package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.web.PageRequests;
import ai.xdev.aisdlc.web.PageResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional governance catalog. Every state transition has an expected prior state and writes
 * a matching append-only audit event before committing.
 */
@Service
public class GovernanceCatalogService {
  private final JdbcTemplate jdbc;
  private final ProjectAccessService access;
  private final AuditService audit;

  public GovernanceCatalogService(JdbcTemplate jdbc, ProjectAccessService access, AuditService audit) {
    this.jdbc = jdbc;
    this.access = access;
    this.audit = audit;
  }

  @Transactional
  public UUID registerKit(UUID organizationId, String actor, String slug, String version, KitLayer layer, String manifest) {
    UUID kitId = UUID.randomUUID();
    jdbc.update("insert into spec_kits(id, organization_id, slug, version, layer, manifest) values (?, ?, ?, ?, ?, cast(? as jsonb))", kitId, organizationId, slug, version, layer.name(), manifest);
    audit.append(organizationId, null, actor, "spec_kit.registered", "spec_kit", kitId.toString(), json("slug", slug, "version", version));
    return kitId;
  }

  public List<Map<String, Object>> listKits(UUID organizationId) {
    return jdbc.queryForList("select id, slug, version, layer, pinned, lifecycle_status as \"lifecycleStatus\", deprecated_at as \"deprecatedAt\", deprecation_reason as \"deprecationReason\", created_at as \"createdAt\" from spec_kits where organization_id = ? order by slug asc, created_at desc", organizationId);
  }

  public PageResponse<Map<String, Object>> listKits(UUID organizationId, String lifecycle, int page, int size) {
    if (lifecycle == null || lifecycle.isBlank()) {
      return page("select count(*) from spec_kits where organization_id = ?", new Object[]{organizationId},
          "select id, slug, version, layer, pinned, lifecycle_status as \"lifecycleStatus\", deprecated_at as \"deprecatedAt\", deprecation_reason as \"deprecationReason\", created_at as \"createdAt\" from spec_kits where organization_id = ? order by slug asc, created_at desc limit ? offset ?", new Object[]{organizationId}, page, size);
    }
    validateLifecycle(lifecycle);
    return page("select count(*) from spec_kits where organization_id = ? and lifecycle_status = ?", new Object[]{organizationId, lifecycle},
        "select id, slug, version, layer, pinned, lifecycle_status as \"lifecycleStatus\", deprecated_at as \"deprecatedAt\", deprecation_reason as \"deprecationReason\", created_at as \"createdAt\" from spec_kits where organization_id = ? and lifecycle_status = ? order by slug asc, created_at desc limit ? offset ?", new Object[]{organizationId, lifecycle}, page, size);
  }

  @Transactional
  public void pinKit(UUID projectId, UUID kitId, int precedence, String actor) {
    if (precedence < 0 || precedence > 10_000) throw new IllegalArgumentException("precedence must be between 0 and 10000");
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    Integer compatible = jdbc.queryForObject("select count(*) from spec_kits where id = ? and organization_id = ? and lifecycle_status = 'ACTIVE'", Integer.class, kitId, project.getOrganizationId());
    if (compatible == null || compatible != 1) throw new IllegalArgumentException("Spec kit is unavailable, deprecated, or belongs to another organization");
    Integer assigned = jdbc.queryForObject("select count(*) from project_kits where project_id = ? and spec_kit_id = ?", Integer.class, projectId, kitId);
    if (assigned != null && assigned > 0) throw new IllegalStateException("Spec kit is already pinned to this project");
    jdbc.update("insert into project_kits(id, project_id, spec_kit_id, precedence) values (?, ?, ?, ?)", UUID.randomUUID(), projectId, kitId, precedence);
    jdbc.update("update spec_kits set pinned = true where id = ?", kitId);
    audit.append(project.getOrganizationId(), projectId, actor, "spec_kit.pinned", "spec_kit", kitId.toString(), "{\"precedence\":" + precedence + "}");
  }

