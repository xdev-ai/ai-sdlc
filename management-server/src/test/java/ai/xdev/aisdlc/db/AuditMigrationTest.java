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
}

