package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.RuntimeAiProviderProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal agent-runtime surface for the provider proxy.
 *
 * <p>The endpoint exists only when a deployment enables it, sits outside {@code /api/**} and the browser CORS policy,
 * and is reachable only by a token that the resource server accepted as an agent workload. The workload subject is
 * taken from the validated token, never from the request body, so a caller cannot dispatch as another workload. The
 * provider response is returned to that authorized caller and is never persisted; only digests reach the audit record.
 */
@RestController
@RequestMapping("/internal/runtime-ai/projects/{projectId}")
@ConditionalOnProperty(name = "aisdlc.runtime-ai.provider-proxy-enabled", havingValue = "true")
public class RuntimeAiProviderProxyController {
  record InvocationInput(
      @NotNull UUID agentSessionId,
      @NotBlank @Size(max = 160) String provider,
      @NotBlank @Size(max = 240) String model,
      @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String requestFingerprint,
      @NotNull JsonNode policyContext,
      @NotNull JsonNode payload) {}

  record InvocationView(String outcome, String reasonCode, Integer providerHttpStatus, int attempts,
                        String requestSha256, String responseSha256, UUID runtimeDecisionId, String providerResponse) {}

  private final RuntimeAiProviderProxyService proxy;

  public RuntimeAiProviderProxyController(RuntimeAiProviderProxyService proxy) {
    this.proxy = proxy;
  }

  @PostMapping("/provider-invocations")
  @PreAuthorize("hasAuthority('ROLE_agent_runtime')")
  public ResponseEntity<InvocationView> invoke(@PathVariable UUID projectId,
                                               @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                               @RequestBody @Valid InvocationInput input,
                                               @AuthenticationPrincipal Jwt jwt) {
    var result = proxy.invoke(projectId, jwt.getSubject(), new RuntimeAiProviderProxyService.InvocationRequest(
        input.agentSessionId(), input.provider(), input.model(), input.requestFingerprint(),
        input.policyContext(), idempotencyKey, input.payload()));
    var view = new InvocationView(result.outcome(), result.reasonCode(), result.httpStatus(), result.attempts(),
        result.requestSha256(), result.responseSha256(), result.runtimeDecisionId(),
        "COMPLETE".equals(result.outcome()) ? result.responseBody() : null);
    return ResponseEntity.status(statusFor(result.outcome(), result.reasonCode())).body(view);
  }

  /**
   * A replayed idempotency key is a conflict, not an authorization failure: reporting it as 403 would make a safe
   * retry look like a governance block. Only a real governance decision returns 403.
   */
  private static HttpStatus statusFor(String outcome, String reasonCode) {
    if ("DUPLICATE_REQUEST".equals(reasonCode)) return HttpStatus.CONFLICT;
    return switch (outcome) {
      case "COMPLETE" -> HttpStatus.OK;
      case "BLOCKED" -> HttpStatus.FORBIDDEN;
      default -> HttpStatus.BAD_GATEWAY;
    };
  }
}
