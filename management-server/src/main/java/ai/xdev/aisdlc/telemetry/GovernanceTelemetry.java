package ai.xdev.aisdlc.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Manual instrumentation for the governance operations automatic instrumentation cannot infer.
 *
 * <p>Every recorded signal passes {@link TelemetryAttributeContract}: outcomes come from a bounded vocabulary, spans
 * carry only allowlisted attributes, and the service-level indicator counter is emitted with exactly the published
 * label set. Project, tenant, user, session, and request identifiers never reach a metric.
 *
 * <p>Telemetry is diagnostic and fails open. Without the OpenTelemetry Java agent the API resolves to a no-op
 * implementation, and any failure inside the recording path is swallowed so it cannot turn a healthy governance
 * operation into an error. Governance evidence continues to fail closed through the audit and policy paths.
 */
@Component
public class GovernanceTelemetry {
  /** Instrumentation scope; the version tracks the telemetry contract rather than the build. */
  public static final String SCOPE = "ai.xdev.aisdlc.governance";

  static final String SLI_EVENTS = "aisdlc.sli.events";
  static final String OPERATION_DURATION = "aisdlc.operation.duration";
  static final String AUDIT_INTEGRITY_FAILURES = "aisdlc.audit.integrity_failures";
  static final String EVIDENCE_INTEGRITY_FAILURES = "aisdlc.evidence.integrity_failures";
  static final int MAX_CAUSE_DEPTH = 32;

  private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("aisdlc.operation");
  private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("aisdlc.outcome");
  private static final AttributeKey<String> SERVICE = AttributeKey.stringKey("service");
  private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey("environment");
  private static final AttributeKey<String> JOURNEY = AttributeKey.stringKey("journey");
  private static final AttributeKey<String> SLI_OUTCOME = AttributeKey.stringKey("outcome");

  /**
   * An instance for constructors that predate telemetry, such as the ones the unit tests use. It behaves exactly like
   * the injected bean when no agent is attached, which is also the default production posture.
   */
  public static GovernanceTelemetry inert() {
    return new GovernanceTelemetry(new TelemetryProperties());
  }

  /** A governed operation that reports its own bounded outcome. */
  @FunctionalInterface
  public interface Operation<T> {
    T run() throws Exception;
  }

  private final TelemetryProperties properties;
  private final Tracer tracer;
  private final LongCounter sliEvents;
  private final DoubleHistogram operationDuration;
  private final LongCounter auditIntegrityFailures;
  private final LongCounter evidenceIntegrityFailures;

  public GovernanceTelemetry(TelemetryProperties properties) {
    this.properties = properties;
    this.tracer = GlobalOpenTelemetry.getTracer(SCOPE, TelemetryAttributeContract.CONTRACT_VERSION);
    Meter meter = GlobalOpenTelemetry.meterBuilder(SCOPE).setInstrumentationVersion(TelemetryAttributeContract.CONTRACT_VERSION).build();
    this.sliEvents = meter.counterBuilder(SLI_EVENTS)
        .setDescription("Service-level indicator events for a governance journey")
        .setUnit("{event}")
        .build();
    this.operationDuration = meter.histogramBuilder(OPERATION_DURATION)
        .setDescription("Duration of a governance operation")
        .setUnit("ms")
        .build();
    this.auditIntegrityFailures = meter.counterBuilder(AUDIT_INTEGRITY_FAILURES)
        .setDescription("Audit hash-chain verifications that found a break")
        .setUnit("{failure}")
        .build();
    this.evidenceIntegrityFailures = meter.counterBuilder(EVIDENCE_INTEGRITY_FAILURES)
        .setDescription("Evidence digest verifications that did not match")
        .setUnit("{failure}")
        .build();
  }

  /**
   * Records an audit hash-chain break. This is a zero-tolerance integrity signal, not an error budget: the alert on
   * it pages immediately. Only the service and environment are attached, never an organization or sequence number.
   */
  public void recordAuditIntegrityFailure() {
    increment(auditIntegrityFailures);
  }

  /** Records an evidence digest mismatch under the same zero-tolerance rule. */
  public void recordEvidenceIntegrityFailure() {
    increment(evidenceIntegrityFailures);
  }

  private void increment(LongCounter counter) {
    try {
      counter.add(1, Attributes.of(SERVICE, properties.getServiceName(), ENVIRONMENT, properties.getEnvironment()));
    } catch (RuntimeException telemetryFailure) {
      // Fail open: the governance record of the failure is the audit ledger, not this counter.
    }
  }

