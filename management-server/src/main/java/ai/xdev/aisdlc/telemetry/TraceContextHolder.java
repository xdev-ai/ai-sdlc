package ai.xdev.aisdlc.telemetry;

import java.util.Optional;

/**
 * Request-scoped access to the active {@link W3CTraceContext} for outbound propagation and log correlation.
 *
 * <p>The value is bound by {@link TraceContextFilter} and cleared in the same request, so a pooled worker thread never
 * inherits another request's trace.
 */
public final class TraceContextHolder {
  private static final ThreadLocal<W3CTraceContext> CURRENT = new ThreadLocal<>();

  private TraceContextHolder() {}

  public static void bind(W3CTraceContext context) { CURRENT.set(context); }

  public static void clear() { CURRENT.remove(); }

  public static Optional<W3CTraceContext> current() { return Optional.ofNullable(CURRENT.get()); }
}
