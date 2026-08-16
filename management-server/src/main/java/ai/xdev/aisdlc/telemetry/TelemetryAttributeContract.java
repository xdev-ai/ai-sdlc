package ai.xdev.aisdlc.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Versioned telemetry data-classification contract for P3.1.
 *
 * <p>Resource attributes and metric labels use strict allowlists; span attributes additionally pass a prohibited-token
 * scan as defense in depth. The Collector transform stage repeats these removals, but the application must never emit a
 * prohibited value in the first place. Tenant, project, user, session, request, and trace identifiers are excluded from
 * metric labels to keep metric identity bounded.
 */
public final class TelemetryAttributeContract {
  /** Contract version recorded on every exported resource; a configuration mismatch fails validation. */
  public static final String CONTRACT_VERSION = "telemetry.v1";

  /** The only resource attribute keys permitted on exported signals. */
  public static final Set<String> RESOURCE_ATTRIBUTE_KEYS = Set.of(
      "service.name", "service.namespace", "service.version", "service.instance.id",
      "deployment.environment.name", "aisdlc.telemetry.contract");

  /** Label set of {@code aisdlc_sli_events_total}; see infra/observability/README.md. */
  public static final Set<String> SLI_EVENT_LABELS = Set.of("service", "environment", "journey", "outcome");

  /** Label set of {@code aisdlc_slo_target_ratio}; see infra/observability/README.md. */
  public static final Set<String> SLO_TARGET_LABELS = Set.of("service", "environment", "journey", "window");

  /** The only {@code outcome} values a service-level indicator may report. */
  public static final Set<String> SLI_OUTCOMES = Set.of("good", "bad");

  /** Bounded outcome vocabulary for governance spans; free-form user values are never permitted. */
  public static final Set<String> OPERATION_OUTCOMES = Set.of("success", "rejected", "failed", "timeout");

  /** Span attribute keys permitted without a further review of the telemetry classification policy. */
  public static final Set<String> SPAN_ATTRIBUTE_KEYS = Set.of(
      "http.request.method", "http.route", "http.response.status_code", "server.address",
      "aisdlc.operation", "aisdlc.outcome", "aisdlc.policy.bundle_version", "aisdlc.correlation_id");

  private static final List<String> PROHIBITED_TOKENS = List.of(
      "authorization", "cookie", "token", "secret", "password", "credential", "api_key", "apikey",
      "prompt", "completion", "model.output", "tool.argument", "payload", "body",
      "db.statement", "url.query", "url.full", "query_string",
      "tenant", "project", "user", "session", "subject", "email", "evidence", "digest", "fingerprint");

  private TelemetryAttributeContract() {}

  /** Builds the exported resource from validated configuration only. */
  public static Map<String, String> resourceAttributes(TelemetryProperties properties) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("service.name", properties.getServiceName());
    attributes.put("service.namespace", properties.getServiceNamespace());
    attributes.put("deployment.environment.name", properties.getEnvironment());
    attributes.put("aisdlc.telemetry.contract", properties.getContractVersion());
    if (!properties.getServiceVersion().isBlank()) attributes.put("service.version", properties.getServiceVersion());
    if (!properties.getServiceInstanceId().isBlank()) attributes.put("service.instance.id", properties.getServiceInstanceId());
    return Map.copyOf(attributes);
  }

  /** True when a key carries, or is named after, data the telemetry pipeline must never receive. */
  public static boolean isProhibited(String key) {
    if (key == null || key.isBlank()) return true;
    String normalized = key.toLowerCase(Locale.ROOT);
    return PROHIBITED_TOKENS.stream().anyMatch(normalized::contains);
  }

  /** Drops every span attribute that is not allowlisted or that trips the prohibited-token scan. */
  public static Map<String, String> sanitizeSpanAttributes(Map<String, String> candidate) {
    Map<String, String> safe = new LinkedHashMap<>();
    if (candidate == null) return Map.of();
    candidate.forEach((key, value) -> {
      if (key != null && value != null && SPAN_ATTRIBUTE_KEYS.contains(key) && !isProhibited(key)) safe.put(key, value);
    });
    return Map.copyOf(safe);
  }

  /** Rejects resource attribute keys an operator supplied outside the allowlist. */
  public static Set<String> rejectedResourceKeys(Map<String, String> candidate) {
    Set<String> rejected = new TreeSet<>();
    if (candidate == null) return rejected;
    candidate.keySet().forEach(key -> {
      if (key == null || !RESOURCE_ATTRIBUTE_KEYS.contains(key) || isProhibited(key)) rejected.add(String.valueOf(key));
    });
    return rejected;
  }

  /** Fails closed when an SLI counter is recorded with a label set other than the published contract. */
  public static void requireSliEventLabels(Set<String> labels, String outcome) {
    if (labels == null || !SLI_EVENT_LABELS.equals(labels)) {
      throw new IllegalArgumentException("SLI event labels must be exactly " + new TreeSet<>(SLI_EVENT_LABELS) + " but were " + new TreeSet<>(labels == null ? Set.<String>of() : labels));
    }
    if (!SLI_OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("SLI outcome must be one of " + new TreeSet<>(SLI_OUTCOMES) + " but was " + outcome);
    }
  }

  /** Fails closed when an SLO target gauge is recorded with a label set other than the published contract. */
  public static void requireSloTargetLabels(Set<String> labels) {
    if (labels == null || !SLO_TARGET_LABELS.equals(labels)) {
      throw new IllegalArgumentException("SLO target labels must be exactly " + new TreeSet<>(SLO_TARGET_LABELS) + " but were " + new TreeSet<>(labels == null ? Set.<String>of() : labels));
    }
  }

  /** Fails closed when a governance span reports an outcome outside the bounded vocabulary. */
  public static void requireOperationOutcome(String outcome) {
    if (!OPERATION_OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("Operation outcome must be one of " + new TreeSet<>(OPERATION_OUTCOMES) + " but was " + outcome);
    }
  }
}
