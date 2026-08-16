package ai.xdev.aisdlc.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The instrumentation contract. Without an OpenTelemetry agent the API is a no-op, so these tests assert the property
 * that matters for the platform: recording observes a governance operation and never changes its result.
 */
class GovernanceTelemetryTest {
  private final GovernanceTelemetry telemetry = GovernanceTelemetry.inert();

  @Test
  void returnsTheOperationResultUnchanged() throws Exception {
    Object value = new Object();
    assertSame(value, telemetry.record("aisdlc.policy.evaluate", "policy-decision-latency", () -> value));
    assertSame(value, telemetry.recordUnchecked("aisdlc.audit.append", "audit-correctness", () -> value));
  }

  @Test
  void runsTheOperationExactlyOnce() throws Exception {
    AtomicInteger invocations = new AtomicInteger();
    telemetry.record("aisdlc.evidence.write", "evidence-durability", invocations::incrementAndGet);
    assertEquals(1, invocations.get());
  }

  @Test
  void rethrowsTheOriginalFailureWithoutWrappingIt() {
    IllegalStateException failure = new IllegalStateException("policy bundle missing");
    var thrown = assertThrows(IllegalStateException.class,
        () -> telemetry.record("aisdlc.policy.evaluate", "policy-decision-latency", () -> { throw failure; }));
    assertSame(failure, thrown);

    SecurityException denied = new SecurityException("membership required");
    assertSame(denied, assertThrows(SecurityException.class,
        () -> telemetry.recordUnchecked("aisdlc.approval.transition", "approval-orchestration", () -> { throw denied; })));
  }

  @Test
  void preservesACheckedExceptionForCallersThatDeclareIt() {
    IOException failure = new IOException("storage unavailable");
    assertSame(failure, assertThrows(IOException.class,
        () -> telemetry.record("aisdlc.evidence.write", "evidence-durability", () -> { throw failure; })));
  }

  @Test
  void mapsFailuresOntoTheBoundedOutcomeVocabulary() {
    assertEquals("timeout", GovernanceTelemetry.outcomeFor(new TimeoutException("deadline")));
    assertEquals("timeout", GovernanceTelemetry.outcomeFor(new java.net.http.HttpTimeoutException("read timeout")));
    assertEquals("rejected", GovernanceTelemetry.outcomeFor(new SecurityException("denied")));
    assertEquals("rejected", GovernanceTelemetry.outcomeFor(new IllegalArgumentException("invalid input")));
    assertEquals("failed", GovernanceTelemetry.outcomeFor(new IllegalStateException("broken")));
    assertEquals("timeout", GovernanceTelemetry.outcomeFor(new IllegalStateException(new TimeoutException("nested"))));
    TelemetryAttributeContract.OPERATION_OUTCOMES.forEach(TelemetryAttributeContract::requireOperationOutcome);
  }

  /** {@code Throwable.initCause} forbids self-causation, but an override can still return a cycle. */
  private static final class SelfCausingException extends RuntimeException {
    SelfCausingException() { super("cyclic"); }
    @Override public synchronized Throwable getCause() { return this; }
  }

  @Test
  void terminatesOnASelfReferencingExceptionChain() {
    assertEquals("failed", GovernanceTelemetry.outcomeFor(new SelfCausingException()));
  }

  @Test
  void walksADeepCauseChainToFindTheBoundedOutcome() {
    Throwable deep = new TimeoutException("provider deadline");
    for (int depth = 0; depth < 20; depth++) deep = new IllegalStateException("layer " + depth, deep);
    assertEquals("timeout", GovernanceTelemetry.outcomeFor(deep));
  }

  @Test
  void failsOpenWhenTheRecordedOutcomeIsOutsideTheContract() {
    assertDoesNotThrow(() -> telemetry.recordOutcome("aisdlc.notification.dispatch", "notification-timeliness", "degraded", 1_000L));
    assertDoesNotThrow(() -> telemetry.recordOutcome("aisdlc.notification.dispatch", "notification-timeliness", "success", 1_000L));
  }

  @Test
  void recordsTheSameJourneyLabelSetTheSloRulesExpect() {
    // The Prometheus rules in infra/observability/p3-slo-burn-rate-rules.yaml read exactly these labels.
    assertEquals(java.util.Set.of("service", "environment", "journey", "outcome"), TelemetryAttributeContract.SLI_EVENT_LABELS);
    assertDoesNotThrow(() -> TelemetryAttributeContract.requireSliEventLabels(TelemetryAttributeContract.SLI_EVENT_LABELS, "good"));
    assertDoesNotThrow(() -> TelemetryAttributeContract.requireSliEventLabels(TelemetryAttributeContract.SLI_EVENT_LABELS, "bad"));
  }

  @Test
  void carriesNoIdentifierInItsInstrumentationScope() {
    assertTrue(GovernanceTelemetry.SCOPE.startsWith("ai.xdev.aisdlc"));
    assertTrue(TelemetryAttributeContract.SPAN_ATTRIBUTE_KEYS.contains("aisdlc.operation"));
    assertTrue(TelemetryAttributeContract.SPAN_ATTRIBUTE_KEYS.contains("aisdlc.outcome"));
    assertTrue(TelemetryAttributeContract.isProhibited("aisdlc.project.id"));
  }
}
