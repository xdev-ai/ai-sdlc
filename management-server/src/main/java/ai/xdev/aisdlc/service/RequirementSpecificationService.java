package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import ai.xdev.aisdlc.web.PageRequests;
import ai.xdev.aisdlc.web.PageResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which immutable document version specifies which requirement.
 *
 * <p>V22 created {@code requirement_specifications} and nothing used it, so the question it exists to answer — "which
 * version of which analysis document specifies this requirement, and what did it say before" — had no API.
 *
 * <p>The schema decides the shape of this service, not the reverse. Links are append-only and a partial unique index
 * permits exactly one open link per requirement, so re-specifying is close-then-insert inside one transaction rather
 * than an update. That is the whole point: an overwrite would destroy the record of what a past release was specified
 * against, which is the only reason to keep this history at all.
 */
@Service
public class RequirementSpecificationService {
  private final JdbcTemplate jdbc;
  private final ProjectAccessService access;
  private final AuditService audit;

  public RequirementSpecificationService(JdbcTemplate jdbc, ProjectAccessService access, AuditService audit) {
    this.jdbc = jdbc;
    this.access = access;
    this.audit = audit;
  }

  /**
   * Link a requirement to the document version that specifies it, superseding whatever governed it before.
   *
   * <p>Superseding is done here rather than left to the caller because the two statements have to be one transaction:
   * a close without an insert leaves a requirement unspecified, and an insert without a close violates the partial
   * unique index. Letting a client sequence them means one failed request can leave the ledger in either state.
   *
   * @param reason why the previous link no longer governs; required when one is being replaced, because a supersede
   *     with no stated reason is the case this table exists to prevent
   */
  @Transactional
  public UUID link(UUID projectId, String actor, UUID traceNodeId, UUID specKitId, String sourceDocumentCode, String reason) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);

    // Both ends must belong where the caller says they do, or the link would assert provenance across projects.
    Integer node = jdbc.queryForObject("select count(*) from trace_nodes where id = ? and project_id = ?", Integer.class, traceNodeId, projectId);
    if (node == null || node != 1) throw new IllegalArgumentException("The requirement does not belong to this project");
    Integer kit = jdbc.queryForObject("select count(*) from spec_kits where id = ? and organization_id = ?", Integer.class, specKitId, project.getOrganizationId());
    if (kit == null || kit != 1) throw new IllegalArgumentException("The specification document does not belong to this organization");

    // A deprecated document version must not become the current authority for a requirement. It stays readable in
    // history — superseded links keep pointing at it — but it cannot be newly assigned.
    String lifecycle = jdbc.queryForObject(
        "select lifecycle_status as \"lifecycleStatus\" from spec_kits where id = ?", String.class, specKitId);
    if (!"ACTIVE".equals(lifecycle)) {
      throw new IllegalStateException("A " + lifecycle + " document version cannot be assigned as the current specification");
    }

    UUID superseded = currentLinkId(traceNodeId);
    if (superseded != null) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("Superseding an existing specification requires a reason");
      }
      jdbc.update("update requirement_specifications set superseded_by = ?, superseded_at = now(), supersede_reason = ? where id = ?",
          actor, reason.trim(), superseded);
    }

    UUID id = UUID.randomUUID();
    try {
      jdbc.update("""
          insert into requirement_specifications(id, tenant_id, project_id, trace_node_id, spec_kit_id, source_document_code, linked_by)
          values (?, (select tenant_id from projects where id = ?), ?, ?, ?, ?, ?)
          """, id, projectId, projectId, traceNodeId, specKitId, sourceDocumentCode.trim(), actor);
    } catch (DuplicateKeyException conflict) {
      // The partial unique index fired, which means another transaction opened a link for this requirement between
      // the read above and this insert. Reporting the race as a conflict is honest; retrying would silently pick a
      // winner and lose the other caller's reason.
      throw new IllegalStateException("Another specification was linked to this requirement concurrently", conflict);
    }

    audit.append(project.getOrganizationId(), projectId, actor, "requirement.specification.linked", "requirement_specification",
        id.toString(), json("documentCode", sourceDocumentCode.trim(), "supersededLink", superseded == null ? "" : superseded.toString()));
    return id;
  }

  /**
   * Close the current link without opening another, for a requirement whose governing document was withdrawn.
   *
   * <p>Kept separate from {@link #link} because "this requirement is no longer specified by anything" is a real state
   * and a caller must be able to say it deliberately, rather than by linking a placeholder document.
   */
  @Transactional
  public void unlink(UUID projectId, String actor, UUID traceNodeId, String reason) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Closing a specification link requires a reason");
    UUID current = currentLinkId(traceNodeId);
    if (current == null) throw new IllegalArgumentException("This requirement has no current specification");
    Integer owned = jdbc.queryForObject("select count(*) from requirement_specifications where id = ? and project_id = ?", Integer.class, current, projectId);
    if (owned == null || owned != 1) throw new IllegalArgumentException("The requirement does not belong to this project");
    jdbc.update("update requirement_specifications set superseded_by = ?, superseded_at = now(), supersede_reason = ? where id = ?",
        actor, reason.trim(), current);
    audit.append(project.getOrganizationId(), projectId, actor, "requirement.specification.closed", "requirement_specification",
        current.toString(), json("traceNode", traceNodeId.toString()));
  }

  /** The current specification of every requirement in the project, newest link first. */
  public PageResponse<Map<String, Object>> current(UUID projectId, String actor, int page, int size) {
    readAccess(projectId, actor);
    return page("select count(*) from requirement_specifications where project_id = ? and superseded_at is null", new Object[]{projectId},
        CURRENT_SQL + " order by rs.linked_at desc limit ? offset ?", new Object[]{projectId}, page, size);
  }

  /**
   * Every link a requirement has ever had, current first then most recently closed.
   *
   * <p>This is the read the table was built for, so it returns superseded rows deliberately: the reason a link was
   * replaced is as much the record as the link itself.
   */
  public List<Map<String, Object>> history(UUID projectId, String actor, UUID traceNodeId) {
    readAccess(projectId, actor);
    return jdbc.queryForList(CURRENT_SQL_ALL + " and rs.trace_node_id = ? order by rs.superseded_at is not null, rs.linked_at desc",
        projectId, traceNodeId);
  }

  /**
   * Requirements in the project with no current specification.
   *
   * <p>A traceability matrix that only lists what is linked reads as complete when it is not. This is the gap report:
   * requirements nothing currently specifies.
   */
  public List<Map<String, Object>> unspecified(UUID projectId, String actor) {
    readAccess(projectId, actor);
    return jdbc.queryForList("""
        select n.id, n.external_key as "externalKey", n.label, n.status, n.created_at as "createdAt"
        from trace_nodes n
        where n.project_id = ? and n.node_type = 'REQUIREMENT'
          and not exists (select 1 from requirement_specifications rs
                          where rs.trace_node_id = n.id and rs.superseded_at is null)
        order by n.external_key
        """, projectId);
  }

  /** Which requirements a given document version currently governs — the reverse read, for impact analysis. */
  public List<Map<String, Object>> requirementsFor(UUID projectId, String actor, UUID specKitId) {
    readAccess(projectId, actor);
    return jdbc.queryForList(CURRENT_SQL + " and rs.spec_kit_id = ? order by n.external_key", projectId, specKitId);
  }

  // Aliases are quoted because PostgreSQL folds unquoted identifiers to lower case, which would send camelCase keys
  // to the client as camelcase and break every consumer reading them.
  private static final String SELECT = """
      select rs.id, rs.trace_node_id as "traceNodeId", n.external_key as "requirementKey", n.label as "requirementLabel",
             rs.spec_kit_id as "specKitId", k.slug as "documentSlug", k.version as "documentVersion",
             k.lifecycle_status as "documentLifecycle", rs.source_document_code as "sourceDocumentCode",
             rs.linked_by as "linkedBy", rs.linked_at as "linkedAt",
             rs.superseded_by as "supersededBy", rs.superseded_at as "supersededAt", rs.supersede_reason as "supersedeReason"
      from requirement_specifications rs
      join trace_nodes n on n.id = rs.trace_node_id
      join spec_kits k on k.id = rs.spec_kit_id
      where rs.project_id = ?""";
  private static final String CURRENT_SQL = SELECT + " and rs.superseded_at is null";
  private static final String CURRENT_SQL_ALL = SELECT;

  private void readAccess(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
  }

  private UUID currentLinkId(UUID traceNodeId) {
    List<UUID> open = jdbc.queryForList(
        "select id from requirement_specifications where trace_node_id = ? and superseded_at is null", UUID.class, traceNodeId);
    return open.isEmpty() ? null : open.get(0);
  }

  private PageResponse<Map<String, Object>> page(String countSql, Object[] countParameters, String pageSql, Object[] pageParameters, int page, int size) {
    int offset = PageRequests.offset(page, size);
    Long total = jdbc.queryForObject(countSql, Long.class, countParameters);
    Object[] parameters = Arrays.copyOf(pageParameters, pageParameters.length + 2);
    parameters[parameters.length - 2] = size;
    parameters[parameters.length - 1] = offset;
    return PageResponse.of(jdbc.queryForList(pageSql, parameters), page, size, total == null ? 0 : total);
  }

  private String json(String... entries) {
    StringJoiner joiner = new StringJoiner(",", "{", "}");
    for (int index = 0; index < entries.length; index += 2) joiner.add("\"" + escape(entries[index]) + "\":\"" + escape(entries[index + 1]) + "\"");
    return joiner.toString();
  }

  private String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }
}
