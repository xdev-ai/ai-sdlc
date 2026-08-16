package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems single-use tool capability grants.
 *
 * <p>The broker is the only component that may authorize a tool action, and it authorizes an exact set of arguments
 * rather than a tool name: the grant is bound to a canonical argument fingerprint, so a model that proposes one call
 * and then executes another is refused. A grant is tenant-scoped, expires on a bounded deadline, and can be redeemed
 * exactly once — the redemption is an atomic conditional update, so two concurrent replays cannot both win.
 *
 * <p>No raw prompt, model output, or tool argument is persisted. The stored record holds digests, the capability scope,
 * the policy and approval linkage, and lifecycle timestamps. The grant secret is returned to the caller once and only
 * its SHA-256 is stored, so reading the database does not yield a usable grant.
 */
@Service
public class RuntimeAiToolBrokerService {
  /** Result of a grant request; {@code grantSecret} is non-null only on an allowed issue and is never stored. */
  public record GrantIssue(String outcome, String reasonCode, UUID grantId, String grantSecret, Instant expiresAt,
                           String argumentFingerprint, UUID runtimeDecisionId) {
    public boolean allowed() { return "ALLOW".equals(outcome); }
  }

  /** Result of redeeming a grant; the receipt digest links the execution attempt to its authorization. */
  public record GrantRedemption(String outcome, String reasonCode, UUID grantId, String receiptSha256) {
    public boolean allowed() { return "ALLOW".equals(outcome); }
  }

  private record Capability(UUID id, String impactLevel, boolean requiresApproval, String manifestSha256) {}

  static final Duration MIN_TTL = Duration.ofSeconds(1);
  static final Duration MAX_TTL = Duration.ofSeconds(300);
  static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final RuntimeAiBrokerService broker;
  private final ProjectAccessService access;
  private final AuditService audit;

  public RuntimeAiToolBrokerService(JdbcTemplate jdbc, RuntimeAiBrokerService broker, ProjectAccessService access, AuditService audit) {
    this.jdbc = jdbc; this.broker = broker; this.access = access; this.audit = audit;
  }

  /**
   * Authorizes one tool invocation and, when allowed, issues a single-use grant for exactly those arguments.
   *
   * <p>The policy context is derived here from bounded facts rather than accepted from the caller, so a workload cannot
   * feed the policy engine its own evidence.
   */
  @Transactional
  public GrantIssue issue(UUID projectId, String workloadSubject, UUID agentSessionId, String toolName,
                          JsonNode arguments, UUID approvalRequestId, Duration requestedTtl) {
    if (agentSessionId == null || arguments == null || !arguments.isObject()) {
      throw new IllegalArgumentException("A tool grant requires a session and a JSON object of arguments");
    }
    bounded(toolName, 160);
    Duration ttl = boundedTtl(requestedTtl);
    String argumentFingerprint = sha256(canonical(arguments));

    Capability capability;
    try {
      capability = capability(projectId, toolName.trim());
    } catch (EmptyResultDataAccessException absent) {
      return new GrantIssue("DENY", "TOOL_NOT_ALLOWLISTED", null, null, null, argumentFingerprint, null);
    }

    var authorization = broker.authorizeTool(projectId, workloadSubject, agentSessionId, toolName.trim(),
        argumentFingerprint, policyContext(toolName.trim(), capability, argumentFingerprint, approvalRequestId), approvalRequestId, false);
    if (!authorization.allowed()) {
      return new GrantIssue("DENY", authorization.reasonCode(), null, null, null, argumentFingerprint, authorization.runtimeDecisionId());
    }
    // Defense in depth: the broker already requires an approved request for a high-impact tool, and the schema
    // repeats the rule, so a future change to either one cannot silently issue an unapproved high-impact grant.
    if ("HIGH_IMPACT".equals(capability.impactLevel()) && approvalRequestId == null) {
      return new GrantIssue("DENY", "HUMAN_APPROVAL_REQUIRED", null, null, null, argumentFingerprint, authorization.runtimeDecisionId());
    }

    var project = access.requireProject(projectId);
    byte[] secretBytes = new byte[32];
    RANDOM.nextBytes(secretBytes);
    String grantSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    UUID grantId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(ttl);
    jdbc.update("insert into runtime_ai_tool_grants(id,tenant_id,project_id,tool_capability_id,workload_subject,agent_session_id,runtime_decision_id,approval_request_id,capability_scope,tool_manifest_sha256,argument_fingerprint,grant_nonce_sha256,grant_status,reason_code,expires_at) "
            + "values(?,?,?,?,?,?,?,?,?,?,?,?,'ISSUED',?,?)",
        grantId, project.getTenantId(), projectId, capability.id(), workloadSubject, agentSessionId,
        authorization.runtimeDecisionId(), approvalRequestId, capability.impactLevel(), capability.manifestSha256(),
        argumentFingerprint, sha256(grantSecret), authorization.reasonCode(), java.sql.Timestamp.from(expiresAt));
    audit.append(project.getOrganizationId(), projectId, workloadSubject, "runtime_ai.tool_grant_issued", "runtime_ai_tool_grant", grantId.toString(),
        "{\"capabilityScope\":\"" + capability.impactLevel() + "\",\"argumentFingerprint\":\"" + argumentFingerprint
            + "\",\"approvalRequestId\":" + quotedOrNull(approvalRequestId) + ",\"expiresAt\":\"" + expiresAt + "\"}");
    return new GrantIssue("ALLOW", authorization.reasonCode(), grantId, grantSecret, expiresAt, argumentFingerprint, authorization.runtimeDecisionId());
  }

