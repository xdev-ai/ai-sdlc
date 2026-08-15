package ai.xdev.aisdlc.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Verifies that the immutable audit ledger remains queryable as part of readiness. */
@Component("auditLedger")
public class AuditLedgerHealthIndicator implements HealthIndicator {
  private final JdbcTemplate jdbc;

  public AuditLedgerHealthIndicator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Health health() {
    try {
      Integer count = jdbc.queryForObject("select count(*) from audit_events", Integer.class);
      return Health.up().withDetail("events", count == null ? 0 : count).build();
    } catch (Exception error) {
      return Health.down(error).build();
    }
  }
}
