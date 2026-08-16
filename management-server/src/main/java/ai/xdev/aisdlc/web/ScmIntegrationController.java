package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.service.GitHubWebhookSignatureVerifier;
import ai.xdev.aisdlc.service.ScmIntegrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ScmIntegrationController {
  record RepositoryLinkInput(@NotNull ScmProvider provider, @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") String repositoryFullName, @Positive Long installationId, @Size(max = 255) String defaultBranch, boolean policyGateEnabled) {}
  record ValidationLinkInput(@NotNull UUID validationRunId) {}
  record Created(UUID id) {}
  private final ScmIntegrationService scm;
  private final GitHubWebhookSignatureVerifier signatures;
  public ScmIntegrationController(ScmIntegrationService scm, GitHubWebhookSignatureVerifier signatures) { this.scm = scm; this.signatures = signatures; }

  @PostMapping("/webhooks/github")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> githubWebhook(@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature, @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId, @RequestHeader(value = "X-GitHub-Event", required = false) String eventName, @RequestBody byte[] payload) {
    if (!signatures.isValid(payload, signature)) throw new SecurityException("Invalid GitHub webhook signature");
    ScmIntegrationService.WebhookIngestResult result = scm.ingestGitHub(deliveryId, eventName, payload);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("eventId", result.eventId());
    response.put("duplicate", result.duplicate());
    response.put("accepted", result.accepted());
    response.put("disposition", result.disposition());
    return response;
  }

  @PostMapping("/projects/{projectId}/scm-repositories")
  @ResponseStatus(HttpStatus.CREATED)
  Created linkRepository(@PathVariable UUID projectId, @RequestBody @Valid RepositoryLinkInput input, @AuthenticationPrincipal Jwt jwt) {
    return new Created(scm.linkRepository(projectId, jwt.getSubject(), input.provider(), input.repositoryFullName(), input.installationId(), input.defaultBranch(), input.policyGateEnabled()));
  }

  @GetMapping("/projects/{projectId}/scm-repositories")
  List<Map<String, Object>> repositories(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return scm.listRepositoryLinks(projectId, jwt.getSubject()); }

  @GetMapping("/projects/{projectId}/scm-events")
  PageResponse<Map<String, Object>> events(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) {
    return scm.listEvents(projectId, jwt.getSubject(), page, size);
  }

  @PostMapping("/projects/{projectId}/scm-events/{eventId}/validation-run")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void linkValidationRun(@PathVariable UUID projectId, @PathVariable UUID eventId, @RequestBody @Valid ValidationLinkInput input, @AuthenticationPrincipal Jwt jwt) {
    scm.linkValidationRun(projectId, eventId, input.validationRunId(), jwt.getSubject());
  }
}
