package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.service.GitHubWebhookSignatureVerifier;
import ai.xdev.aisdlc.service.ScmIntegrationService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ScmIntegrationControllerTest {
  @Test void acceptsVerifiedWebhookAndReturnsIdempotencyDisposition() {
    ScmIntegrationService service = mock(ScmIntegrationService.class);
    GitHubWebhookSignatureVerifier verifier = mock(GitHubWebhookSignatureVerifier.class);
    ScmIntegrationController controller = new ScmIntegrationController(service, verifier);
    UUID eventId = UUID.randomUUID();
    byte[] payload = "{}".getBytes();
    when(verifier.isValid(payload, "sha256=valid")).thenReturn(true);
    when(service.ingestGitHub("delivery-1", "pull_request", payload)).thenReturn(new ScmIntegrationService.WebhookIngestResult(eventId, false, true, "processed"));

    Map<String, Object> response = controller.githubWebhook("sha256=valid", "delivery-1", "pull_request", payload);

    assertEquals(eventId, response.get("eventId"));
    assertEquals(true, response.get("accepted"));
    assertEquals("processed", response.get("disposition"));
    verify(service).ingestGitHub("delivery-1", "pull_request", payload);
  }

  @Test void rejectsWebhookBeforeServiceIngestionWhenSignatureIsInvalid() {
    ScmIntegrationService service = mock(ScmIntegrationService.class);
    GitHubWebhookSignatureVerifier verifier = mock(GitHubWebhookSignatureVerifier.class);
    ScmIntegrationController controller = new ScmIntegrationController(service, verifier);
    byte[] payload = "{}".getBytes();
    when(verifier.isValid(payload, "sha256=bad")).thenReturn(false);

    assertThrows(SecurityException.class, () -> controller.githubWebhook("sha256=bad", "delivery-1", "push", payload));
    verifyNoInteractions(service);
  }

  @Test void linksValidationRunWithAuthenticatedSubject() {
    ScmIntegrationService service = mock(ScmIntegrationService.class);
    ScmIntegrationController controller = new ScmIntegrationController(service, mock(GitHubWebhookSignatureVerifier.class));
    UUID projectId = UUID.randomUUID(); UUID eventId = UUID.randomUUID(); UUID runId = UUID.randomUUID();
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("reviewer").build();

    controller.linkValidationRun(projectId, eventId, new ScmIntegrationController.ValidationLinkInput(runId), jwt);

    verify(service).linkValidationRun(projectId, eventId, runId, "reviewer");
  }
}
