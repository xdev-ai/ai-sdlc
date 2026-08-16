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
  private final java.util.Map<String, ai.xdev.aisdlc.scm.ScmConnector> connectors;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  public ScmIntegrationController(ScmIntegrationService scm, GitHubWebhookSignatureVerifier signatures) {
    this(scm, signatures, java.util.List.of(), new com.fasterxml.jackson.databind.ObjectMapper());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScmIntegrationController(ScmIntegrationService scm, GitHubWebhookSignatureVerifier signatures, java.util.List<ai.xdev.aisdlc.scm.ScmConnector> connectorBeans, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.scm = scm; this.signatures = signatures; this.objectMapper = objectMapper;
    java.util.Map<String, ai.xdev.aisdlc.scm.ScmConnector> byKey = new LinkedHashMap<>();
    for (var connector : connectorBeans) byKey.put(connector.provider().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), connector);
    this.connectors = java.util.Map.copyOf(byKey);
  }

  /**
   * Provider-neutral webhook ingress. Verification happens before the body is parsed, and an unconfigured or unknown
   * connector is refused rather than treated as trusted: a webhook nobody can authenticate must not reach the ledger.
   */
  @PostMapping("/webhooks/scm/{connectorKey}")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Map<String, Object> connectorWebhook(@PathVariable String connectorKey, @RequestHeader Map<String, String> rawHeaders, @RequestBody byte[] payload) {
    var connector = connectors.get(connectorKey == null ? "" : connectorKey.toLowerCase(java.util.Locale.ROOT));
    if (connector == null) throw new IllegalArgumentException("Unknown SCM connector");
    if (!connector.isConfigured()) throw new SecurityException("Connector is not configured");
    Map<String, String> headers = new LinkedHashMap<>();
    rawHeaders.forEach((name, value) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), value));
    if (!connector.verify(payload, headers)) throw new SecurityException("Invalid webhook signature");
    com.fasterxml.jackson.databind.JsonNode parsed;
    try {
      parsed = objectMapper.readTree(payload);
    } catch (Exception malformed) {
      throw new IllegalArgumentException("Webhook payload is not valid JSON");
    }
    ScmIntegrationService.WebhookIngestResult result = scm.ingestConnectorEvent(connector, parsed, headers, payload);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("eventId", result.eventId());
    response.put("duplicate", result.duplicate());
    response.put("accepted", result.accepted());
    response.put("disposition", result.disposition());
    response.put("contract", ai.xdev.aisdlc.scm.ScmConnector.CONTRACT_VERSION);
    return response;
  }

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
