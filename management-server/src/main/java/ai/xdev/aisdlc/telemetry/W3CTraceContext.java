package ai.xdev.aisdlc.telemetry;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * W3C Trace Context propagation state for one server request.
 *
 * <p>An acceptable inbound {@code traceparent} is continued rather than replaced: the trace identifier and sampling
 * decision are inherited and only a new span identifier is generated. A new root is created only when no acceptable
 * context is present. Sampling of a new root is deterministic in the trace identifier, so the same trace never receives
 * two different decisions.
 *
 * @param traceId 32-character lower-case hexadecimal trace identifier
 * @param spanId 16-character lower-case hexadecimal identifier of this server span
 * @param parentSpanId identifier of the accepted remote parent span, or an empty string for a root
 * @param sampled inherited or deterministically computed sampling decision
 * @param traceState validated vendor state forwarded unchanged, or an empty string
 * @param remoteParent whether an acceptable inbound context was continued
 */
public record W3CTraceContext(String traceId, String spanId, String parentSpanId, boolean sampled, String traceState, boolean remoteParent) {
  public static final String TRACEPARENT_HEADER = "traceparent";
  public static final String TRACESTATE_HEADER = "tracestate";

  private static final String INVALID_TRACE_ID = "0".repeat(32);
  private static final String INVALID_SPAN_ID = "0".repeat(16);
  private static final int MAX_TRACESTATE_MEMBERS = 32;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HexFormat HEX = HexFormat.of();

  public W3CTraceContext {
    if (!isTraceId(traceId)) throw new IllegalArgumentException("Invalid trace identifier");
    if (!isSpanId(spanId)) throw new IllegalArgumentException("Invalid span identifier");
    if (parentSpanId == null) throw new IllegalArgumentException("Parent span identifier must not be null");
    if (traceState == null) throw new IllegalArgumentException("Trace state must not be null");
  }

  /**
   * Continues an inbound context when the {@code traceparent} header is acceptable.
   *
   * @return the continued context, or empty when the caller supplied no acceptable context
   */
  public static Optional<W3CTraceContext> continueFrom(String traceparent, String tracestate) {
    if (traceparent == null) return Optional.empty();
    String[] fields = traceparent.trim().split("-", -1);
    if (fields.length < 4) return Optional.empty();
    String version = fields[0].toLowerCase(Locale.ROOT);
    // Version ff is forbidden; a future version keeps the first four fields and its remainder is ignored.
    if (!version.matches("[0-9a-f]{2}") || "ff".equals(version)) return Optional.empty();
    if ("00".equals(version) && fields.length != 4) return Optional.empty();
    String traceId = fields[1].toLowerCase(Locale.ROOT);
    String parentSpanId = fields[2].toLowerCase(Locale.ROOT);
    String flags = fields[3].toLowerCase(Locale.ROOT);
    if (!isTraceId(traceId) || !isSpanId(parentSpanId) || !flags.matches("[0-9a-f]{2}")) return Optional.empty();
    boolean sampled = (HexFormat.fromHexDigits(flags) & 0x01) == 0x01;
    return Optional.of(new W3CTraceContext(traceId, randomSpanId(), parentSpanId, sampled, sanitizeTraceState(tracestate), true));
  }

  /** Creates a root context with a deterministic, ratio-based sampling decision. */
  public static W3CTraceContext newRoot(double sampleRatio) {
    String traceId = randomTraceId();
    return new W3CTraceContext(traceId, randomSpanId(), "", isSampled(traceId, sampleRatio), "", false);
  }

  /**
   * Deterministic ratio sampler over the trailing 64 bits of the trace identifier, so every service that sees the same
   * trace derives the same decision without coordination.
   */
  public static boolean isSampled(String traceId, double sampleRatio) {
    if (!isTraceId(traceId)) throw new IllegalArgumentException("Invalid trace identifier");
    if (sampleRatio <= 0.0d) return false;
    if (sampleRatio >= 1.0d) return true;
    long trailing = Long.parseUnsignedLong(traceId.substring(16), 16);
    double position = (double) (trailing >>> 11) / (double) (1L << 53);
    return position < sampleRatio;
  }

  /** Renders the version-00 {@code traceparent} value this service sends downstream. */
  public String traceparent() {
    return "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
  }

  /** Headers to inject into an outbound call so the downstream service continues this trace. */
  public Map<String, String> outboundHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(TRACEPARENT_HEADER, traceparent());
    if (!traceState.isBlank()) headers.put(TRACESTATE_HEADER, traceState);
    return Map.copyOf(headers);
  }

  private static String sanitizeTraceState(String tracestate) {
    if (tracestate == null || tracestate.isBlank()) return "";
    String[] members = tracestate.split(",", -1);
    if (members.length > MAX_TRACESTATE_MEMBERS) return "";
    for (String member : members) {
      String trimmed = member.trim();
      // An unparsable list is discarded entirely; the traceparent above still continues the trace.
      if (!trimmed.matches("[a-z0-9][a-z0-9_*/@-]{0,255}=[\\x20-\\x2b\\x2d-\\x3c\\x3e-\\x7e]{0,255}")) return "";
    }
    return tracestate.trim();
  }

  private static boolean isTraceId(String value) {
    return value != null && value.matches("[0-9a-f]{32}") && !INVALID_TRACE_ID.equals(value);
  }

  private static boolean isSpanId(String value) {
    return value != null && value.matches("[0-9a-f]{16}") && !INVALID_SPAN_ID.equals(value);
  }

  private static String randomTraceId() {
    byte[] bytes = new byte[16];
    do { RANDOM.nextBytes(bytes); } while (INVALID_TRACE_ID.equals(HEX.formatHex(bytes)));
    return HEX.formatHex(bytes);
  }

  private static String randomSpanId() {
    byte[] bytes = new byte[8];
    do { RANDOM.nextBytes(bytes); } while (INVALID_SPAN_ID.equals(HEX.formatHex(bytes)));
    return HEX.formatHex(bytes);
  }
}
