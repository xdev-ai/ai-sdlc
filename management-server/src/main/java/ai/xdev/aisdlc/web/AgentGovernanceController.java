package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.AgentPolicyDecision;
import ai.xdev.aisdlc.service.AgentGovernanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-governance")
public class AgentGovernanceController {
  record Created(UUID id) {}
  record PromptTemplateInput(@NotBlank @Pattern(regexp = "^[a-z0-9._-]{3,160}$") String templateKey, @NotBlank @Pattern(regexp = "^[0-9]+\\.[0-9]+\\.[0-9]+([-.+][0-9A-Za-z.-]+)?$") String semanticVersion, @NotBlank @Size(max = 240) String displayName, @Size(max = 2000) String sourceReference, @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String templateSha256, @Size(max = 80) String classification) {}
  record SessionInput(UUID promptTemplateId, @NotBlank @Size(max = 240) String agentIdentity, @NotBlank @Size(max = 160) String provider, @NotBlank @Size(max = 240) String modelName, @NotBlank @Size(max = 240) String modelVersion, @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sessionFingerprint, @Pattern(regexp = "^$|^[a-fA-F0-9]{64}$") String contextSha256, @Min(0) @Max(100000) int toolInvocationCount, @Pattern(regexp = "^$|^[a-fA-F0-9]{64}$") String toolInvocationSha256, @Size(max = 2000) String purpose) {}
  record GeneratedChangeInput(UUID validationRunId, UUID evidenceAssetId, @NotBlank @Size(max = 2000) String changeReference, @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String generatedChangeSha256, @NotNull AgentPolicyDecision policyDecision, @Size(max = 2000) String policyReference, @NotBlank @Size(max = 240) String approvalTitle, @Size(max = 2000) String approvalDetails, @Min(1) @Max(50) int requiredQuorum, @Size(max = 200) String requestedApprover, @NotNull Instant approvalDueAt) {}
  private final AgentGovernanceService service;
  public AgentGovernanceController(AgentGovernanceService service) { this.service = service; }
  @PostMapping("/prompt-templates") @ResponseStatus(HttpStatus.CREATED) Created registerPromptTemplate(@PathVariable UUID projectId, @RequestBody @Valid PromptTemplateInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(service.registerPromptTemplate(projectId, jwt.getSubject(), input.templateKey(), input.semanticVersion(), input.displayName(), input.sourceReference(), input.templateSha256(), input.classification()).getId()); }
  @GetMapping("/prompt-templates") PageResponse<AgentGovernanceService.PromptTemplateView> promptTemplates(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return service.listPromptTemplates(projectId, jwt.getSubject(), page, size); }
  @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED) Created declareSession(@PathVariable UUID projectId, @RequestBody @Valid SessionInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(service.declareSession(projectId, jwt.getSubject(), input.promptTemplateId(), input.agentIdentity(), input.provider(), input.modelName(), input.modelVersion(), input.sessionFingerprint(), input.contextSha256(), input.toolInvocationCount(), input.toolInvocationSha256(), input.purpose()).getId()); }
  @GetMapping("/sessions") PageResponse<AgentGovernanceService.AgentSessionView> sessions(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return service.listSessions(projectId, jwt.getSubject(), page, size); }
  @PostMapping("/sessions/{sessionId}/complete") AgentGovernanceService.AgentSessionView complete(@PathVariable UUID projectId, @PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt) { return toView(service.completeSession(projectId, sessionId, jwt.getSubject())); }
  @PostMapping("/sessions/{sessionId}/block") AgentGovernanceService.AgentSessionView block(@PathVariable UUID projectId, @PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt) { return toView(service.blockSession(projectId, sessionId, jwt.getSubject())); }
  @PostMapping("/sessions/{sessionId}/evidence") @ResponseStatus(HttpStatus.CREATED) Created declareChange(@PathVariable UUID projectId, @PathVariable UUID sessionId, @RequestBody @Valid GeneratedChangeInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(service.declareGeneratedChange(projectId, sessionId, jwt.getSubject(), input.validationRunId(), input.evidenceAssetId(), input.changeReference(), input.generatedChangeSha256(), input.policyDecision(), input.policyReference(), input.approvalTitle(), input.approvalDetails(), input.requiredQuorum(), input.requestedApprover(), input.approvalDueAt()).getId()); }
  @GetMapping("/evidence") PageResponse<AgentGovernanceService.AgentEvidenceView> evidence(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return service.listEvidence(projectId, jwt.getSubject(), page, size); }
  private AgentGovernanceService.AgentSessionView toView(ai.xdev.aisdlc.domain.AgentSession s) { return new AgentGovernanceService.AgentSessionView(s.getId(), s.getPromptTemplateId(), s.getAgentIdentity(), s.getProvider(), s.getModelName(), s.getModelVersion(), s.getSessionFingerprint(), s.getContextSha256(), s.getToolInvocationCount(), s.getToolInvocationSha256(), s.getPurpose(), s.getStatus(), s.getDeclaredBy(), s.getDeclaredAt(), s.getCompletedAt()); }
}
