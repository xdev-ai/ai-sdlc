package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.web.PageResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cel.common.CelValidationException;
import dev.cel.runtime.CelEvaluationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded CEL evaluation for governance policy. The declared environment contains only `context`
 * and has no application-supplied functions; policy evaluation is therefore side-effect free.
 */
@Service
public class PolicyEvaluationService {
  public record PolicyBundleView(UUID id, UUID projectId, String bundleKey, String semanticVersion, String description, String expression, String sourceSha256, JsonNode fixtures, boolean dryRunDefault, PolicyBundleLifecycle lifecycleStatus, String compilationError, Instant checkedAt, String createdBy, Instant createdAt) {}
  public record EvaluationView(UUID id, UUID policyBundleId, UUID projectId, String contextSha256, PolicyEvaluationMode mode, PolicyEvaluationOutcome outcome, Boolean result, String errorCode, JsonNode detail, String evaluatedBy, Instant evaluatedAt) {}
  public record FixtureResult(String name, boolean expected, PolicyEvaluationOutcome outcome, Boolean result, String errorCode, boolean passed) {}
  public record TestRunView(UUID policyBundleId, List<FixtureResult> results, boolean passed) {}

  private static final int MAX_EXPRESSION_CHARS = 12_000;
  private static final int MAX_CONTEXT_CHARS = 65_536;
  private static final int MAX_CONTEXT_NODES = 2_000;
  private static final int MAX_CONTEXT_DEPTH = 12;
  private static final int MAX_FIXTURES = 100;

  private final JdbcTemplate jdbc;
  private ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry = ai.xdev.aisdlc.telemetry.GovernanceTelemetry.inert();
  @org.springframework.beans.factory.annotation.Autowired public void setTelemetry(ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry) { this.telemetry = telemetry; }
  private final ProjectAccessService access;
  private final AuditService audit;
  private final ObjectMapper mapper;
  private final PolicyExpressionEngine engine;

  public PolicyEvaluationService(JdbcTemplate jdbc, ProjectAccessService access, AuditService audit, ObjectMapper mapper, PolicyExpressionEngine engine) {
    this.jdbc = jdbc; this.access = access; this.audit = audit; this.mapper = mapper; this.engine = engine;
  }

