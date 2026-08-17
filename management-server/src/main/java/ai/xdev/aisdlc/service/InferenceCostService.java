package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provider-neutral, minor-unit cost ledger. Forecasts are advisory only and never route a model. */
@Service
public class InferenceCostService {
  public record UsageView(UUID id, String sourceEventKey, String provider, String modelName, Instant occurredAt, long inputTokens, long outputTokens, String currencyCode, long sourceCostMinor) {}
  /**
   * @param status {@code ADVISORY}, {@code LOW_CONFIDENCE}, or {@code INSUFFICIENT_DATA}
   * @param explanation why the status is what it is; an {@code INSUFFICIENT_DATA} result names the shortfall rather
   *     than returning a number derived from too little history
   * @param backtestWape null when every back-tested period cost nothing, because a percentage error with a zero
   *     denominator is undefined rather than perfect
   */
  public record ForecastView(UUID id, LocalDate start, int horizonDays, String currencyCode, Long predictedCostMinor,
                             Long lowerBoundMinor, Long upperBoundMinor, int sampleDays, String status,
                             String explanation, Double backtestWape, Double backtestIntervalCoverage) {}
  private final JdbcTemplate jdbc; private final ProjectAccessService access; private final AuditService audit; private final BudgetEnforcementService budgets;
  public InferenceCostService(JdbcTemplate jdbc, ProjectAccessService access, AuditService audit, BudgetEnforcementService budgets) { this.jdbc = jdbc; this.access = access; this.audit = audit; this.budgets = budgets; }
  @Transactional
  public UsageView ingest(UUID projectId, String actor, String sourceEventKey, String provider, String model, String version, Instant occurredAt, long input, long output, String currency, long costMinor, String sourceClaimSha256) {
    var project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    require(sourceEventKey, 240); require(provider, 160); require(model, 240); if (input < 0 || output < 0 || costMinor < 0) throw new IllegalArgumentException("Usage and cost must be non-negative");
    String c = requireCurrency(currency); requireDigest(sourceClaimSha256); UUID id = UUID.randomUUID();
    int inserted = jdbc.update("insert into inference_usage_events(id,project_id,source_event_key,provider,model_name,model_version,occurred_at,input_tokens,output_tokens,currency_code,source_cost_minor,source_claim_sha256,recorded_by) values(?,?,?,?,?,?,?, ?,?,?, ?,?,?) on conflict(project_id,source_event_key) do nothing", id, projectId, sourceEventKey.trim(), provider.trim(), model.trim(), blank(version,240), occurredAt == null ? Instant.now() : occurredAt, input, output, c, costMinor, sourceClaimSha256.toLowerCase(Locale.ROOT), actor);
    if (inserted == 0) return existing(projectId, sourceEventKey);
    String evidence = sha256(projectId + "|" + sourceEventKey + "|" + c + "|" + costMinor);
    jdbc.update("insert into inference_cost_allocations(id,usage_event_id,project_id,allocation_key,currency_code,allocated_cost_minor,allocation_method,allocation_evidence_sha256) values(?,?,?,?,?,?,?,?)", UUID.randomUUID(), id, projectId, "project:" + projectId, c, costMinor, "SOURCE_COST_EXACT", evidence);
    audit.append(project.getOrganizationId(), projectId, actor, "inference_usage.ingested", "inference_usage_event", id.toString(), "{\"sourceEventKey\":\"" + json(sourceEventKey) + "\",\"costMinor\":" + costMinor + "}");
    budgets.evaluateAfterUsage(projectId, id, c, actor);
    return new UsageView(id, sourceEventKey.trim(), provider.trim(), model.trim(), occurredAt == null ? Instant.now() : occurredAt, input, output, c, costMinor);
  }
  /**
   * Produces an advisory forecast and the back-test evidence that says whether its intervals have held.
   *
   * <p>The arithmetic lives in {@link SeasonalCostForecaster} as a pure function so that every rule — the
   * day-of-week baseline, the refusal thresholds, the rolling-origin back-test — is asserted without a database.
   * This method only fetches history, persists the result, and never acts on it: a forecast cannot route a model,
   * change a budget, or block a request.
   */
  @Transactional
  public ForecastView forecast(UUID projectId, String actor, String currency, int horizonDays) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    String c = requireCurrency(currency); int horizon = Math.max(1, Math.min(90, horizonDays));
    LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
    // Eight complete weeks plus the four back-test origins that precede them, so a fold never trains on a window
    // that has been truncated by the query rather than by the calendar.
    List<SeasonalCostForecaster.DailyCost> history = jdbc.query(
        "select occurred_at::date as day, coalesce(sum(source_cost_minor),0) as total from inference_usage_events"
            + " where project_id=? and currency_code=? and occurred_at >= (?::date - interval '84 days')"
            + " group by occurred_at::date order by occurred_at::date",
        (rs, row) -> new SeasonalCostForecaster.DailyCost(rs.getObject("day", LocalDate.class), rs.getLong("total")),
        projectId, c, asOf);

    SeasonalCostForecaster.Forecast result = SeasonalCostForecaster.forecast(history, asOf, horizon);
    var backtest = result.backtest();
    String evidence = sha256(projectId + "|" + c + "|" + horizon + "|" + SeasonalCostForecaster.METHODOLOGY + "|" + history);
    UUID id = UUID.randomUUID(); LocalDate start = asOf.plusDays(1);
    jdbc.update("insert into inference_cost_forecasts(id,project_id,forecast_start,horizon_days,currency_code,predicted_cost_minor,lower_bound_minor,upper_bound_minor,sample_days,methodology,status,evidence_sha256,generated_by,observed_days,backtest_folds,backtest_wape,backtest_median_abs_error_minor,backtest_interval_coverage,explanation) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id, projectId, start, horizon, c, result.predictedMinor(), result.lowerMinor(), result.upperMinor(),
        result.observedDays(), SeasonalCostForecaster.METHODOLOGY, result.status(), evidence, actor,
        result.observedDays(), backtest == null ? null : backtest.folds(),
        backtest == null ? null : backtest.weightedAbsolutePercentageError(),
        backtest == null ? null : backtest.medianAbsoluteErrorMinor(),
        backtest == null ? null : backtest.intervalCoverage(), result.explanation());
    return new ForecastView(id, start, horizon, c, result.predictedMinor(), result.lowerMinor(), result.upperMinor(),
        result.observedDays(), result.status(), result.explanation(),
        backtest == null ? null : backtest.weightedAbsolutePercentageError(),
        backtest == null ? null : backtest.intervalCoverage());
  }
  private UsageView existing(UUID projectId, String key) { return jdbc.queryForObject("select id,source_event_key,provider,model_name,occurred_at,input_tokens,output_tokens,currency_code,source_cost_minor from inference_usage_events where project_id=? and source_event_key=?", (rs,n)->new UsageView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getLong(6),rs.getLong(7),rs.getString(8),rs.getLong(9)), projectId,key.trim()); }
  private static String require(String v,int max){if(v==null||v.isBlank()||v.trim().length()>max)throw new IllegalArgumentException("Required bounded value missing");return v.trim();} private static String blank(String v,int max){return v==null||v.isBlank()?null:require(v,max);} private static String requireCurrency(String v){if(v==null||!v.matches("[A-Za-z]{3}"))throw new IllegalArgumentException("ISO currency code required");return v.toUpperCase(Locale.ROOT);} private static void requireDigest(String v){if(v==null||!v.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("SHA-256 required");} private static String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}} private static String json(String v){return v.replace("\\","\\\\").replace("\"","\\\"");}
}
