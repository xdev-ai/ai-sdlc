package ai.xdev.aisdlc.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Privacy and cardinality contract tests for the P3.1 telemetry data-classification policy. */
class TelemetryAttributeContractTest {
  @Test
  void resourceAttributesContainOnlyAllowlistedOperationalKeys() {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setEnvironment("production");
    properties.setServiceVersion("0.1.0");
    properties.setServiceInstanceId("management-server-7");
    Map<String, String> resource = TelemetryAttributeContract.resourceAttributes(properties);
    assertTrue(TelemetryAttributeContract.RESOURCE_ATTRIBUTE_KEYS.containsAll(resource.keySet()), resource.toString());
    assertEquals("production", resource.get("deployment.environment.name"));
    assertEquals(TelemetryAttributeContract.CONTRACT_VERSION, resource.get("aisdlc.telemetry.contract"));
    assertEquals(Set.of(), TelemetryAttributeContract.rejectedResourceKeys(resource));
  }

  @Test
  void resourceAttributesOmitUnsetOptionalKeysRatherThanExportingEmptyValues() {
    Map<String, String> resource = TelemetryAttributeContract.resourceAttributes(new TelemetryProperties());
    assertFalse(resource.containsKey("service.version"));
    assertFalse(resource.containsKey("service.instance.id"));
  }

  @Test
  void prohibitedTokensCoverCredentialsPayloadsAndIdentifiers() {
    List<String> prohibited = List.of(
        "http.request.header.authorization", "http.request.header.cookie", "session.id", "user.email",
        "db.statement", "url.query", "url.full", "gen_ai.prompt", "gen_ai.completion", "tool.arguments",
        "tenant.id", "project.id", "evidence.digest", "request.body", "provider.api_key", "workload.secret");
    prohibited.forEach(key -> assertTrue(TelemetryAttributeContract.isProhibited(key), key));
    List<String> allowed = List.of("http.route", "http.request.method", "aisdlc.operation", "aisdlc.outcome");
    allowed.forEach(key -> assertFalse(TelemetryAttributeContract.isProhibited(key), key));
  }

  @Test
  void spanSanitizerDropsUnknownAndProhibitedAttributes() {
    Map<String, String> candidate = new LinkedHashMap<>();
    candidate.put("http.route", "/api/v1/projects/{projectId}");
    candidate.put("aisdlc.operation", "aisdlc.policy.evaluate");
    candidate.put("aisdlc.outcome", "success");
    candidate.put("db.statement", "select * from audit_events");
    candidate.put("tenant.id", "acme");
    candidate.put("gen_ai.prompt", "internal prompt text");
    candidate.put("unreviewed.attribute", "value");
    Map<String, String> safe = TelemetryAttributeContract.sanitizeSpanAttributes(candidate);
    assertEquals(Set.of("http.route", "aisdlc.operation", "aisdlc.outcome"), safe.keySet());
  }

  @Test
  void sliCounterAcceptsOnlyTheDocumentedLabelSet() {
    TelemetryAttributeContract.requireSliEventLabels(Set.of("service", "environment", "journey", "outcome"), "good");
    TelemetryAttributeContract.requireSliEventLabels(Set.of("service", "environment", "journey", "outcome"), "bad");
    assertThrows(IllegalArgumentException.class, () -> TelemetryAttributeContract.requireSliEventLabels(
        Set.of("service", "environment", "journey", "outcome", "project"), "good"));
    assertThrows(IllegalArgumentException.class, () -> TelemetryAttributeContract.requireSliEventLabels(
        Set.of("service", "environment", "journey"), "good"));
    assertThrows(IllegalArgumentException.class, () -> TelemetryAttributeContract.requireSliEventLabels(
        Set.of("service", "environment", "journey", "outcome"), "degraded"));
  }

  @Test
  void sloTargetGaugeAcceptsOnlyTheDocumentedLabelSet() {
    TelemetryAttributeContract.requireSloTargetLabels(Set.of("service", "environment", "journey", "window"));
    assertThrows(IllegalArgumentException.class, () -> TelemetryAttributeContract.requireSloTargetLabels(
        Set.of("service", "environment", "journey", "window", "tenant")));
  }

  @Test
  void metricLabelContractsExcludeEveryUnboundedIdentifier() {
    Set<String> unbounded = Set.of("tenant", "project", "user", "session", "request_id", "trace_id", "correlation_id");
    unbounded.forEach(label -> {
      assertFalse(TelemetryAttributeContract.SLI_EVENT_LABELS.contains(label), label);
      assertFalse(TelemetryAttributeContract.SLO_TARGET_LABELS.contains(label), label);
    });
  }

  @Test
  void governanceSpanOutcomeStaysWithinTheBoundedVocabulary() {
    TelemetryAttributeContract.OPERATION_OUTCOMES.forEach(TelemetryAttributeContract::requireOperationOutcome);
    assertThrows(IllegalArgumentException.class, () -> TelemetryAttributeContract.requireOperationOutcome("project-42-failed"));
  }
}
