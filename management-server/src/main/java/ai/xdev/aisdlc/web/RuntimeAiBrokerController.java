package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.RuntimeAiBrokerService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Authorization-only surface. A separate, later release must own outbound provider and tool execution. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runtime-ai-broker")
public class RuntimeAiBrokerController {
  record WorkloadInput(@NotBlank @Size(max=240) String workloadSubject, boolean active) {}
  record ProviderInput(@NotBlank @Size(max=160) String provider, @NotBlank @Size(max=240) String model, @NotNull UUID policyBundleId, @NotBlank @Pattern(regexp="^https://[^?#\\s]+$") @Size(max=2048) String endpointUri, @NotBlank @Size(max=240) String credentialReference, @Size(max=240) String mtlsReference, boolean requireMtls, @Min(100) @Max(120000) int timeoutMs, @Min(1) @Max(3) int maxAttempts, boolean active) {}
  record ToolInput(@NotBlank @Size(max=160) String toolName, @NotNull UUID policyBundleId, @Pattern(regexp="READ_ONLY|MUTATING|HIGH_IMPACT") String impactLevel, boolean requiresApproval, boolean active) {}
  record ProviderRequest(@NotNull UUID agentSessionId,@NotBlank @Size(max=160) String provider,@NotBlank @Size(max=240) String model,@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$") String requestFingerprint,@NotNull JsonNode policyContext,boolean dryRun) {}
  record ToolRequest(@NotNull UUID agentSessionId,@NotBlank @Size(max=160) String toolName,@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$") String requestFingerprint,@NotNull JsonNode policyContext,UUID approvalRequestId,boolean dryRun) {}
  private final RuntimeAiBrokerService broker;
  public RuntimeAiBrokerController(RuntimeAiBrokerService broker){this.broker=broker;}
  @PutMapping("/workloads") @ResponseStatus(HttpStatus.NO_CONTENT) public void configureWorkload(@PathVariable UUID projectId,@RequestBody @Valid WorkloadInput input,@AuthenticationPrincipal Jwt jwt){broker.registerWorkload(projectId,jwt.getSubject(),input.workloadSubject(),input.active());}
  @PutMapping("/providers") @ResponseStatus(HttpStatus.NO_CONTENT) public void configureProvider(@PathVariable UUID projectId,@RequestBody @Valid ProviderInput input,@AuthenticationPrincipal Jwt jwt){broker.configureProvider(projectId,jwt.getSubject(),input.provider(),input.model(),input.policyBundleId(),input.endpointUri(),input.credentialReference(),input.mtlsReference(),input.requireMtls(),input.timeoutMs(),input.maxAttempts(),input.active());}
  @PutMapping("/tools") @ResponseStatus(HttpStatus.NO_CONTENT) public void configureTool(@PathVariable UUID projectId,@RequestBody @Valid ToolInput input,@AuthenticationPrincipal Jwt jwt){broker.configureTool(projectId,jwt.getSubject(),input.toolName(),input.policyBundleId(),input.impactLevel(),input.requiresApproval(),input.active());}
  @PostMapping("/provider-authorizations") public RuntimeAiBrokerService.AuthorizationView authorizeProvider(@PathVariable UUID projectId,@RequestBody @Valid ProviderRequest input,@AuthenticationPrincipal Jwt jwt){return broker.preflight(projectId,jwt.getSubject(),input.agentSessionId(),input.provider(),input.model(),input.requestFingerprint(),input.policyContext(),input.dryRun());}
  @PostMapping("/tool-authorizations") public RuntimeAiBrokerService.AuthorizationView authorizeTool(@PathVariable UUID projectId,@RequestBody @Valid ToolRequest input,@AuthenticationPrincipal Jwt jwt){return broker.authorizeTool(projectId,jwt.getSubject(),input.agentSessionId(),input.toolName(),input.requestFingerprint(),input.policyContext(),input.approvalRequestId(),input.dryRun());}
}
