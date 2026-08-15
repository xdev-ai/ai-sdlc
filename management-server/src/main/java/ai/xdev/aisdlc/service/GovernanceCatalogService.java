package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.service.ProjectAccessService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GovernanceCatalogService {
  private final JdbcTemplate jdbc;
  private final ProjectAccessService access;
  private final AuditService audit;
  public GovernanceCatalogService(JdbcTemplate jdbc, ProjectAccessService access, AuditService audit) { this.jdbc = jdbc; this.access = access; this.audit = audit; }

  @Transactional
  public UUID registerKit(UUID organizationId, String actor, String slug, String version, KitLayer layer, String manifest) {
    UUID kitId = UUID.randomUUID();
    jdbc.update("insert into spec_kits(id, organization_id, slug, version, layer, manifest) values (?, ?, ?, ?, ?, cast(? as jsonb))", kitId, organizationId, slug, version, layer.name(), manifest);
    audit.append(organizationId, null, actor, "spec_kit.registered", "spec_kit", kitId.toString(), "{\"slug\":\"" + slug + "\",\"version\":\"" + version + "\"}");
    return kitId;
  }
  public List<Map<String, Object>> listKits(UUID organizationId) { return jdbc.queryForList("select id, slug, version, layer, pinned, created_at from spec_kits where organization_id = ? order by slug, version desc", organizationId); }
  @Transactional
  public void pinKit(UUID projectId, UUID kitId, int precedence, String actor) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    jdbc.update("insert into project_kits(id, project_id, spec_kit_id, precedence) values (?, ?, ?, ?)", UUID.randomUUID(), projectId, kitId, precedence);
    jdbc.update("update spec_kits set pinned = true where id = ?", kitId);
    audit.append(project.getOrganizationId(), projectId, actor, "spec_kit.pinned", "spec_kit", kitId.toString(), "{\"precedence\":" + precedence + "}");
  }

  @Transactional
  public UUID addPolicy(UUID organizationId, UUID projectId, String actor, String key, String version, String rule) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into policies(id, organization_id, project_id, key, version, rule) values (?, ?, ?, ?, ?, cast(? as jsonb))", id, organizationId, projectId, key, version, rule);
    audit.append(organizationId, projectId, actor, "policy.created", "policy", id.toString(), "{\"key\":\"" + key + "\",\"version\":\"" + version + "\"}");
    return id;
  }
  public List<Map<String, Object>> listPolicies(UUID projectId, String actor) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return jdbc.queryForList("select id, key, version, rule, active, created_at from policies where organization_id = ? and (project_id is null or project_id = ?) and active = true order by key, version desc", project.getOrganizationId(), projectId);
  }
  @Transactional
  public UUID addConstitution(UUID organizationId, UUID projectId, String actor, String version, String content) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into constitutions(id, organization_id, project_id, version, content) values (?, ?, ?, ?, ?)", id, organizationId, projectId, version, content);
    audit.append(organizationId, projectId, actor, "constitution.published", "constitution", id.toString(), "{\"version\":\"" + version + "\"}");
    return id;
  }
  public List<Map<String, Object>> listConstitutions(UUID projectId, String actor) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return jdbc.queryForList("select id, version, content, active, created_at from constitutions where organization_id = ? and (project_id is null or project_id = ?) and active = true order by created_at desc", project.getOrganizationId(), projectId);
  }
  @Transactional
  public UUID grantCapability(UUID projectId, String actor, String subject, String capability, Instant expiresAt) {
    var project = access.requireProject(projectId);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into capability_grants(id, organization_id, project_id, subject, capability, expires_at) values (?, ?, ?, ?, ?, ?)", id, project.getOrganizationId(), projectId, subject, capability, expiresAt);
    audit.append(project.getOrganizationId(), projectId, actor, "capability.granted", "capability_grant", id.toString(), "{\"subject\":\"" + subject + "\",\"capability\":\"" + capability + "\"}");
    return id;
  }
  @Transactional
  public UUID requestException(UUID projectId, String actor, String policyKey, String rationale) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exception_requests(id, organization_id, project_id, requested_by, policy_key, rationale, status) values (?, ?, ?, ?, ?, ?, 'PENDING')", id, project.getOrganizationId(), projectId, actor, policyKey, rationale);
    audit.append(project.getOrganizationId(), projectId, actor, "exception.requested", "exception_request", id.toString(), "{\"policyKey\":\"" + policyKey + "\"}");
    return id;
  }

  @Transactional
  public UUID addTraceNode(UUID projectId, String actor, TraceNodeType type, String externalKey, String label, String status) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into trace_nodes(id, project_id, node_type, external_key, label, status) values (?, ?, ?, ?, ?, ?)", id, projectId, type.name(), externalKey, label, status);
    audit.append(project.getOrganizationId(), projectId, actor, "trace.node.created", "trace_node", id.toString(), "{\"type\":\"" + type + "\"}");
    return id;
  }
  @Transactional
  public UUID addTraceEdge(UUID projectId, String actor, UUID sourceId, UUID targetId, String relation) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into trace_edges(id, project_id, source_node_id, target_node_id, relation) values (?, ?, ?, ?, ?)", id, projectId, sourceId, targetId, relation);
    audit.append(project.getOrganizationId(), projectId, actor, "trace.edge.created", "trace_edge", id.toString(), "{\"relation\":\"" + relation + "\"}");
    return id;
  }
  public Map<String, List<Map<String, Object>>> traceability(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return Map.of("nodes", jdbc.queryForList("select id, node_type, external_key, label, status from trace_nodes where project_id = ? order by created_at", projectId), "edges", jdbc.queryForList("select id, source_node_id, target_node_id, relation from trace_edges where project_id = ?", projectId));
  }

  @Transactional
  public UUID requestReview(UUID projectId, String actor, ReviewType type, String title) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into review_items(id, project_id, review_type, title, status, requested_by) values (?, ?, ?, ?, 'PENDING', ?)", id, projectId, type.name(), title, actor);
    audit.append(project.getOrganizationId(), projectId, actor, "review.requested", "review_item", id.toString(), "{\"type\":\"" + type + "\"}");
    return id;
  }
  @Transactional
  public void decideReview(UUID projectId, UUID reviewId, String actor, ReviewStatus decision, String note) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER);
    if (decision == ReviewStatus.PENDING) throw new IllegalArgumentException("A human review decision must be final");
    jdbc.update("update review_items set status = ?, decided_by = ?, decision_note = ?, decided_at = now() where id = ? and project_id = ? and status = 'PENDING'", decision.name(), actor, note, reviewId, projectId);
    audit.append(project.getOrganizationId(), projectId, actor, "review.decided", "review_item", reviewId.toString(), "{\"decision\":\"" + decision + "\"}");
  }
  public List<Map<String, Object>> reviewQueue(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return jdbc.queryForList("select id, review_type, title, status, requested_by, decided_by, decision_note, decided_at, created_at from review_items where project_id = ? order by case when status = 'PENDING' then 0 else 1 end, created_at desc", projectId);
  }

  @Transactional
  public UUID writeMetrics(UUID projectId, String actor, Instant start, Instant end, BigDecimal deploymentFrequency, BigDecimal leadTime, BigDecimal failureRate, BigDecimal reviewDelta, BigDecimal reworkRate, BigDecimal queueHealth, BigDecimal alignment) {
    var project = access.requireProject(projectId);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into quality_metric_snapshots(id, project_id, period_start, period_end, deployment_frequency, lead_time_hours, change_failure_rate, pr_review_time_delta_hours, rework_rate, review_queue_health, spec_alignment_score) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, projectId, start, end, deploymentFrequency, leadTime, failureRate, reviewDelta, reworkRate, queueHealth, alignment);
    audit.append(project.getOrganizationId(), projectId, actor, "quality_metrics.recorded", "quality_metric_snapshot", id.toString(), "{}");
    return id;
  }
  public List<Map<String, Object>> metrics(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return jdbc.queryForList("select * from quality_metric_snapshots where project_id = ? order by period_end desc limit 24", projectId);
  }
}

