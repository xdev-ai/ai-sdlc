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

  @Test
  void declaresUsageLinkedBudgetDecisionIdempotency() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V16__budget_usage_linkage.sql"));
    assertTrue(migration.contains("add column usage_event_id uuid references inference_usage_events(id)"));
    assertTrue(migration.contains("inference_budget_decisions_usage_event_unique"));
    assertTrue(migration.contains("where usage_event_id is not null"));
  }

  @Test
  void declaresProviderProxyProfileAndDispatchGuards() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V17__runtime_provider_proxy_execution.sql"));
    assertTrue(migration.contains("add column endpoint_uri varchar(2048)"));
    assertTrue(migration.contains("runtime_ai_provider_profiles_mtls_reference_ck"));
    assertTrue(migration.contains("runtime_ai_provider_dispatches"));
    assertTrue(migration.contains("unique(project_id, idempotency_key)"));
  }

  @Test
  void declaresSingleUseTenantScopedToolGrantGuards() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V18__runtime_ai_tool_grants.sql"));
    assertTrue(migration.contains("tenant_id uuid not null references tenants(id)"));
    assertTrue(migration.contains("runtime_ai_tool_grants_nonce_unique unique (grant_nonce_sha256)"));
    assertTrue(migration.contains("runtime_ai_tool_grants_high_impact_approval_ck"));
    assertTrue(migration.contains("runtime_ai_tool_grants_redeemed_ck"));
    assertTrue(migration.contains("argument_fingerprint varchar(64) not null"));
  }

  /**
   * A Spec Kit version is a document version. Immutability that rests on application code never issuing the wrong
   * UPDATE is weaker than the audit ledger's, which is a database trigger — and the docs describe both as
   * "immutable". UNIQUE (organization_id, slug, version, layer) stops a duplicate version; it does nothing to stop
   * the manifest of an existing one being rewritten while its version number stays the same.
   */
  @Test
  void declaresDatabaseEnforcedSpecKitVersionImmutability() throws Exception {
    String migration = Files.readString(Path.of("src/main/resources/db/migration/V21__spec_kit_version_immutability.sql"));

    assertTrue(migration.contains("spec_kits_no_version_rewrite before update on spec_kits"));
    assertTrue(migration.contains("spec_kits_no_delete before delete on spec_kits"));
    // Identity and content are frozen.
    for (String frozen : new String[] {"slug", "version", "layer", "parent_kit_id", "manifest", "created_at"}) {
      assertTrue(migration.contains("new." + frozen), "the trigger must compare " + frozen);
    }
    // Lifecycle must stay mutable, or a kit could never be pinned or deprecated.
    for (String mutable : new String[] {"pinned", "lifecycle_status", "deprecation_reason"}) {
      assertTrue(!migration.contains("new." + mutable + " "), mutable + " must remain mutable");
    }
    assertTrue(migration.contains("register a new version instead of rewriting"));
  }
}