  /**
   * Records a governance operation as a span, a duration histogram sample, and one service-level indicator event.
   *
   * <p>A thrown exception is reported as a {@code failed} outcome and rethrown unchanged: instrumentation observes the
   * operation, it never changes its result.
   *
   * @param operation stable operation name, for example {@code aisdlc.policy.evaluate}
   * @param journey the service journey this operation belongs to, used as the SLI dimension
   */
  public <T> T record(String operation, String journey, Operation<T> work) throws Exception {
    long startedAt = System.nanoTime();
    Span span = startSpan(operation);
    String outcome = "failed";
    // The recording runs in a finally block so an Error — not only an Exception — still ends the span and emits its
    // event. An unended span would leak in an agent-attached deployment and lose the failure from the SLI entirely.
    try (Scope ignored = span == null ? null : span.makeCurrent()) {
      T result = work.run();
      outcome = "success";
      return result;
    } catch (Throwable failure) {
      outcome = outcomeFor(failure);
      throw failure;
    } finally {
      complete(span, operation, journey, outcome, startedAt);
    }
  }

  /** {@link #record} for work that throws only unchecked exceptions. */
  public <T> T recordUnchecked(String operation, String journey, java.util.function.Supplier<T> work) {
    try {
      return record(operation, journey, work::get);
    } catch (RuntimeException runtime) {
      throw runtime;
    } catch (Exception checked) {
      throw new IllegalStateException("Unexpected checked exception from an unchecked operation", checked);
    }
  }

  /**
   * Records an operation whose outcome the caller determines, for a path that returns a rejection rather than
   * throwing. The outcome must be part of the bounded vocabulary.
   */
  public void recordOutcome(String operation, String journey, String outcome, long elapsedNanos) {
    try {
      TelemetryAttributeContract.requireOperationOutcome(outcome);
      emit(operation, journey, outcome, elapsedNanos);
    } catch (RuntimeException telemetryFailure) {
      // Fail open: a rejected recording must not propagate into the governed operation.
    }
  }

  private Span startSpan(String operation) {
    try {
      return tracer.spanBuilder(operation).startSpan();
    } catch (RuntimeException telemetryFailure) {
      return null;
    }
  }

  private void complete(Span span, String operation, String journey, String outcome, long startedAt) {
    try {
      if (span != null) {
        span.setAttribute(OPERATION, operation);
        span.setAttribute(OUTCOME, outcome);
        if (!"success".equals(outcome)) span.setStatus(StatusCode.ERROR);
        span.end();
      }
      emit(operation, journey, outcome, System.nanoTime() - startedAt);
    } catch (RuntimeException telemetryFailure) {
      // Fail open.
    }
  }

  private void emit(String operation, String journey, String outcome, long elapsedNanos) {
    TelemetryAttributeContract.requireOperationOutcome(outcome);
    String sliOutcome = "success".equals(outcome) ? "good" : "bad";
    Attributes sli = Attributes.of(
        SERVICE, properties.getServiceName(),
        ENVIRONMENT, properties.getEnvironment(),
        JOURNEY, journey,
        SLI_OUTCOME, sliOutcome);
    // The label keys are a compile-time constant; asserting against the shared set avoids allocating one per call.
    TelemetryAttributeContract.requireSliEventLabels(TelemetryAttributeContract.SLI_EVENT_LABELS, sliOutcome);
    sliEvents.add(1, sli);
    operationDuration.record(
        TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
        Attributes.of(OPERATION, operation, OUTCOME, outcome));
  }

  /**
   * Maps a failure to the bounded outcome vocabulary without exposing the exception message.
   *
   * <p>The walk is depth-bounded rather than cycle-detecting. {@code getCause} can be overridden to return any
   * cycle, not only self-causation, and this runs on the failure path before the fail-open guard — an unbounded walk
   * would hang the governed operation rather than degrade telemetry.
   */
  static String outcomeFor(Throwable failure) {
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      String type = cause.getClass().getSimpleName();
      if (type.contains("Timeout") || cause instanceof java.util.concurrent.TimeoutException) return "timeout";
      if (cause instanceof SecurityException || cause instanceof IllegalArgumentException) return "rejected";
      Throwable next = cause.getCause();
      cause = next == cause ? null : next;
    }
    return "failed";
  }
}