  @Transactional
  public PolicyBundleView create(UUID projectId, String actor, String bundleKey, String semanticVersion, String description, String expression, JsonNode fixtures, boolean dryRunDefault) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    validateBundleInput(bundleKey, semanticVersion, description, expression, fixtures);
    validateCompilation(expression);
    UUID id = UUID.randomUUID();
    String sourceHash = sha256(expression);
    String fixtureJson = canonical(fixtures == null ? mapper.createArrayNode() : fixtures);
    jdbc.update("insert into policy_bundles(id, project_id, bundle_key, semantic_version, description, cel_expression, source_sha256, fixture_json, dry_run_default, lifecycle_status, checked_at, created_by) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, 'DRAFT', now(), ?)", id, projectId, bundleKey.trim(), semanticVersion.trim(), blankToNull(description, 2000), expression.trim(), sourceHash, fixtureJson, dryRunDefault, actor);
    audit.append(project.getOrganizationId(), projectId, actor, "policy_bundle.created", "policy_bundle", id.toString(), "{\"key\":\"" + json(bundleKey) + "\",\"version\":\"" + json(semanticVersion) + "\",\"sourceSha256\":\"" + sourceHash + "\"}");
    return require(projectId, id);
  }

  @Transactional
  public PolicyBundleView activate(UUID projectId, UUID bundleId, String actor) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    PolicyBundleView bundle = require(projectId, bundleId);
    if (bundle.lifecycleStatus() != PolicyBundleLifecycle.DRAFT) throw new IllegalStateException("Only a DRAFT policy bundle can be activated");
    TestRunView tests = runFixtures(projectId, bundleId, actor, false);
    if (!tests.passed()) throw new IllegalStateException("Policy bundle fixtures must pass before activation");
    try {
      jdbc.update("update policy_bundles set lifecycle_status = 'ACTIVE', activated_at = now(), activated_by = ? where id = ? and project_id = ? and lifecycle_status = 'DRAFT'", actor, bundleId, projectId);
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw new IllegalStateException("Another active version exists for policy key " + bundle.bundleKey(), ex);
    }
    audit.append(project.getOrganizationId(), projectId, actor, "policy_bundle.activated", "policy_bundle", bundleId.toString(), "{\"key\":\"" + json(bundle.bundleKey()) + "\",\"version\":\"" + json(bundle.semanticVersion()) + "\"}");
    return require(projectId, bundleId);
  }

  @Transactional
  public PolicyBundleView retire(UUID projectId, UUID bundleId, String actor) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    int changed = jdbc.update("update policy_bundles set lifecycle_status = 'RETIRED', retired_at = now(), retired_by = ? where id = ? and project_id = ? and lifecycle_status <> 'RETIRED'", actor, bundleId, projectId);
    if (changed != 1) throw new IllegalStateException("Policy bundle is unavailable or already retired");
    audit.append(project.getOrganizationId(), projectId, actor, "policy_bundle.retired", "policy_bundle", bundleId.toString(), "{}");
    return require(projectId, bundleId);
  }

  @Transactional
  public EvaluationView evaluate(UUID projectId, UUID bundleId, String actor, JsonNode context, boolean dryRun) {
    return telemetry.recordUnchecked("aisdlc.policy.evaluate", "policy-decision-latency", () -> evaluateBundle(projectId, bundleId, actor, context, dryRun));
  }

  private EvaluationView evaluateBundle(UUID projectId, UUID bundleId, String actor, JsonNode context, boolean dryRun) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    PolicyBundleView bundle = require(projectId, bundleId);
    if (!dryRun && bundle.lifecycleStatus() != PolicyBundleLifecycle.ACTIVE) throw new IllegalStateException("Enforcement evaluation requires an ACTIVE policy bundle");
    EvaluationView value = evaluateInternal(bundle, projectId, actor, context, dryRun ? PolicyEvaluationMode.DRY_RUN : PolicyEvaluationMode.ENFORCEMENT, true);
    audit.append(access.requireProject(projectId).getOrganizationId(), projectId, actor, "policy_bundle.evaluated", "policy_evaluation", value.id().toString(), "{\"bundleId\":\"" + bundleId + "\",\"mode\":\"" + value.mode() + "\",\"outcome\":\"" + value.outcome() + "\",\"contextSha256\":\"" + value.contextSha256() + "\"}");
    return value;
  }

  /** Runtime-only evaluation path. The caller has authenticated a workload identity scoped to this project. */
  @Transactional
  public EvaluationView evaluateForRuntimeWorkload(UUID projectId, UUID bundleId, String workloadSubject, JsonNode context, boolean dryRun) {
    if (workloadSubject == null || workloadSubject.isBlank() || workloadSubject.length() > 240) throw new IllegalArgumentException("Invalid workload identity");
    Boolean registered = jdbc.queryForObject("select exists(select 1 from runtime_ai_workload_identities where project_id=? and workload_subject=? and active=true)", Boolean.class, projectId, workloadSubject.trim());
    if (!Boolean.TRUE.equals(registered)) throw new IllegalArgumentException("Unregistered runtime workload");
    PolicyBundleView bundle = require(projectId, bundleId);
    if (!dryRun && bundle.lifecycleStatus() != PolicyBundleLifecycle.ACTIVE) throw new IllegalStateException("Enforcement evaluation requires an ACTIVE policy bundle");
    EvaluationView value = evaluateInternal(bundle, projectId, workloadSubject.trim(), context, dryRun ? PolicyEvaluationMode.DRY_RUN : PolicyEvaluationMode.ENFORCEMENT, true);
    audit.append(access.requireProject(projectId).getOrganizationId(), projectId, workloadSubject.trim(), "policy_bundle.runtime_evaluated", "policy_evaluation", value.id().toString(), "{\"bundleId\":\"" + bundleId + "\",\"mode\":\"" + value.mode() + "\",\"outcome\":\"" + value.outcome() + "\",\"contextSha256\":\"" + value.contextSha256() + "\"}");
    return value;
  }

  @Transactional
  public TestRunView runFixtures(UUID projectId, UUID bundleId, String actor, boolean requireReadAccess) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    PolicyBundleView bundle = require(projectId, bundleId);
    List<Fixture> fixtures = parseFixtures(bundle.fixtures());
    if (fixtures.isEmpty()) throw new IllegalStateException("At least one policy fixture is required for this operation");
    List<FixtureResult> results = new ArrayList<>();
    for (Fixture fixture : fixtures) {
      EvaluationView evaluation = evaluateInternal(bundle, projectId, actor, fixture.context(), PolicyEvaluationMode.FIXTURE, true);
      boolean passed = evaluation.outcome() != PolicyEvaluationOutcome.ERROR && Boolean.valueOf(fixture.expected()).equals(evaluation.result());
      results.add(new FixtureResult(fixture.name(), fixture.expected(), evaluation.outcome(), evaluation.result(), evaluation.errorCode(), passed));
    }
    boolean passed = results.stream().allMatch(FixtureResult::passed);
    audit.append(access.requireProject(projectId).getOrganizationId(), projectId, actor, "policy_bundle.fixtures.executed", "policy_bundle", bundleId.toString(), "{\"passed\":" + passed + ",\"fixtureCount\":" + results.size() + "}");
    return new TestRunView(bundleId, List.copyOf(results), passed);
  }

  public PageResponse<PolicyBundleView> list(UUID projectId, String actor, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    int boundedPage = Math.max(0, page); int boundedSize = Math.min(100, Math.max(1, size));
    Long total = jdbc.queryForObject("select count(*) from policy_bundles where project_id = ?", Long.class, projectId);
    List<PolicyBundleView> values = jdbc.query("select * from policy_bundles where project_id = ? order by created_at desc limit ? offset ?", (rs, row) -> bundle(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("bundle_key"), rs.getString("semantic_version"), rs.getString("description"), rs.getString("cel_expression"), rs.getString("source_sha256"), rs.getString("fixture_json"), rs.getBoolean("dry_run_default"), PolicyBundleLifecycle.valueOf(rs.getString("lifecycle_status")), rs.getString("compilation_error"), rs.getTimestamp("checked_at") == null ? null : rs.getTimestamp("checked_at").toInstant(), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()), projectId, boundedSize, (long) boundedPage * boundedSize);
    return PageResponse.of(values, boundedPage, boundedSize, total == null ? 0 : total);
  }

  public PageResponse<EvaluationView> evaluations(UUID projectId, UUID bundleId, String actor, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    require(projectId, bundleId); int boundedPage = Math.max(0, page); int boundedSize = Math.min(100, Math.max(1, size));
    Long total = jdbc.queryForObject("select count(*) from policy_evaluations where project_id = ? and policy_bundle_id = ?", Long.class, projectId, bundleId);
    List<EvaluationView> values = jdbc.query("select * from policy_evaluations where project_id = ? and policy_bundle_id = ? order by evaluated_at desc limit ? offset ?", (rs, row) -> evaluation(rs.getObject("id", UUID.class), rs.getObject("policy_bundle_id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("context_sha256"), PolicyEvaluationMode.valueOf(rs.getString("evaluation_mode")), PolicyEvaluationOutcome.valueOf(rs.getString("outcome")), rs.getObject("result", Boolean.class), rs.getString("error_code"), rs.getString("detail"), rs.getString("evaluated_by"), rs.getTimestamp("evaluated_at").toInstant()), projectId, bundleId, boundedSize, (long) boundedPage * boundedSize);
    return PageResponse.of(values, boundedPage, boundedSize, total == null ? 0 : total);
  }

  private EvaluationView evaluateInternal(PolicyBundleView bundle, UUID projectId, String actor, JsonNode context, PolicyEvaluationMode mode, boolean persist) {
    validateContext(context);
    String digest = sha256(canonical(context));
    PolicyEvaluationOutcome outcome; Boolean result = null; String error = null;
    try {
      Object raw = engine.evaluate(bundle.expression(), mapper.convertValue(context, new TypeReference<Map<String, Object>>() {}));
      if (!(raw instanceof Boolean bool)) { outcome = PolicyEvaluationOutcome.ERROR; error = "NON_BOOLEAN_RESULT"; }
      else { result = bool; outcome = bool ? PolicyEvaluationOutcome.PASS : PolicyEvaluationOutcome.FAIL; }
    } catch (CelValidationException ex) { outcome = PolicyEvaluationOutcome.ERROR; error = "COMPILATION_ERROR"; }
      catch (CelEvaluationException ex) { outcome = PolicyEvaluationOutcome.ERROR; error = "EVALUATION_ERROR"; }
      catch (RuntimeException ex) { outcome = PolicyEvaluationOutcome.ERROR; error = "CONTEXT_ERROR"; }
    UUID id = UUID.randomUUID();
    String detail = "{\"bundleSourceSha256\":\"" + bundle.sourceSha256() + "\"}";
    if (persist) jdbc.update("insert into policy_evaluations(id, policy_bundle_id, project_id, context_sha256, evaluation_mode, outcome, result, error_code, detail, evaluated_by) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)", id, bundle.id(), projectId, digest, mode.name(), outcome.name(), result, error, detail, actor);
    return new EvaluationView(id, bundle.id(), projectId, digest, mode, outcome, result, error, parseJson(detail), actor, Instant.now());
  }

  private PolicyBundleView require(UUID projectId, UUID id) {
    List<PolicyBundleView> values = jdbc.query("select * from policy_bundles where id = ? and project_id = ?", (rs, row) -> bundle(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("bundle_key"), rs.getString("semantic_version"), rs.getString("description"), rs.getString("cel_expression"), rs.getString("source_sha256"), rs.getString("fixture_json"), rs.getBoolean("dry_run_default"), PolicyBundleLifecycle.valueOf(rs.getString("lifecycle_status")), rs.getString("compilation_error"), rs.getTimestamp("checked_at") == null ? null : rs.getTimestamp("checked_at").toInstant(), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()), id, projectId);
    if (values.isEmpty()) throw new IllegalArgumentException("Policy bundle not found"); return values.getFirst();
  }
  private PolicyBundleView bundle(UUID id, UUID projectId, String key, String version, String description, String expression, String sourceHash, String fixtures, boolean dryRun, PolicyBundleLifecycle lifecycle, String compilationError, Instant checkedAt, String createdBy, Instant createdAt) { return new PolicyBundleView(id, projectId, key, version, description, expression, sourceHash, parseJson(fixtures), dryRun, lifecycle, compilationError, checkedAt, createdBy, createdAt); }
  private EvaluationView evaluation(UUID id, UUID bundleId, UUID projectId, String digest, PolicyEvaluationMode mode, PolicyEvaluationOutcome outcome, Boolean result, String error, String detail, String actor, Instant evaluatedAt) { return new EvaluationView(id, bundleId, projectId, digest, mode, outcome, result, error, parseJson(detail), actor, evaluatedAt); }
  private void validateCompilation(String expression) { try { engine.validate(expression); } catch (CelValidationException ex) { throw new IllegalArgumentException("CEL policy expression does not type-check: " + ex.getMessage(), ex); } }
  private void validateBundleInput(String key, String version, String description, String expression, JsonNode fixtures) { if (key == null || !key.matches("[a-z0-9._-]{3,160}")) throw new IllegalArgumentException("Bundle key must match [a-z0-9._-]{3,160}"); if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+([-.+][0-9A-Za-z.-]+)?")) throw new IllegalArgumentException("Semantic version is required"); if (description != null && description.length() > 2000) throw new IllegalArgumentException("Description exceeds 2000 characters"); if (expression == null || expression.isBlank() || expression.length() > MAX_EXPRESSION_CHARS) throw new IllegalArgumentException("Expression must be between 1 and 12000 characters"); parseFixtures(fixtures == null ? mapper.createArrayNode() : fixtures); }
  private List<Fixture> parseFixtures(JsonNode node) { if (!node.isArray()) throw new IllegalArgumentException("Policy fixtures must be a JSON array"); if (node.size() > MAX_FIXTURES) throw new IllegalArgumentException("Policy bundle has too many fixtures"); List<Fixture> values = new ArrayList<>(); Set<String> names = new HashSet<>(); for (JsonNode item : node) { String name = item.path("name").asText(); if (name.isBlank() || name.length() > 160 || !names.add(name)) throw new IllegalArgumentException("Every fixture needs a unique bounded name"); if (!item.path("expected").isBoolean()) throw new IllegalArgumentException("Fixture expected must be Boolean"); JsonNode context = item.get("context"); validateContext(context); values.add(new Fixture(name, item.path("expected").booleanValue(), context)); } return values; }
  private void validateContext(JsonNode context) { if (context == null || !context.isObject()) throw new IllegalArgumentException("Policy context must be a JSON object"); String encoded = canonical(context); if (encoded.length() > MAX_CONTEXT_CHARS || nodeCount(context, 0) > MAX_CONTEXT_NODES) throw new IllegalArgumentException("Policy context exceeds bounded complexity"); }
  private int nodeCount(JsonNode node, int depth) { if (depth > MAX_CONTEXT_DEPTH) throw new IllegalArgumentException("Policy context exceeds maximum depth"); int count = 1; if (node.isContainerNode()) for (JsonNode child : node) count += nodeCount(child, depth + 1); return count; }
  private JsonNode parseJson(String value) { try { return mapper.readTree(value); } catch (Exception ex) { throw new IllegalStateException("Persisted policy JSON is invalid", ex); } }
  private String canonical(JsonNode value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException("Unable to serialize policy JSON", ex); } }
  private String sha256(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString(); } catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); } }
  private String blankToNull(String value, int max) { if (value == null || value.isBlank()) return null; String trimmed = value.trim(); if (trimmed.length() > max) throw new IllegalArgumentException("Value exceeds " + max + " characters"); return trimmed; }
  private String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
  private record Fixture(String name, boolean expected, JsonNode context) {}
}
