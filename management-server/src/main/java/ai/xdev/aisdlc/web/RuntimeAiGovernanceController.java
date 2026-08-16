package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.RuntimeAiGovernanceService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runtime-ai-governance")
public class RuntimeAiGovernanceController {
  record DecisionInput(UUID agentSessionId,@NotNull UUID policyBundleId,@NotBlank @Pattern(regexp="PRE_FLIGHT|POST_FLIGHT|TOOL_CALL|EMERGENCY_OVERRIDE") String stage,@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$") String requestFingerprint,@NotNull JsonNode context,boolean dryRun) {}
  private final RuntimeAiGovernanceService service; public RuntimeAiGovernanceController(RuntimeAiGovernanceService service){this.service=service;}
  @PostMapping("/decisions") @ResponseStatus(HttpStatus.CREATED) RuntimeAiGovernanceService.DecisionView decide(@PathVariable UUID projectId,@RequestBody @Valid DecisionInput input,@AuthenticationPrincipal Jwt jwt){return service.decide(projectId,jwt.getSubject(),input.agentSessionId(),input.policyBundleId(),input.stage(),input.requestFingerprint(),input.context(),input.dryRun());}
}