  @Transactional
  public void unpinKit(UUID projectId, UUID kitId, String actor) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    int changed = jdbc.update("delete from project_kits where project_id = ? and spec_kit_id = ?", projectId, kitId);
    if (changed != 1) throw new IllegalStateException("Spec kit is not pinned to this project");
    jdbc.update("update spec_kits set pinned = exists(select 1 from project_kits where spec_kit_id = ?) where id = ?", kitId, kitId);
    audit.append(project.getOrganizationId(), projectId, actor, "spec_kit.unpinned", "spec_kit", kitId.toString(), "{}");
  }

  public List<Map<String, Object>> projectKits(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return jdbc.queryForList("select sk.id, sk.slug, sk.version, sk.layer, sk.lifecycle_status as \"lifecycleStatus\", pk.precedence, pk.pinned_at as \"pinnedAt\" from project_kits pk join spec_kits sk on sk.id = pk.spec_kit_id where pk.project_id = ? order by pk.precedence asc, sk.slug asc", projectId);
  }

  @Transactional
  public void deprecateKit(UUID organizationId, UUID kitId, String actor, String reason) {
    int changed = jdbc.update("update spec_kits set lifecycle_status = 'DEPRECATED', deprecated_at = now(), deprecated_by = ?, deprecation_reason = ? where id = ? and organization_id = ? and lifecycle_status = 'ACTIVE'", actor, reason, kitId, organizationId);
    if (changed != 1) throw new IllegalStateException("Only an active kit in the requested organization can be deprecated");
    audit.append(organizationId, null, actor, "spec_kit.deprecated", "spec_kit", kitId.toString(), json("reason", reason));
  }

  @Transactional
  public UUID addPolicy(UUID organizationId, UUID projectId, String actor, String key, String version, String rule) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into policies(id, organization_id, project_id, key, version, rule) values (?, ?, ?, ?, ?, cast(? as jsonb))", id, organizationId, projectId, key, version, rule);
    audit.append(organizationId, projectId, actor, "policy.created", "policy", id.toString(), json("key", key, "version", version));
    return id;
  }

  public List<Map<String, Object>> listPolicies(UUID projectId, String actor) {
    return listPolicies(projectId, actor, false).items();
  }

  public PageResponse<Map<String, Object>> listPolicies(UUID projectId, String actor, boolean includeInactive) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    String active = includeInactive ? "" : " and active = true";
    return page("select count(*) from policies where organization_id = ? and (project_id is null or project_id = ?)" + active, new Object[]{project.getOrganizationId(), projectId},
        "select id, project_id as \"projectId\", key, version, rule, active, activated_at as \"activatedAt\", activated_by as \"activatedBy\", deactivated_at as \"deactivatedAt\", deactivated_by as \"deactivatedBy\", created_at as \"createdAt\" from policies where organization_id = ? and (project_id is null or project_id = ?)" + active + " order by key asc, created_at desc limit ? offset ?", new Object[]{project.getOrganizationId(), projectId}, 0, 100);
  }

  public PageResponse<Map<String, Object>> listPolicies(UUID projectId, String actor, boolean includeInactive, int page, int size) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    String active = includeInactive ? "" : " and active = true";
    return page("select count(*) from policies where organization_id = ? and (project_id is null or project_id = ?)" + active, new Object[]{project.getOrganizationId(), projectId},
        "select id, project_id as \"projectId\", key, version, rule, active, activated_at as \"activatedAt\", activated_by as \"activatedBy\", deactivated_at as \"deactivatedAt\", deactivated_by as \"deactivatedBy\", created_at as \"createdAt\" from policies where organization_id = ? and (project_id is null or project_id = ?)" + active + " order by key asc, created_at desc limit ? offset ?", new Object[]{project.getOrganizationId(), projectId}, page, size);
  }

  @Transactional
  public void changePolicyStatus(UUID organizationId, UUID projectId, UUID policyId, String actor, boolean active) {
    String timestamp = active ? "activated_at = now(), activated_by = ?, deactivated_at = null, deactivated_by = null" : "deactivated_at = now(), deactivated_by = ?";
    int changed = jdbc.update("update policies set active = ?, " + timestamp + " where id = ? and organization_id = ? and project_id is not distinct from ?", active, actor, policyId, organizationId, projectId);
    if (changed != 1) throw new IllegalStateException("Policy lifecycle transition rejected because the record is unavailable");
    audit.append(organizationId, projectId, actor, active ? "policy.activated" : "policy.deactivated", "policy", policyId.toString(), "{}");
  }

  @Transactional
  public UUID addConstitution(UUID organizationId, UUID projectId, String actor, String version, String content) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into constitutions(id, organization_id, project_id, version, content) values (?, ?, ?, ?, ?)", id, organizationId, projectId, version, content);
    audit.append(organizationId, projectId, actor, "constitution.published", "constitution", id.toString(), json("version", version));
    return id;
  }

  public List<Map<String, Object>> listConstitutions(UUID projectId, String actor) {
    return listConstitutions(projectId, actor, false).items();
  }

  public PageResponse<Map<String, Object>> listConstitutions(UUID projectId, String actor, boolean includeInactive) {
    return listConstitutions(projectId, actor, includeInactive, 0, 100);
  }

  public PageResponse<Map<String, Object>> listConstitutions(UUID projectId, String actor, boolean includeInactive, int page, int size) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    String active = includeInactive ? "" : " and active = true";
    return page("select count(*) from constitutions where organization_id = ? and (project_id is null or project_id = ?)" + active, new Object[]{project.getOrganizationId(), projectId},
        "select id, project_id as \"projectId\", version, content, active, activated_at as \"activatedAt\", activated_by as \"activatedBy\", deactivated_at as \"deactivatedAt\", deactivated_by as \"deactivatedBy\", created_at as \"createdAt\" from constitutions where organization_id = ? and (project_id is null or project_id = ?)" + active + " order by created_at desc limit ? offset ?", new Object[]{project.getOrganizationId(), projectId}, page, size);
  }

  @Transactional
  public void changeConstitutionStatus(UUID organizationId, UUID projectId, UUID constitutionId, String actor, boolean active) {
    String timestamp = active ? "activated_at = now(), activated_by = ?, deactivated_at = null, deactivated_by = null" : "deactivated_at = now(), deactivated_by = ?";
    int changed = jdbc.update("update constitutions set active = ?, " + timestamp + " where id = ? and organization_id = ? and project_id is not distinct from ?", active, actor, constitutionId, organizationId, projectId);
    if (changed != 1) throw new IllegalStateException("Constitution lifecycle transition rejected because the record is unavailable");
    audit.append(organizationId, projectId, actor, active ? "constitution.activated" : "constitution.deactivated", "constitution", constitutionId.toString(), "{}");
  }

  @Transactional
  public UUID grantCapability(UUID projectId, String actor, String subject, String capability, Instant expiresAt) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into capability_grants(id, organization_id, project_id, subject, capability, expires_at) values (?, ?, ?, ?, ?, ?)", id, project.getOrganizationId(), projectId, subject, capability, expiresAt);
    audit.append(project.getOrganizationId(), projectId, actor, "capability.granted", "capability_grant", id.toString(), json("subject", subject, "capability", capability));
    return id;
  }

  public PageResponse<Map<String, Object>> listCapabilities(UUID projectId, String actor, boolean includeExpired, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    String expiration = includeExpired ? "" : " and (expires_at is null or expires_at > now())";
    return page("select count(*) from capability_grants where project_id = ?" + expiration, new Object[]{projectId},
        "select id, subject, capability, expires_at as \"expiresAt\", created_at as \"createdAt\" from capability_grants where project_id = ?" + expiration + " order by created_at desc limit ? offset ?", new Object[]{projectId}, page, size);
  }

  @Transactional
  public UUID requestException(UUID projectId, String actor, String policyKey, String rationale) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exception_requests(id, organization_id, project_id, requested_by, policy_key, rationale, status) values (?, ?, ?, ?, ?, ?, 'PENDING')", id, project.getOrganizationId(), projectId, actor, policyKey, rationale);
    audit.append(project.getOrganizationId(), projectId, actor, "exception.requested", "exception_request", id.toString(), json("policyKey", policyKey));
    return id;
  }

  @Transactional
  public void decideException(UUID projectId, UUID exceptionId, String actor, ReviewStatus decision, String note, Instant expiresAt) {
    if (decision != ReviewStatus.APPROVED && decision != ReviewStatus.REJECTED) throw new IllegalArgumentException("An exception must be approved or rejected by a human");
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER);
    if (decision == ReviewStatus.APPROVED && (expiresAt == null || !expiresAt.isAfter(Instant.now()))) throw new IllegalArgumentException("An approved exception requires a future expiry time");
    int changed = jdbc.update("update exception_requests set status = ?, decided_by = ?, decided_at = now(), decision_note = ?, expires_at = ? where id = ? and project_id = ? and status = 'PENDING'", decision.name(), actor, note, expiresAt, exceptionId, projectId);
    if (changed != 1) throw new IllegalStateException("Exception request is no longer pending");
    audit.append(project.getOrganizationId(), projectId, actor, "exception.decided", "exception_request", exceptionId.toString(), json("decision", decision.name()));
  }

  public PageResponse<Map<String, Object>> exceptions(UUID projectId, String actor, String status, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    if (status == null || status.isBlank()) {
      return page("select count(*) from exception_requests where project_id = ?", new Object[]{projectId},
          "select id, requested_by as \"requestedBy\", policy_key as \"policyKey\", rationale, status, decided_by as \"decidedBy\", decided_at as \"decidedAt\", decision_note as \"decisionNote\", expires_at as \"expiresAt\", created_at as \"createdAt\" from exception_requests where project_id = ? order by case when status = 'PENDING' then 0 else 1 end, created_at desc limit ? offset ?", new Object[]{projectId}, page, size);
    }
    validateReviewStatus(status);
    return page("select count(*) from exception_requests where project_id = ? and status = ?", new Object[]{projectId, status},
        "select id, requested_by as \"requestedBy\", policy_key as \"policyKey\", rationale, status, decided_by as \"decidedBy\", decided_at as \"decidedAt\", decision_note as \"decisionNote\", expires_at as \"expiresAt\", created_at as \"createdAt\" from exception_requests where project_id = ? and status = ? order by created_at desc limit ? offset ?", new Object[]{projectId, status}, page, size);
  }

  @Transactional
  public UUID addTraceNode(UUID projectId, String actor, TraceNodeType type, String externalKey, String label, String status) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into trace_nodes(id, project_id, node_type, external_key, label, status) values (?, ?, ?, ?, ?, ?)", id, projectId, type.name(), externalKey, label, status);
    audit.append(project.getOrganizationId(), projectId, actor, "trace.node.created", "trace_node", id.toString(), json("type", type.name()));
    return id;
  }

  @Transactional
  public UUID addTraceEdge(UUID projectId, String actor, UUID sourceId, UUID targetId, String relation) {
    if (sourceId.equals(targetId)) throw new IllegalArgumentException("A trace edge cannot reference the same source and target node");
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    Integer nodes = jdbc.queryForObject("select count(*) from trace_nodes where project_id = ? and id in (?, ?)", Integer.class, projectId, sourceId, targetId);
    if (nodes == null || nodes != 2) throw new IllegalArgumentException("Both trace nodes must belong to the project");
    UUID id = UUID.randomUUID();
    jdbc.update("insert into trace_edges(id, project_id, source_node_id, target_node_id, relation) values (?, ?, ?, ?, ?)", id, projectId, sourceId, targetId, relation);
    audit.append(project.getOrganizationId(), projectId, actor, "trace.edge.created", "trace_edge", id.toString(), json("relation", relation));
    return id;
  }

  public Map<String, List<Map<String, Object>>> traceability(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return Map.of("nodes", jdbc.queryForList("select id, node_type as \"nodeType\", external_key as \"externalKey\", label, status, created_at as \"createdAt\" from trace_nodes where project_id = ? order by created_at", projectId), "edges", jdbc.queryForList("select id, source_node_id as \"sourceNodeId\", target_node_id as \"targetNodeId\", relation from trace_edges where project_id = ? order by id", projectId));
  }

  @Transactional
  public UUID requestReview(UUID projectId, String actor, ReviewType type, String title) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into review_items(id, project_id, review_type, title, status, requested_by) values (?, ?, ?, ?, 'PENDING', ?)", id, projectId, type.name(), title, actor);
    audit.append(project.getOrganizationId(), projectId, actor, "review.requested", "review_item", id.toString(), json("type", type.name()));
    return id;
  }

  @Transactional
  public void decideReview(UUID projectId, UUID reviewId, String actor, ReviewStatus decision, String note) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER);
    if (decision == ReviewStatus.PENDING) throw new IllegalArgumentException("A human review decision must be final");
    int changed = jdbc.update("update review_items set status = ?, decided_by = ?, decision_note = ?, decided_at = now(), updated_at = now() where id = ? and project_id = ? and status = 'PENDING'", decision.name(), actor, note, reviewId, projectId);
    if (changed != 1) throw new IllegalStateException("Review item is no longer pending");
    audit.append(project.getOrganizationId(), projectId, actor, "review.decided", "review_item", reviewId.toString(), json("decision", decision.name()));
  }

  public List<Map<String, Object>> reviewQueue(UUID projectId, String actor) {
    return reviewQueue(projectId, actor, null, 0, 100).items();
  }

  public PageResponse<Map<String, Object>> reviewQueue(UUID projectId, String actor, String status, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    if (status == null || status.isBlank()) {
      return page("select count(*) from review_items where project_id = ?", new Object[]{projectId},
          "select id, review_type as \"reviewType\", title, status, requested_by as \"requestedBy\", decided_by as \"decidedBy\", decision_note as \"decisionNote\", decided_at as \"decidedAt\", created_at as \"createdAt\", updated_at as \"updatedAt\" from review_items where project_id = ? order by case when status = 'PENDING' then 0 else 1 end, created_at desc limit ? offset ?", new Object[]{projectId}, page, size);
    }
    validateReviewStatus(status);
    return page("select count(*) from review_items where project_id = ? and status = ?", new Object[]{projectId, status},
        "select id, review_type as \"reviewType\", title, status, requested_by as \"requestedBy\", decided_by as \"decidedBy\", decision_note as \"decisionNote\", decided_at as \"decidedAt\", created_at as \"createdAt\", updated_at as \"updatedAt\" from review_items where project_id = ? and status = ? order by created_at desc limit ? offset ?", new Object[]{projectId, status}, page, size);
  }

  @Transactional
  public UUID writeMetrics(UUID projectId, String actor, Instant start, Instant end, BigDecimal deploymentFrequency, BigDecimal leadTime, BigDecimal failureRate, BigDecimal reviewDelta, BigDecimal reworkRate, BigDecimal queueHealth, BigDecimal alignment) {
    if (!end.isAfter(start)) throw new IllegalArgumentException("Metric period end must be after period start");
    nonNegative("deploymentFrequency", deploymentFrequency); nonNegative("leadTime", leadTime); nonNegative("reviewDelta", reviewDelta);
    unitInterval("failureRate", failureRate); unitInterval("reworkRate", reworkRate); unitInterval("queueHealth", queueHealth); unitInterval("alignment", alignment);
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into quality_metric_snapshots(id, project_id, period_start, period_end, deployment_frequency, lead_time_hours, change_failure_rate, pr_review_time_delta_hours, rework_rate, review_queue_health, spec_alignment_score) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, projectId, timestamp(start), timestamp(end), deploymentFrequency, leadTime, failureRate, reviewDelta, reworkRate, queueHealth, alignment);
    audit.append(project.getOrganizationId(), projectId, actor, "quality_metrics.recorded", "quality_metric_snapshot", id.toString(), "{}");
    return id;
  }

  public List<Map<String, Object>> metrics(UUID projectId, String actor) {
    return metrics(projectId, actor, 0, 24).items();
  }

  public PageResponse<Map<String, Object>> metrics(UUID projectId, String actor, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return page("select count(*) from quality_metric_snapshots where project_id = ?", new Object[]{projectId},
        "select id, period_start as \"periodStart\", period_end as \"periodEnd\", deployment_frequency as \"deploymentFrequency\", lead_time_hours as \"leadTimeHours\", change_failure_rate as \"changeFailureRate\", pr_review_time_delta_hours as \"prReviewTimeDeltaHours\", rework_rate as \"reworkRate\", review_queue_health as \"reviewQueueHealth\", spec_alignment_score as \"specAlignmentScore\", calculated_at as \"calculatedAt\" from quality_metric_snapshots where project_id = ? order by period_end desc limit ? offset ?", new Object[]{projectId}, page, size);
  }

  private PageResponse<Map<String, Object>> page(String countSql, Object[] countParameters, String pageSql, Object[] pageParameters, int page, int size) {
    int offset = PageRequests.offset(page, size);
    Long total = jdbc.queryForObject(countSql, Long.class, countParameters);
    Object[] parameters = Arrays.copyOf(pageParameters, pageParameters.length + 2);
    parameters[parameters.length - 2] = size;
    parameters[parameters.length - 1] = offset;
    return PageResponse.of(jdbc.queryForList(pageSql, parameters), page, size, total == null ? 0 : total);
  }

  private void validateLifecycle(String lifecycle) {
    if (!"ACTIVE".equals(lifecycle) && !"DEPRECATED".equals(lifecycle)) throw new IllegalArgumentException("lifecycle must be ACTIVE or DEPRECATED");
  }

  private void validateReviewStatus(String status) {
    try { ReviewStatus.valueOf(status); }
    catch (IllegalArgumentException error) { throw new IllegalArgumentException("Unsupported review status"); }
  }

  private void nonNegative(String name, BigDecimal value) {
    if (value != null && value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
  }

  private void unitInterval(String name, BigDecimal value) {
    if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) throw new IllegalArgumentException(name + " must be between 0 and 1");
  }

  /**
   * PostgreSQL's driver cannot infer a SQL type for {@link Instant}, so a positional bind of one fails at execution
   * with "Can't infer the SQL type to use for an instance of java.time.Instant" — reported as bad SQL grammar, which
   * sends the reader looking at the statement instead of the argument.
   *
   * <p>Recording a quality metric period had never worked for this reason: every write returned 500 and the Quality
   * screen therefore always showed "No calculated quality period is available", which reads as an absence of data
   * rather than a broken endpoint. This is the third place in this codebase to hit the same defect, after the
   * inference cost ledger and the risk-intelligence counters.
   */
  private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }

  private String json(String... entries) {
    if (entries.length % 2 != 0) throw new IllegalArgumentException("JSON entries require key-value pairs");
    StringJoiner joiner = new StringJoiner(",", "{", "}");
    for (int index = 0; index < entries.length; index += 2) joiner.add("\"" + escape(entries[index]) + "\":\"" + escape(entries[index + 1]) + "\"");
    return joiner.toString();
  }

  private String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }
}
