package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.PolicyEvaluationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/policy-bundles")
public class PolicyAsCodeController {
  private final PolicyEvaluationService policies;
  public PolicyAsCodeController(PolicyEvaluationService policies) { this.policies = policies; }
  record BundleInput(@NotBlank @Pattern(regexp = "[a-z0-9._-]{3,160}") String key, @NotBlank @Pattern(regexp = "[0-9]+\\.[0-9]+\\.[0-9]+([-.+][0-9A-Za-z.-]+)?") String semanticVersion, @Size(max = 2000) String description, @NotBlank @Size(max = 12000) String expression, JsonNode fixtures, boolean dryRunDefault) {}
  record EvaluationInput(@NotNull JsonNode context, boolean dryRun) {}

  @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  PolicyEvaluationService.PolicyBundleView create(@PathVariable UUID projectId, @RequestBody @Valid BundleInput input, @AuthenticationPrincipal Jwt jwt) { return policies.create(projectId, jwt.getSubject(), input.key(), input.semanticVersion(), input.description(), input.expression(), input.fixtures(), input.dryRunDefault()); }
  @GetMapping
  PageResponse<PolicyEvaluationService.PolicyBundleView> list(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) { return policies.list(projectId, jwt.getSubject(), page, size); }
  @PostMapping("/{bundleId}/activate") @PreAuthorize("hasRole('admin')")
  PolicyEvaluationService.PolicyBundleView activate(@PathVariable UUID projectId, @PathVariable UUID bundleId, @AuthenticationPrincipal Jwt jwt) { return policies.activate(projectId, bundleId, jwt.getSubject()); }
  @PostMapping("/{bundleId}/retire") @PreAuthorize("hasRole('admin')")
  PolicyEvaluationService.PolicyBundleView retire(@PathVariable UUID projectId, @PathVariable UUID bundleId, @AuthenticationPrincipal Jwt jwt) { return policies.retire(projectId, bundleId, jwt.getSubject()); }
  @PostMapping("/{bundleId}/evaluate")
  PolicyEvaluationService.EvaluationView evaluate(@PathVariable UUID projectId, @PathVariable UUID bundleId, @RequestBody @Valid EvaluationInput input, @AuthenticationPrincipal Jwt jwt) { return policies.evaluate(projectId, bundleId, jwt.getSubject(), input.context(), input.dryRun()); }
  @PostMapping("/{bundleId}/test")
  PolicyEvaluationService.TestRunView test(@PathVariable UUID projectId, @PathVariable UUID bundleId, @AuthenticationPrincipal Jwt jwt) { return policies.runFixtures(projectId, bundleId, jwt.getSubject(), true); }
  @GetMapping("/{bundleId}/evaluations")
  PageResponse<PolicyEvaluationService.EvaluationView> evaluations(@PathVariable UUID projectId, @PathVariable UUID bundleId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) { return policies.evaluations(projectId, bundleId, jwt.getSubject(), page, size); }
}
