package ai.xdev.aisdlc.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Verifies that the immutable audit ledger remains queryable as part of readiness. */
@Component("auditLedger")
public class AuditLedgerHealthIndicator implements HealthIndicator {
  private final JdbcTemplate jdbc;

  private ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry = ai.xdev.aisdlc.telemetry.GovernanceTelemetry.inert();
  @org.springframework.beans.factory.annotation.Autowired public void setTelemetry(ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry) { this.telemetry = telemetry; }

  public AuditLedgerHealthIndicator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Health health() {
    long startedAt = System.nanoTime();
    try {
      Integer count = jdbc.queryForObject("select count(*) from audit_events", Integer.class);
      telemetry.recordOutcome("aisdlc.health.audit_ledger", "control-plane-availability", "success", System.nanoTime() - startedAt);
      return Health.up().withDetail("events", count == null ? 0 : count).build();
    } catch (Exception error) {
      telemetry.recordOutcome("aisdlc.health.audit_ledger", "control-plane-availability", "failed", System.nanoTime() - startedAt);
      return Health.down(error).build();
    }
  }
}
