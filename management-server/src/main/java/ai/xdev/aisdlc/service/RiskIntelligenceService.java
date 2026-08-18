package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import ai.xdev.aisdlc.domain.RiskScore;
import ai.xdev.aisdlc.repo.Repositories.RiskScoreRepository;
import ai.xdev.aisdlc.web.PageResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a bounded, explainable governance risk score. Every component is derived from persisted
 * project data and is retained with its source counts. It is not a predictive model or an automated
 * delivery decision.
 */
@Service
public class RiskIntelligenceService {
  public static final String FORMULA_VERSION = "risk.v1";
  private final ProjectAccessService access;
  private final AuditService audit;
  private final RiskScoreRepository scores;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final ObjectMapper json;

  public RiskIntelligenceService(ProjectAccessService access, AuditService audit, RiskScoreRepository scores, org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper json) { this.access = access; this.audit = audit; this.scores = scores; this.jdbc = jdbc; this.json = json; }

  @Transactional
  public RiskScoreView recompute(UUID projectId, String actor) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER);
    Instant now = Instant.now();
    Instant thirtyDays = now.minus(30, ChronoUnit.DAYS);
    Instant ninetyDays = now.minus(90, ChronoUnit.DAYS);

    long criticalFindings = count("select count(*) from findings f join validation_runs r on r.id = f.validation_run_id where r.project_id = ? and r.completed_at >= ? and f.severity = 'CRITICAL'", projectId, ninetyDays);
    long highFindings = count("select count(*) from findings f join validation_runs r on r.id = f.validation_run_id where r.project_id = ? and r.completed_at >= ? and f.severity = 'HIGH'", projectId, ninetyDays);
    long failedPolicy = count("select count(*) from policy_evaluations where project_id = ? and evaluated_at >= ? and evaluation_mode = 'ENFORCEMENT' and outcome in ('FAIL','ERROR')", projectId, thirtyDays);
    long expiredExceptions = count("select count(*) from security_exception_notices where project_id = ? and exception_status = 'EXPIRED'", projectId);
    long expiringExceptions = count("select count(*) from security_exception_notices where project_id = ? and exception_status = 'ACTIVE' and expires_at <= ?", projectId, now.plus(14, ChronoUnit.DAYS));
    long recentRuns = count("select count(*) from validation_runs where project_id = ? and completed_at >= ?", projectId, thirtyDays);
    long runsWithoutEvidence = count("select count(*) from validation_runs r where r.project_id = ? and r.completed_at >= ? and not exists (select 1 from validation_evidences e where e.validation_run_id = r.id)", projectId, thirtyDays);
    long pendingReviews = count("select count(*) from review_items where project_id = ? and status = 'PENDING'", projectId);
    long overdueApprovals = count("select count(*) from approval_requests where project_id = ? and approval_status in ('PENDING','ESCALATED') and due_at <= ?", projectId, now);
    long agentSessions = count("select count(*) from agent_sessions where project_id = ? and declared_at >= ?", projectId, thirtyDays);
    long unverifiedProvenance = count("select count(*) from provenance_records where project_id = ? and verification_status = 'DECLARED'", projectId);

    Map<String, Object> latestQuality = latestQuality(projectId);
    int findingRisk = cap(criticalFindings * 12 + highFindings * 3, 25);
    int policyRisk = cap(failedPolicy * 4, 20);
    int exceptionRisk = cap(expiredExceptions * 5 + expiringExceptions * 2, 15);
    int evidenceRisk = recentRuns == 0 ? 0 : cap(Math.round((float) runsWithoutEvidence * 10 / recentRuns), 10);
    int workflowRisk = cap(pendingReviews * 2 + overdueApprovals * 3, 10);
    int qualityRisk = qualityRisk(latestQuality);
    int provenanceRisk = cap(unverifiedProvenance * 2, 10);
    int score = findingRisk + policyRisk + exceptionRisk + evidenceRisk + workflowRisk + qualityRisk + provenanceRisk;
    String band = band(score);

    Map<String, Object> components = new LinkedHashMap<>();
    components.put("findingRisk", findingRisk); components.put("policyRisk", policyRisk); components.put("exceptionRisk", exceptionRisk);
    components.put("evidenceRisk", evidenceRisk); components.put("workflowRisk", workflowRisk); components.put("qualityRisk", qualityRisk); components.put("provenanceRisk", provenanceRisk);
    Map<String, Object> sources = new LinkedHashMap<>();
    sources.put("criticalFindings90d", criticalFindings); sources.put("highFindings90d", highFindings); sources.put("failedPolicyEvaluations30d", failedPolicy);
    sources.put("expiredSecurityExceptions", expiredExceptions); sources.put("expiringSecurityExceptions14d", expiringExceptions); sources.put("validationRuns30d", recentRuns);
    sources.put("validationRunsWithoutEvidence30d", runsWithoutEvidence); sources.put("pendingReviews", pendingReviews); sources.put("overdueApprovals", overdueApprovals);
    sources.put("agentSessions30d", agentSessions); sources.put("unverifiedProvenance", unverifiedProvenance); sources.put("latestQuality", latestQuality);
    RiskScore risk = scores.save(new RiskScore(projectId, score, band, FORMULA_VERSION, write(components), write(sources), actor));
    audit.append(project.getOrganizationId(), projectId, actor, "risk_score.computed", "risk_score", risk.getId().toString(), "{\"formulaVersion\":\"" + FORMULA_VERSION + "\",\"score\":" + score + "}");
    return view(risk);
  }

  public RiskScoreView latest(UUID projectId, String actor) { access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); return scores.findTopByProjectIdOrderByCalculatedAtDesc(projectId).map(this::view).orElse(null); }
  public PageResponse<RiskScoreView> trend(UUID projectId, String actor, int page, int size) { access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); var result = scores.findByProjectIdOrderByCalculatedAtDesc(projectId, PageRequest.of(page, size)); return PageResponse.of(result.getContent().stream().map(this::view).toList(), result.getNumber(), result.getSize(), result.getTotalElements()); }

  private long count(String sql, Object... arguments) { Long value = jdbc.queryForObject(sql, Long.class, bindable(arguments)); return value == null ? 0 : value; }

  /**
   * Converts {@link Instant} arguments to {@link Timestamp} before they reach the driver.
   *
   * <p>The PostgreSQL driver cannot infer a SQL type for a positionally bound {@code java.time.Instant} and throws
   * {@code Can't infer the SQL type to use for an instance of java.time.Instant}. Every one of the eleven counting
   * queries here passes a 30- or 90-day cutoff, so the whole recompute failed on its first query and the endpoint
   * had never returned a score. Converting inside this one helper fixes all of them and stops the next query from
   * reintroducing it.
   */
  private static Object[] bindable(Object... arguments) {
    Object[] converted = arguments.clone();
    for (int index = 0; index < converted.length; index++) {
      if (converted[index] instanceof Instant instant) converted[index] = Timestamp.from(instant);
    }
    return converted;
  }
  /**
   * This row is embedded in the risk snapshot as {@code sourceSummary.latestQuality}, so its column names were the
   * last snake_case keys reaching a client anywhere in the API — hidden inside a {@code Map} field of an otherwise
   * typed record, and invisible whenever no quality period had been recorded yet.
   *
   * <p>The aliases and the {@code quality.get(...)} reads below have to move together: nothing would fail to compile
   * if they diverged, and the score would quietly compute from zeros instead.
   */
  private Map<String, Object> latestQuality(UUID projectId) { var rows = jdbc.queryForList("select deployment_frequency as \"deploymentFrequency\", lead_time_hours as \"leadTimeHours\", change_failure_rate as \"changeFailureRate\", pr_review_time_delta_hours as \"prReviewTimeDeltaHours\", rework_rate as \"reworkRate\", review_queue_health as \"reviewQueueHealth\", spec_alignment_score as \"specAlignmentScore\", security_debt_score as \"securityDebtScore\", model_use_distribution as \"modelUseDistribution\" from quality_metric_snapshots where project_id = ? order by period_end desc limit 1", projectId); return rows.isEmpty() ? Map.of() : rows.getFirst(); }
  private int qualityRisk(Map<String, Object> quality) {
    if (quality.isEmpty()) return 0;
    double failure = decimal(quality.get("changeFailureRate")); double rework = decimal(quality.get("reworkRate"));
    double alignmentGap = 1 - decimal(quality.get("specAlignmentScore")); double queueGap = 1 - decimal(quality.get("reviewQueueHealth"));
    double lead = Math.min(decimal(quality.get("leadTimeHours")) / 168d, 1d); double review = Math.min(decimal(quality.get("prReviewTimeDeltaHours")) / 168d, 1d);
    double debt = decimal(quality.get("securityDebtScore"));
    return cap(Math.round((float) (failure * 7 + rework * 3 + alignmentGap * 4 + queueGap * 3 + lead * 2 + review * 2 + debt * 4)), 10);
  }
  private double decimal(Object value) { return value instanceof BigDecimal d ? d.doubleValue() : value instanceof Number n ? n.doubleValue() : 0d; }
  private int cap(long value, int maximum) { return (int) Math.max(0, Math.min(value, maximum)); }
  private String band(int score) { return score >= 75 ? "CRITICAL" : score >= 50 ? "HIGH" : score >= 25 ? "MODERATE" : "LOW"; }
  private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("Could not serialize risk evidence", exception); } }
  private RiskScoreView view(RiskScore score) { return new RiskScoreView(score.getId(), score.getProjectId(), score.getScore(), score.getRiskBand(), score.getFormulaVersion(), read(score.getComponents()), read(score.getSourceSummary()), score.getCalculatedBy(), score.getCalculatedAt()); }
  private Map<String, Object> read(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception exception) { throw new IllegalStateException("Stored risk evidence is malformed", exception); } }

  public record RiskScoreView(UUID id, UUID projectId, int score, String riskBand, String formulaVersion, Map<String, Object> components, Map<String, Object> sourceSummary, String calculatedBy, Instant calculatedAt) {}
}
