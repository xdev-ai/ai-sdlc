package ai.xdev.aisdlc.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuditMigrationTest {
  @Test
  void declaresDatabaseEnforcedAppendOnlyAuditProtection() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__governance_control_plane.sql"));
    assertTrue(migration.contains("audit_events_no_update BEFORE UPDATE"));
    assertTrue(migration.contains("audit_events_no_delete BEFORE DELETE"));
    assertTrue(migration.contains("audit_events is append-only"));
  }

  @Test
  void declaresProductionLifecycleIntegrityAndQueryIndexes() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V2__production_governance_hardening.sql"));
    assertTrue(migration.contains("lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'"));
    assertTrue(migration.contains("quality_metric_period_order_ck"));
    assertTrue(migration.contains("project_kit_unique_assignment_uq"));
    assertTrue(migration.contains("audit_events_org_action_idx"));
  }

  @Test
  void declaresValidationTriageAndEvidenceRetentionControls() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V3__validation_lifecycle_controls.sql"));
    assertTrue(migration.contains("findings_triage_status_ck"));
    assertTrue(migration.contains("evidence_retention_after_created_ck"));
    assertTrue(migration.contains("evidence_retention_cleanup_idx"));
  }
}
