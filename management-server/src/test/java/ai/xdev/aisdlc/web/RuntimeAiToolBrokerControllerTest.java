package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.xdev.aisdlc.service.RuntimeAiToolBrokerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

class RuntimeAiToolBrokerControllerTest {
  private final ObjectMapper json = new ObjectMapper();
  private final RuntimeAiToolBrokerService broker = mock(RuntimeAiToolBrokerService.class);
  private final RuntimeAiToolBrokerController controller = new RuntimeAiToolBrokerController(broker);
  private final UUID projectId = UUID.randomUUID();

  private static Jwt workloadToken(String subject) {
    return Jwt.withTokenValue("token").header("alg", "none").subject(subject)
        .claim("roles", List.of("agent_runtime")).audience(List.of("aisdlc-runtime"))
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
  }

  @Test
  void issuesAgainstTheAuthenticatedWorkloadAndReturnsTheSecretOnce() throws Exception {
    JsonNode arguments = json.readTree("{\"issue\":\"AISDLC-1\"}");
    UUID sessionId = UUID.randomUUID();
    UUID grantId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(30);
    when(broker.issue(eq(projectId), eq("workload-1"), eq(sessionId), eq("issue-tracker-read"), eq(arguments), eq(null), eq(Duration.ofSeconds(30))))
        .thenReturn(new RuntimeAiToolBrokerService.GrantIssue("ALLOW", "POLICY_PASS", grantId, "grant-secret", expiresAt, "a".repeat(64), UUID.randomUUID()));

    var response = controller.issue(projectId,
        new RuntimeAiToolBrokerController.GrantInput(sessionId, "issue-tracker-read", arguments, null, 30), workloadToken("workload-1"));

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals("grant-secret", response.getBody().grantSecret());
    assertEquals(grantId, response.getBody().grantId());
    verify(broker).issue(projectId, "workload-1", sessionId, "issue-tracker-read", arguments, null, Duration.ofSeconds(30));
  }

  @Test
  void returnsForbiddenAndNoSecretWhenTheGrantIsDenied() throws Exception {
    JsonNode arguments = json.readTree("{}");
    when(broker.issue(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new RuntimeAiToolBrokerService.GrantIssue("DENY", "HUMAN_APPROVAL_REQUIRED", null, null, null, "a".repeat(64), UUID.randomUUID()));

    var response = controller.issue(projectId,
        new RuntimeAiToolBrokerController.GrantInput(UUID.randomUUID(), "deploy", arguments, null, null), workloadToken("workload-1"));

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    assertEquals("HUMAN_APPROVAL_REQUIRED", response.getBody().reasonCode());
    assertNull(response.getBody().grantSecret());
  }

  @Test
  void redeemsAgainstTheAuthenticatedWorkloadAndSurfacesReplayAsForbidden() throws Exception {
    JsonNode arguments = json.readTree("{\"issue\":\"AISDLC-1\"}");
    UUID grantId = UUID.randomUUID();
    when(broker.redeem(projectId, "workload-1", "grant-secret", arguments))
        .thenReturn(new RuntimeAiToolBrokerService.GrantRedemption("ALLOW", "GRANT_REDEEMED", grantId, "b".repeat(64)));
    var allowed = controller.redeem(projectId, new RuntimeAiToolBrokerController.RedemptionInput("grant-secret", arguments), workloadToken("workload-1"));
    assertEquals(HttpStatus.OK, allowed.getStatusCode());
    assertEquals("b".repeat(64), allowed.getBody().receiptSha256());

    when(broker.redeem(projectId, "workload-1", "grant-secret", arguments))
        .thenReturn(new RuntimeAiToolBrokerService.GrantRedemption("DENY", "GRANT_ALREADY_REDEEMED", grantId, null));
    var replayed = controller.redeem(projectId, new RuntimeAiToolBrokerController.RedemptionInput("grant-secret", arguments), workloadToken("workload-1"));
    assertEquals(HttpStatus.FORBIDDEN, replayed.getStatusCode());
    assertEquals("GRANT_ALREADY_REDEEMED", replayed.getBody().reasonCode());
    assertNull(replayed.getBody().receiptSha256());
  }
}