  /**
   * Redeems a grant for the exact arguments it authorized.
   *
   * <p>The state transition is a single conditional update, so a replayed secret, an expired deadline, a different
   * workload, or altered arguments all fail without a second code path deciding the outcome.
   */
  @Transactional
  public GrantRedemption redeem(UUID projectId, String workloadSubject, String grantSecret, JsonNode arguments) {
    if (grantSecret == null || grantSecret.isBlank() || arguments == null || !arguments.isObject()) {
      throw new IllegalArgumentException("A redemption requires a grant secret and a JSON object of arguments");
    }
    String nonceSha256 = sha256(grantSecret);
    String argumentFingerprint = sha256(canonical(arguments));
    String receipt = sha256(nonceSha256 + "|" + argumentFingerprint);
    int claimed = jdbc.update("update runtime_ai_tool_grants set grant_status='REDEEMED', redeemed_at=now(), receipt_sha256=? "
            + "where grant_nonce_sha256=? and project_id=? and workload_subject=? and argument_fingerprint=? and grant_status='ISSUED' and expires_at > now()",
        receipt, nonceSha256, projectId, workloadSubject, argumentFingerprint);
    if (claimed == 1) {
      UUID grantId = jdbc.queryForObject("select id from runtime_ai_tool_grants where grant_nonce_sha256=?", UUID.class, nonceSha256);
      var project = access.requireProject(projectId);
      audit.append(project.getOrganizationId(), projectId, workloadSubject, "runtime_ai.tool_grant_redeemed", "runtime_ai_tool_grant", String.valueOf(grantId),
          "{\"argumentFingerprint\":\"" + argumentFingerprint + "\",\"receiptSha256\":\"" + receipt + "\"}");
      return new GrantRedemption("ALLOW", "GRANT_REDEEMED", grantId, receipt);
    }
    return diagnose(projectId, workloadSubject, nonceSha256, argumentFingerprint);
  }

