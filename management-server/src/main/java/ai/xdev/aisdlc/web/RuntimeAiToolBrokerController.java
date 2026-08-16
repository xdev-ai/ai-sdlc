package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.RuntimeAiToolBrokerService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal agent-runtime surface for tool capability grants.
 *
 * <p>A workload asks for a grant covering exact arguments and later redeems it once. The grant secret is present only
 * in the issue response; it is never returned by any other endpoint and never appears in an audit record. The workload
 * subject comes from the validated token, so a caller cannot request or redeem a grant belonging to another workload.
 */
@RestController
@RequestMapping("/internal/runtime-ai/projects/{projectId}/tool-grants")
@ConditionalOnProperty(name = "aisdlc.runtime-ai.tool-broker-enabled", havingValue = "true")
public class RuntimeAiToolBrokerController {
  record GrantInput(
      @NotNull UUID agentSessionId,
      @NotBlank @Size(max = 160) String toolName,
      @NotNull JsonNode arguments,
      UUID approvalRequestId,
      @Min(1) @Max(300) Integer ttlSeconds) {}

  record RedemptionInput(@NotBlank @Size(max = 256) String grantSecret, @NotNull JsonNode arguments) {}

  record GrantView(String outcome, String reasonCode, UUID grantId, String grantSecret, Instant expiresAt,
                   String argumentFingerprint, UUID runtimeDecisionId) {}

  record RedemptionView(String outcome, String reasonCode, UUID grantId, String receiptSha256) {}

  private final RuntimeAiToolBrokerService broker;

  public RuntimeAiToolBrokerController(RuntimeAiToolBrokerService broker) {
    this.broker = broker;
  }

  @PostMapping
  @PreAuthorize("hasAuthority('ROLE_agent_runtime')")
  public ResponseEntity<GrantView> issue(@PathVariable UUID projectId, @RequestBody @Valid GrantInput input, @AuthenticationPrincipal Jwt jwt) {
    var issued = broker.issue(projectId, jwt.getSubject(), input.agentSessionId(), input.toolName(), input.arguments(),
        input.approvalRequestId(), input.ttlSeconds() == null ? null : Duration.ofSeconds(input.ttlSeconds()));
    var view = new GrantView(issued.outcome(), issued.reasonCode(), issued.grantId(), issued.grantSecret(),
        issued.expiresAt(), issued.argumentFingerprint(), issued.runtimeDecisionId());
    return ResponseEntity.status(issued.allowed() ? HttpStatus.CREATED : HttpStatus.FORBIDDEN).body(view);
  }

  @PostMapping("/redemptions")
  @PreAuthorize("hasAuthority('ROLE_agent_runtime')")
  public ResponseEntity<RedemptionView> redeem(@PathVariable UUID projectId, @RequestBody @Valid RedemptionInput input, @AuthenticationPrincipal Jwt jwt) {
    var redemption = broker.redeem(projectId, jwt.getSubject(), input.grantSecret(), input.arguments());
    var view = new RedemptionView(redemption.outcome(), redemption.reasonCode(), redemption.grantId(), redemption.receiptSha256());
    return ResponseEntity.status(redemption.allowed() ? HttpStatus.OK : HttpStatus.FORBIDDEN).body(view);
  }
}
