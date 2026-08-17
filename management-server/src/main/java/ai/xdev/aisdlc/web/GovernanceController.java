package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.service.AuditVerificationService;
import ai.xdev.aisdlc.service.GovernanceCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GovernanceController {
  private final GovernanceCatalogService governance;
  private final AuditVerificationService audit;

  public GovernanceController(GovernanceCatalogService governance, AuditVerificationService audit) { this.governance = governance; this.audit = audit; }

  record KitInput(@NotBlank @Pattern(regexp = "[a-z0-9-]{3,100}") String slug, @NotBlank @Size(max = 80) String version, @NotNull KitLayer layer, @NotBlank @Size(max = 100000) String manifest) {}
  record PolicyInput(UUID projectId, @NotBlank @Pattern(regexp = "[a-z0-9._-]{3,160}") String key, @NotBlank @Size(max = 80) String version, @NotBlank @Size(max = 100000) String rule) {}
  record ConstitutionInput(UUID projectId, @NotBlank @Size(max = 80) String version, @NotBlank @Size(max = 100000) String content) {}
  record CapabilityInput(@NotBlank @Size(max = 120) String subject, @NotBlank @Pattern(regexp = "[a-z0-9._:-]{3,160}") String capability, Instant expiresAt) {}
  record ExceptionInput(@NotBlank @Pattern(regexp = "[a-z0-9._-]{3,160}") String policyKey, @NotBlank @Size(max = 8000) String rationale) {}
  record ExceptionDecisionInput(@NotNull ReviewStatus decision, @Size(max = 4000) String note, Instant expiresAt) {}
  record TraceNodeInput(@NotNull TraceNodeType type, @NotBlank @Size(max = 160) String externalKey, @NotBlank @Size(max = 300) String label, @Size(max = 50) String status) {}
  record TraceEdgeInput(@NotNull UUID sourceNodeId, @NotNull UUID targetNodeId, @NotBlank @Size(max = 80) String relation) {}
  record ReviewInput(@NotNull ReviewType type, @NotBlank @Size(max = 300) String title) {}
  record DecisionInput(@NotNull ReviewStatus decision, @Size(max = 4000) String note) {}
  record MetricsInput(@NotNull Instant periodStart, @NotNull Instant periodEnd, BigDecimal deploymentFrequency, BigDecimal leadTimeHours, BigDecimal changeFailureRate, BigDecimal prReviewTimeDeltaHours, BigDecimal reworkRate, BigDecimal reviewQueueHealth, BigDecimal specAlignmentScore) {}
  record DeprecateKitInput(@NotBlank @Size(max = 4000) String reason) {}
  record Created(UUID id) {}

  @PostMapping("/organizations/{organizationId}/spec-kits") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created registerKit(@PathVariable UUID organizationId, @RequestBody @Valid KitInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.registerKit(organizationId, jwt.getSubject(), input.slug(), input.version(), input.layer(), input.manifest())); }
  @GetMapping("/organizations/{organizationId}/spec-kits") @PreAuthorize("hasRole('admin')")
  PageResponse<Map<String, Object>> listKits(@PathVariable UUID organizationId, @RequestParam(required = false) String lifecycle, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return governance.listKits(organizationId, lifecycle, page, size); }
  @PostMapping("/organizations/{organizationId}/spec-kits/{kitId}/deprecate") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('admin')")
  void deprecateKit(@PathVariable UUID organizationId, @PathVariable UUID kitId, @RequestBody @Valid DeprecateKitInput input, @AuthenticationPrincipal Jwt jwt) { governance.deprecateKit(organizationId, kitId, jwt.getSubject(), input.reason()); }
  @PostMapping("/projects/{projectId}/spec-kits/{kitId}/pin") @ResponseStatus(HttpStatus.NO_CONTENT)
  void pinKit(@PathVariable UUID projectId, @PathVariable UUID kitId, @RequestParam @Min(0) @Max(10000) int precedence, @AuthenticationPrincipal Jwt jwt) { governance.pinKit(projectId, kitId, precedence, jwt.getSubject()); }
  @DeleteMapping("/projects/{projectId}/spec-kits/{kitId}/pin") @ResponseStatus(HttpStatus.NO_CONTENT)
  void unpinKit(@PathVariable UUID projectId, @PathVariable UUID kitId, @AuthenticationPrincipal Jwt jwt) { governance.unpinKit(projectId, kitId, jwt.getSubject()); }
  @GetMapping("/projects/{projectId}/spec-kits")
  List<Map<String, Object>> projectKits(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return governance.projectKits(projectId, jwt.getSubject()); }

  @PostMapping("/organizations/{organizationId}/policies") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created addPolicy(@PathVariable UUID organizationId, @RequestBody @Valid PolicyInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addPolicy(organizationId, input.projectId(), jwt.getSubject(), input.key(), input.version(), input.rule())); }
  @GetMapping("/projects/{projectId}/policies")
  PageResponse<Map<String, Object>> policies(@PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean includeInactive, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return governance.listPolicies(projectId, jwt.getSubject(), includeInactive, page, size); }
  @PostMapping("/organizations/{organizationId}/policies/{policyId}/activate") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('admin')")
  void activatePolicy(@PathVariable UUID organizationId, @PathVariable UUID policyId, @RequestParam(required = false) UUID projectId, @AuthenticationPrincipal Jwt jwt) { governance.changePolicyStatus(organizationId, projectId, policyId, jwt.getSubject(), true); }
  @PostMapping("/organizations/{organizationId}/policies/{policyId}/deactivate") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('admin')")
  void deactivatePolicy(@PathVariable UUID organizationId, @PathVariable UUID policyId, @RequestParam(required = false) UUID projectId, @AuthenticationPrincipal Jwt jwt) { governance.changePolicyStatus(organizationId, projectId, policyId, jwt.getSubject(), false); }

  @PostMapping("/organizations/{organizationId}/constitutions") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created addConstitution(@PathVariable UUID organizationId, @RequestBody @Valid ConstitutionInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addConstitution(organizationId, input.projectId(), jwt.getSubject(), input.version(), input.content())); }
  @GetMapping("/projects/{projectId}/constitutions")
  PageResponse<Map<String, Object>> constitutions(@PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean includeInactive, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return governance.listConstitutions(projectId, jwt.getSubject(), includeInactive, page, size); }
  @PostMapping("/organizations/{organizationId}/constitutions/{constitutionId}/activate") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('admin')")
  void activateConstitution(@PathVariable UUID organizationId, @PathVariable UUID constitutionId, @RequestParam(required = false) UUID projectId, @AuthenticationPrincipal Jwt jwt) { governance.changeConstitutionStatus(organizationId, projectId, constitutionId, jwt.getSubject(), true); }
  @PostMapping("/organizations/{organizationId}/constitutions/{constitutionId}/deactivate") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('admin')")
  void deactivateConstitution(@PathVariable UUID organizationId, @PathVariable UUID constitutionId, @RequestParam(required = false) UUID projectId, @AuthenticationPrincipal Jwt jwt) { governance.changeConstitutionStatus(organizationId, projectId, constitutionId, jwt.getSubject(), false); }

  @PostMapping("/projects/{projectId}/capability-grants") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created grant(@PathVariable UUID projectId, @RequestBody @Valid CapabilityInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.grantCapability(projectId, jwt.getSubject(), input.subject(), input.capability(), input.expiresAt())); }
  @GetMapping("/projects/{projectId}/capability-grants")
  PageResponse<Map<String, Object>> capabilities(@PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean includeExpired, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return governance.listCapabilities(projectId, jwt.getSubject(), includeExpired, page, size); }
  @PostMapping("/projects/{projectId}/exception-requests") @ResponseStatus(HttpStatus.CREATED)
  Created exception(@PathVariable UUID projectId, @RequestBody @Valid ExceptionInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.requestException(projectId, jwt.getSubject(), input.policyKey(), input.rationale())); }
  @GetMapping("/projects/{projectId}/exception-requests")
  PageResponse<Map<String, Object>> exceptions(@PathVariable UUID projectId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return governance.exceptions(projectId, jwt.getSubject(), status, page, size); }
  @PostMapping("/projects/{projectId}/exception-requests/{exceptionId}/decision") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAnyRole('admin','reviewer')")
  void exceptionDecision(@PathVariable UUID projectId, @PathVariable UUID exceptionId, @RequestBody @Valid ExceptionDecisionInput input, @AuthenticationPrincipal Jwt jwt) { governance.decideException(projectId, exceptionId, jwt.getSubject(), input.decision(), input.note(), input.expiresAt()); }

  @PostMapping("/projects/{projectId}/trace/nodes") @ResponseStatus(HttpStatus.CREATED)
  Created traceNode(@PathVariable UUID projectId, @RequestBody @Valid TraceNodeInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addTraceNode(projectId, jwt.getSubject(), input.type(), input.externalKey(), input.label(), input.status())); }
  @PostMapping("/projects/{projectId}/trace/edges") @ResponseStatus(HttpStatus.CREATED)
  Created traceEdge(@PathVariable UUID projectId, @RequestBody @Valid TraceEdgeInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addTraceEdge(projectId, jwt.getSubject(), input.sourceNodeId(), input.targetNodeId(), input.relation())); }
  @GetMapping("/projects/{projectId}/traceability")
  Map<String, List<Map<String, Object>>> trace(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return governance.traceability(projectId, jwt.getSubject()); }

  @PostMapping("/projects/{projectId}/review-items") @ResponseStatus(HttpStatus.CREATED)
  Created review(@PathVariable UUID projectId, @RequestBody @Valid ReviewInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.requestReview(projectId, jwt.getSubject(), input.type(), input.title())); }
  @PostMapping("/projects/{projectId}/review-items/{reviewId}/decision") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAnyRole('admin','reviewer')")
  void reviewDecision(@PathVariable UUID projectId, @PathVariable UUID reviewId, @RequestBody @Valid DecisionInput input, @AuthenticationPrincipal Jwt jwt) { governance.decideReview(projectId, reviewId, jwt.getSubject(), input.decision(), input.note()); }
  @GetMapping("/projects/{projectId}/review-items")
  PageResponse<Map<String, Object>> reviews(@PathVariable UUID projectId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return governance.reviewQueue(projectId, jwt.getSubject(), status, page, size); }

  @PostMapping("/projects/{projectId}/quality-metrics") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created metricsWrite(@PathVariable UUID projectId, @RequestBody @Valid MetricsInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.writeMetrics(projectId, jwt.getSubject(), input.periodStart(), input.periodEnd(), input.deploymentFrequency(), input.leadTimeHours(), input.changeFailureRate(), input.prReviewTimeDeltaHours(), input.reworkRate(), input.reviewQueueHealth(), input.specAlignmentScore())); }
  @GetMapping("/projects/{projectId}/quality-metrics")
  PageResponse<Map<String, Object>> metrics(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size, @AuthenticationPrincipal Jwt jwt) { return governance.metrics(projectId, jwt.getSubject(), page, size); }

  @GetMapping("/organizations/{organizationId}/audit-events") @PreAuthorize("hasRole('admin')")
  PageResponse<AuditVerificationService.AuditEventView> audit(@PathVariable UUID organizationId, @RequestParam(required = false) String action, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return audit.list(organizationId, action, page, size); }
  @GetMapping("/organizations/{organizationId}/audit-events/verify") @PreAuthorize("hasRole('admin')")
  AuditVerificationService.VerificationView verifyAudit(@PathVariable UUID organizationId) { return audit.verify(organizationId); }
}