  /** Human revocation of an unredeemed grant; a redeemed grant is history and is never rewritten. */
  @Transactional
  public boolean revoke(UUID projectId, String actor, UUID grantId) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    int revoked = jdbc.update("update runtime_ai_tool_grants set grant_status='REVOKED' where id=? and project_id=? and grant_status='ISSUED'", grantId, projectId);
    if (revoked == 1) {
      audit.append(project.getOrganizationId(), projectId, actor, "runtime_ai.tool_grant_revoked", "runtime_ai_tool_grant", grantId.toString(), "{}");
    }
    return revoked == 1;
  }

  private GrantRedemption diagnose(UUID projectId, String workloadSubject, String nonceSha256, String argumentFingerprint) {
    Map<String, Object> row;
    try {
      row = jdbc.queryForMap("select id,workload_subject,argument_fingerprint,grant_status,expires_at <= now() as expired from runtime_ai_tool_grants where grant_nonce_sha256=? and project_id=?", nonceSha256, projectId);
    } catch (EmptyResultDataAccessException absent) {
      return new GrantRedemption("DENY", "GRANT_UNKNOWN", null, null);
    }
    UUID grantId = (UUID) row.get("id");
    String status = String.valueOf(row.get("grant_status"));
    if (!"ISSUED".equals(status)) {
      return new GrantRedemption("DENY", "REDEEMED".equals(status) ? "GRANT_ALREADY_REDEEMED" : "GRANT_" + status, grantId, null);
    }
    if (Boolean.TRUE.equals(row.get("expired"))) {
      jdbc.update("update runtime_ai_tool_grants set grant_status='EXPIRED' where id=? and grant_status='ISSUED'", grantId);
      return new GrantRedemption("DENY", "GRANT_EXPIRED", grantId, null);
    }
    if (!workloadSubject.equals(row.get("workload_subject"))) {
      return new GrantRedemption("DENY", "GRANT_SUBJECT_MISMATCH", grantId, null);
    }
    if (!argumentFingerprint.equals(row.get("argument_fingerprint"))) {
      return new GrantRedemption("DENY", "GRANT_ARGUMENT_MISMATCH", grantId, null);
    }
    return new GrantRedemption("DENY", "GRANT_UNAVAILABLE", grantId, null);
  }

  private Capability capability(UUID projectId, String toolName) {
    return jdbc.queryForObject("select id,impact_level,requires_approval from runtime_ai_tool_capabilities where project_id=? and tool_name=? and active=true",
        (rs, index) -> new Capability(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3),
            sha256(toolName + "|" + rs.getString(2) + "|" + rs.getBoolean(3))),
        projectId, toolName);
  }

  private static ObjectNode policyContext(String toolName, Capability capability, String argumentFingerprint, UUID approvalRequestId) {
    ObjectNode context = JsonNodeFactory.instance.objectNode();
    context.put("toolName", toolName);
    context.put("impactLevel", capability.impactLevel());
    context.put("requiresApproval", capability.requiresApproval());
    context.put("argumentFingerprint", argumentFingerprint);
    context.put("approvalLinked", approvalRequestId != null);
    return context;
  }

  private static Duration boundedTtl(Duration requested) {
    Duration ttl = requested == null ? DEFAULT_TTL : requested;
    if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("A tool grant lifetime must be between " + MIN_TTL + " and " + MAX_TTL);
    }
    return ttl;
  }

  /**
   * Renders a JSON value in a stable form so the same arguments always produce the same fingerprint. Object members
   * are ordered by key; array order is preserved because it is part of the value.
   */
  static String canonical(JsonNode node) {
    if (node == null || node.isNull()) return "null";
    if (node.isObject()) {
      Map<String, JsonNode> ordered = new TreeMap<>();
      node.fieldNames().forEachRemaining(name -> ordered.put(name, node.get(name)));
      StringBuilder builder = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, JsonNode> field : ordered.entrySet()) {
        if (!first) builder.append(',');
        builder.append(TextNode.valueOf(field.getKey())).append(':').append(canonical(field.getValue()));
        first = false;
      }
      return builder.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder builder = new StringBuilder("[");
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) builder.append(',');
        builder.append(canonical(node.get(index)));
      }
      return builder.append(']').toString();
    }
    return node.toString();
  }

  private static String quotedOrNull(UUID value) { return value == null ? "null" : "\"" + value + "\""; }

  private static void bounded(String value, int max) {
    if (value == null || value.isBlank() || value.trim().length() > max) throw new IllegalArgumentException("Required bounded value missing");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Digest unavailable", exception);
    }
  }
}
