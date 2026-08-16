package ai.xdev.aisdlc.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceContextFilterTest {
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";

  private final List<W3CTraceContext> observed = new ArrayList<>();
  private final List<String> observedMdc = new ArrayList<>();

  private MockFilterChain recordingChain() {
    return new MockFilterChain() {
      @Override
      public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws IOException, ServletException {
        TraceContextHolder.current().ifPresent(observed::add);
        observedMdc.add(MDC.get(TraceContextFilter.TRACE_ID_MDC_KEY) + "/" + MDC.get(TraceContextFilter.SPAN_ID_MDC_KEY));
        super.doFilter(request, response);
      }
    };
  }

  private W3CTraceContext run(TelemetryProperties properties, MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
    new TraceContextFilter(properties).doFilter(request, response, recordingChain());
    return observed.getLast();
  }

  @AfterEach
  void clearThreadState() {
    TraceContextHolder.clear();
    MDC.clear();
  }

  @Test
  void continuesAnAcceptableInboundTraceContext() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    request.addHeader(W3CTraceContext.TRACEPARENT_HEADER, "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");
    request.addHeader(W3CTraceContext.TRACESTATE_HEADER, "congo=t61rcWkgMzE");
    W3CTraceContext context = run(new TelemetryProperties(), request, new MockHttpServletResponse());
    assertEquals(TRACE_ID, context.traceId());
    assertEquals(PARENT_SPAN_ID, context.parentSpanId());
    assertEquals("congo=t61rcWkgMzE", context.traceState());
    assertTrue(context.remoteParent());
    assertEquals(context, request.getAttribute(TraceContextFilter.REQUEST_ATTRIBUTE));
  }

  @Test
  void createsANewRootWhenTheInboundContextIsAbsentOrUnusable() throws Exception {
    MockHttpServletRequest absent = new MockHttpServletRequest("GET", "/api/v1/projects");
    assertFalse(run(new TelemetryProperties(), absent, new MockHttpServletResponse()).remoteParent());

    MockHttpServletRequest malformed = new MockHttpServletRequest("GET", "/api/v1/projects");
    malformed.addHeader(W3CTraceContext.TRACEPARENT_HEADER, "00-" + "0".repeat(32) + "-" + PARENT_SPAN_ID + "-01");
    W3CTraceContext root = run(new TelemetryProperties(), malformed, new MockHttpServletResponse());
    assertFalse(root.remoteParent());
    assertNotEquals("0".repeat(32), root.traceId());
  }

  @Test
  void ignoresAnInboundContextWhenRemoteContinuationIsDisabled() throws Exception {
    TelemetryProperties properties = new TelemetryProperties();
    properties.setAcceptRemoteTraceContext(false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    request.addHeader(W3CTraceContext.TRACEPARENT_HEADER, "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");
    W3CTraceContext context = run(properties, request, new MockHttpServletResponse());
    assertNotEquals(TRACE_ID, context.traceId());
    assertFalse(context.remoteParent());
  }

  @Test
  void bindsTraceIdentifiersToLoggingContextForTheRequestOnly() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    W3CTraceContext context = run(new TelemetryProperties(), request, new MockHttpServletResponse());
    assertEquals(context.traceId() + "/" + context.spanId(), observedMdc.getLast());
    assertNull(MDC.get(TraceContextFilter.TRACE_ID_MDC_KEY));
    assertNull(MDC.get(TraceContextFilter.SPAN_ID_MDC_KEY));
    assertTrue(TraceContextHolder.current().isEmpty());
  }

  @Test
  void clearsThreadStateEvenWhenTheDownstreamChainFails() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    MockFilterChain failing = new MockFilterChain() {
      @Override
      public void doFilter(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) {
        throw new IllegalStateException("downstream failure");
      }
    };
    assertThrows(IllegalStateException.class, () -> new TraceContextFilter(new TelemetryProperties())
        .doFilter(request, new MockHttpServletResponse(), failing));
    assertTrue(TraceContextHolder.current().isEmpty());
    assertNull(MDC.get(TraceContextFilter.TRACE_ID_MDC_KEY));
  }

  @Test
  void doesNotReturnTraceIdentifiersToTheCaller() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    request.addHeader(W3CTraceContext.TRACEPARENT_HEADER, "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");
    MockHttpServletResponse response = new MockHttpServletResponse();
    W3CTraceContext context = run(new TelemetryProperties(), request, response);
    assertNotNull(context);
    assertTrue(response.getHeaderNames().isEmpty(), response.getHeaderNames().toString());
  }

  @Test
  void carriesNoRequestDataBeyondTheTraceIdentifiers() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    request.setQueryString("token=secret");
    request.addHeader("Authorization", "Bearer secret-token");
    W3CTraceContext context = run(new TelemetryProperties(), request, new MockHttpServletResponse());
    assertFalse(context.traceparent().contains("secret"));
    assertEquals("", context.traceState());
  }
}
