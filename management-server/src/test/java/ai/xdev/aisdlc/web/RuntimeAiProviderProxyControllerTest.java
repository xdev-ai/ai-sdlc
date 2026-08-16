package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.xdev.aisdlc.service.RuntimeAiProviderProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

class RuntimeAiProviderProxyControllerTest {
  private static final String FINGERPRINT = "c".repeat(64);

  private final ObjectMapper mapper = new ObjectMapper();
  private final RuntimeAiProviderProxyService proxy = mock(RuntimeAiProviderProxyService.class);
  private final RuntimeAiProviderProxyController controller = new RuntimeAiProviderProxyController(proxy);

  private RuntimeAiProviderProxyController.InvocationInput input() throws Exception {
    JsonNode context = mapper.readTree("{\"providerAllowed\":true}");
    JsonNode payload = mapper.readTree("{\"input\":\"opaque\"}");
    return new RuntimeAiProviderProxyController.InvocationInput(UUID.randomUUID(), "provider-a", "model-a", FINGERPRINT, context, payload);
  }

  private static Jwt workloadToken(String subject) {
    return Jwt.withTokenValue("token").header("alg", "none").subject(subject)
        .claim("roles", java.util.List.of("agent_runtime")).audience(java.util.List.of("aisdlc-runtime"))
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
  }

  @Test
  void bindsTheDispatchToTheAuthenticatedWorkloadSubjectAndHeaderIdempotencyKey() throws Exception {
    UUID project = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    var input = input();
    when(proxy.invoke(eq(project), eq("workload-1"), any())).thenReturn(new RuntimeAiProviderProxyService.InvocationResult(
        "COMPLETE", "PROVIDER_RESPONSE_ACCEPTED", 200, 1, "a".repeat(64), "b".repeat(64), "{\"output\":\"value\"}", UUID.randomUUID()));

    var response = controller.invoke(project, idempotencyKey, input, workloadToken("workload-1"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("{\"output\":\"value\"}", response.getBody().providerResponse());
    var dispatched = org.mockito.ArgumentCaptor.forClass(RuntimeAiProviderProxyService.InvocationRequest.class);
    verify(proxy).invoke(eq(project), eq("workload-1"), dispatched.capture());
    assertEquals(idempotencyKey, dispatched.getValue().idempotencyKey());
    assertEquals(input.agentSessionId(), dispatched.getValue().agentSessionId());
  }

  @Test
  void returnsForbiddenAndNoProviderBodyWhenGovernanceBlocksTheRequest() throws Exception {
    UUID project = UUID.randomUUID();
    when(proxy.invoke(any(), any(), any())).thenReturn(new RuntimeAiProviderProxyService.InvocationResult(
        "BLOCKED", "MODEL_OR_PROVIDER_NOT_ALLOWLISTED", null, 0, "a".repeat(64), null, null, UUID.randomUUID()));

    var response = controller.invoke(project, UUID.randomUUID(), input(), workloadToken("workload-1"));

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    assertEquals("MODEL_OR_PROVIDER_NOT_ALLOWLISTED", response.getBody().reasonCode());
    assertNull(response.getBody().providerResponse());
  }

  @Test
  void returnsBadGatewayAndNoProviderBodyWhenTheDispatchFails() throws Exception {
    when(proxy.invoke(any(), any(), any())).thenReturn(new RuntimeAiProviderProxyService.InvocationResult(
        "FAILED", "PROVIDER_TIMEOUT", null, 3, "a".repeat(64), null, "leaked provider body", UUID.randomUUID()));

    var response = controller.invoke(UUID.randomUUID(), UUID.randomUUID(), input(), workloadToken("workload-1"));

    assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    assertEquals("PROVIDER_TIMEOUT", response.getBody().reasonCode());
    assertNull(response.getBody().providerResponse());
  }
}
