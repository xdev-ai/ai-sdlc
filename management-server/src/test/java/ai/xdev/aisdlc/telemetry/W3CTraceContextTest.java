package ai.xdev.aisdlc.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class W3CTraceContextTest {
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
  private static final String SAMPLED_TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

  @Test
  void continuesAnAcceptableInboundContextWithoutReplacingTheTrace() {
    W3CTraceContext context = W3CTraceContext.continueFrom(SAMPLED_TRACEPARENT, null).orElseThrow();
    assertEquals(TRACE_ID, context.traceId());
    assertEquals(PARENT_SPAN_ID, context.parentSpanId());
    assertNotEquals(PARENT_SPAN_ID, context.spanId());
    assertTrue(context.spanId().matches("[0-9a-f]{16}"));
    assertTrue(context.sampled());
    assertTrue(context.remoteParent());
  }

  @Test
  void inheritsAnUnsampledUpstreamDecision() {
    W3CTraceContext context = W3CTraceContext.continueFrom("00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-00", null).orElseThrow();
    assertFalse(context.sampled());
  }

  @Test
  void rejectsMalformedForbiddenAndAllZeroIdentifiers() {
    List<String> rejected = List.of(
        "",
        "00-" + TRACE_ID + "-" + PARENT_SPAN_ID,
        "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01-extra",
        "ff-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01",
        "00-" + "0".repeat(32) + "-" + PARENT_SPAN_ID + "-01",
        "00-" + TRACE_ID + "-" + "0".repeat(16) + "-01",
        "00-" + TRACE_ID.substring(1) + "-" + PARENT_SPAN_ID + "-01",
        "00-XYZ92f3577b34da6a3ce929d0e0e4736-" + PARENT_SPAN_ID + "-01");
    rejected.forEach(header -> assertEquals(Optional.empty(), W3CTraceContext.continueFrom(header, null), header));
    assertEquals(Optional.empty(), W3CTraceContext.continueFrom(null, null));
  }

  @Test
  void acceptsAFutureVersionByParsingOnlyTheFirstFourFields() {
    W3CTraceContext context = W3CTraceContext.continueFrom("01-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01-future", null).orElseThrow();
    assertEquals(TRACE_ID, context.traceId());
    assertTrue(context.sampled());
  }

  @Test
  void forwardsValidTraceStateAndDiscardsAnUnparsableList() {
    assertEquals("congo=t61rcWkgMzE,rojo=00f067aa0ba902b7",
        W3CTraceContext.continueFrom(SAMPLED_TRACEPARENT, "congo=t61rcWkgMzE,rojo=00f067aa0ba902b7").orElseThrow().traceState());
    assertEquals("", W3CTraceContext.continueFrom(SAMPLED_TRACEPARENT, "not a valid member").orElseThrow().traceState());
    String oversized = String.join(",", java.util.Collections.nCopies(33, "vendor=value"));
    assertEquals("", W3CTraceContext.continueFrom(SAMPLED_TRACEPARENT, oversized).orElseThrow().traceState());
  }

  @Test
  void createsANewRootOnlyWhenNoAcceptableContextExists() {
    W3CTraceContext root = W3CTraceContext.newRoot(1.0d);
    assertTrue(root.traceId().matches("[0-9a-f]{32}"));
    assertEquals("", root.parentSpanId());
    assertEquals("", root.traceState());
    assertFalse(root.remoteParent());
    assertNotEquals(root.traceId(), W3CTraceContext.newRoot(1.0d).traceId());
  }

  @Test
  void ratioSamplerIsDeterministicInTheTraceIdentifier() {
    assertTrue(W3CTraceContext.isSampled(TRACE_ID, 1.0d));
    assertFalse(W3CTraceContext.isSampled(TRACE_ID, 0.0d));
    boolean first = W3CTraceContext.isSampled(TRACE_ID, 0.5d);
    assertEquals(first, W3CTraceContext.isSampled(TRACE_ID, 0.5d));
    assertTrue(W3CTraceContext.newRoot(1.0d).sampled());
    assertFalse(W3CTraceContext.newRoot(0.0d).sampled());
  }

  @Test
  void ratioSamplerKeepsRoughlyTheRequestedShareOfTraces() {
    int sampled = 0;
    for (int index = 0; index < 4_000; index++) {
      if (W3CTraceContext.newRoot(0.25d).sampled()) sampled++;
    }
    assertTrue(sampled > 700 && sampled < 1300, "sampled=" + sampled);
  }

  @Test
  void rendersVersionZeroHeadersForOutboundPropagation() {
    W3CTraceContext context = W3CTraceContext.continueFrom(SAMPLED_TRACEPARENT, "congo=t61rcWkgMzE").orElseThrow();
    assertEquals("00-" + TRACE_ID + "-" + context.spanId() + "-01", context.traceparent());
    assertEquals(Map.of("traceparent", context.traceparent(), "tracestate", "congo=t61rcWkgMzE"), context.outboundHeaders());
    assertEquals(Map.of("traceparent", W3CTraceContext.newRoot(0.0d).traceparent()).keySet(),
        W3CTraceContext.newRoot(0.0d).outboundHeaders().keySet());
  }

  @Test
  void outboundHeadersCarryNoAttributeBeyondTheTraceContext() {
    W3CTraceContext context = W3CTraceContext.continueFrom(SAMPLED_TRACEPARENT, null).orElseThrow();
    context.outboundHeaders().keySet().forEach(header ->
        assertTrue(List.of("traceparent", "tracestate").contains(header), header));
  }
}
