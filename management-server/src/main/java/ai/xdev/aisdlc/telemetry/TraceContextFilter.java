package ai.xdev.aisdlc.telemetry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds W3C trace context to every request and exposes it to logs and outbound propagation.
 *
 * <p>The filter never replaces an acceptable inbound context; it creates a new root only when the caller supplied none
 * or supplied an unparsable one. Trace identifiers are not written to the response: the existing
 * {@code X-Correlation-Id} stays the client-facing handle, and the trace-to-audit pivot is authorized through the
 * control plane rather than by returning trace identifiers to arbitrary callers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceContextFilter extends OncePerRequestFilter {
  public static final String REQUEST_ATTRIBUTE = "aisdlc.traceContext";
  public static final String TRACE_ID_MDC_KEY = "traceId";
  public static final String SPAN_ID_MDC_KEY = "spanId";

  private final TelemetryProperties properties;

  public TraceContextFilter(TelemetryProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    // Telemetry is diagnostic and fails open: a failure to establish trace context degrades observability for this
    // request and must never turn a valid request into an error. Governance evidence fails closed elsewhere.
    try {
      W3CTraceContext context = resolve(request);
      TraceContextHolder.bind(context);
      request.setAttribute(REQUEST_ATTRIBUTE, context);
      MDC.put(TRACE_ID_MDC_KEY, context.traceId());
      MDC.put(SPAN_ID_MDC_KEY, context.spanId());
    } catch (RuntimeException telemetryFailure) {
      TraceContextHolder.clear();
      MDC.remove(TRACE_ID_MDC_KEY);
      MDC.remove(SPAN_ID_MDC_KEY);
    }
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TRACE_ID_MDC_KEY);
      MDC.remove(SPAN_ID_MDC_KEY);
      TraceContextHolder.clear();
    }
  }

  private W3CTraceContext resolve(HttpServletRequest request) {
    if (!properties.isAcceptRemoteTraceContext()) return W3CTraceContext.newRoot(properties.getTraceSampleRatio());
    Optional<W3CTraceContext> continued = W3CTraceContext.continueFrom(
        request.getHeader(W3CTraceContext.TRACEPARENT_HEADER), request.getHeader(W3CTraceContext.TRACESTATE_HEADER));
    return continued.orElseGet(() -> W3CTraceContext.newRoot(properties.getTraceSampleRatio()));
  }
}
