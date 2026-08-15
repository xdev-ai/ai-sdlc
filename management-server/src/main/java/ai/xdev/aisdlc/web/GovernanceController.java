package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.AuditEventRepository;
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
  private final GovernanceCatalogService governance; private final AuditEventRepository audits;
  public GovernanceController(GovernanceCatalogService governance, AuditEventRepository audits) { this.governance = governance; this.audits = audits; }
  record KitInput(@NotBlank String slug, @NotBlank String version, @NotNull KitLayer layer, @NotBlank String manifest) {}
  record PolicyInput(UUID projectId, @NotBlank String key, @NotBlank String version, @NotBlank String rule) {}
  record ConstitutionInput(UUID projectId, @NotBlank String version, @NotBlank String content) {}
  record CapabilityInput(@NotBlank String subject, @NotBlank String capability, Instant expiresAt) {}
  record ExceptionInput(@NotBlank String policyKey, @NotBlank String rationale) {}
  record TraceNodeInput(@NotNull TraceNodeType type, @NotBlank String externalKey, @NotBlank String label, String status) {}
  record TraceEdgeInput(@NotNull UUID sourceNodeId, @NotNull UUID targetNodeId, @NotBlank String relation) {}
  record ReviewInput(@NotNull ReviewType type, @NotBlank String title) {}
  record DecisionInput(@NotNull ReviewStatus decision, @Size(max = 4000) String note) {}
  record MetricsInput(@NotNull Instant periodStart, @NotNull Instant periodEnd, BigDecimal deploymentFrequency, BigDecimal leadTimeHours, BigDecimal changeFailureRate, BigDecimal prReviewTimeDeltaHours, BigDecimal reworkRate, BigDecimal reviewQueueHealth, BigDecimal specAlignmentScore) {}
  record Created(UUID id) {}

  @PostMapping("/organizations/{organizationId}/spec-kits") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created registerKit(@PathVariable UUID organizationId, @RequestBody @Valid KitInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.registerKit(organizationId, jwt.getSubject(), input.slug(), input.version(), input.layer(), input.manifest())); }
  @GetMapping("/organizations/{organizationId}/spec-kits") @PreAuthorize("hasAnyRole('admin','developer','reviewer')")
  List<Map<String, Object>> listKits(@PathVariable UUID organizationId) { return governance.listKits(organizationId); }
  @PostMapping("/projects/{projectId}/spec-kits/{kitId}/pin") @ResponseStatus(HttpStatus.NO_CONTENT)
  void pinKit(@PathVariable UUID projectId, @PathVariable UUID kitId, @RequestParam @Min(0) int precedence, @AuthenticationPrincipal Jwt jwt) { governance.pinKit(projectId, kitId, precedence, jwt.getSubject()); }

  @PostMapping("/organizations/{organizationId}/policies") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created addPolicy(@PathVariable UUID organizationId, @RequestBody @Valid PolicyInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addPolicy(organizationId, input.projectId(), jwt.getSubject(), input.key(), input.version(), input.rule())); }
  @GetMapping("/projects/{projectId}/policies") List<Map<String, Object>> policies(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return governance.listPolicies(projectId, jwt.getSubject()); }
  @PostMapping("/organizations/{organizationId}/constitutions") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created addConstitution(@PathVariable UUID organizationId, @RequestBody @Valid ConstitutionInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addConstitution(organizationId, input.projectId(), jwt.getSubject(), input.version(), input.content())); }
  @GetMapping("/projects/{projectId}/constitutions") List<Map<String, Object>> constitutions(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return governance.listConstitutions(projectId, jwt.getSubject()); }
  @PostMapping("/projects/{projectId}/capability-grants") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created grant(@PathVariable UUID projectId, @RequestBody @Valid CapabilityInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.grantCapability(projectId, jwt.getSubject(), input.subject(), input.capability(), input.expiresAt())); }
  @PostMapping("/projects/{projectId}/exception-requests") @ResponseStatus(HttpStatus.CREATED)
  Created exception(@PathVariable UUID projectId, @RequestBody @Valid ExceptionInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.requestException(projectId, jwt.getSubject(), input.policyKey(), input.rationale())); }

  @PostMapping("/projects/{projectId}/trace/nodes") @ResponseStatus(HttpStatus.CREATED)
  Created traceNode(@PathVariable UUID projectId, @RequestBody @Valid TraceNodeInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addTraceNode(projectId, jwt.getSubject(), input.type(), input.externalKey(), input.label(), input.status())); }
  @PostMapping("/projects/{projectId}/trace/edges") @ResponseStatus(HttpStatus.CREATED)
  Created traceEdge(@PathVariable UUID projectId, @RequestBody @Valid TraceEdgeInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.addTraceEdge(projectId, jwt.getSubject(), input.sourceNodeId(), input.targetNodeId(), input.relation())); }
  @GetMapping("/projects/{projectId}/traceability") Map<String, List<Map<String, Object>>> trace(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return governance.traceability(projectId, jwt.getSubject()); }

  @PostMapping("/projects/{projectId}/review-items") @ResponseStatus(HttpStatus.CREATED)
  Created review(@PathVariable UUID projectId, @RequestBody @Valid ReviewInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.requestReview(projectId, jwt.getSubject(), input.type(), input.title())); }
  @PostMapping("/projects/{projectId}/review-items/{reviewId}/decision") @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('admin','reviewer')") void reviewDecision(@PathVariable UUID projectId, @PathVariable UUID reviewId, @RequestBody @Valid DecisionInput input, @AuthenticationPrincipal Jwt jwt) { governance.decideReview(projectId, reviewId, jwt.getSubject(), input.decision(), input.note()); }
  @GetMapping("/projects/{projectId}/review-items") List<Map<String, Object>> reviews(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return governance.reviewQueue(projectId, jwt.getSubject()); }

  @PostMapping("/projects/{projectId}/quality-metrics") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  Created metricsWrite(@PathVariable UUID projectId, @RequestBody @Valid MetricsInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(governance.writeMetrics(projectId, jwt.getSubject(), input.periodStart(), input.periodEnd(), input.deploymentFrequency(), input.leadTimeHours(), input.changeFailureRate(), input.prReviewTimeDeltaHours(), input.reworkRate(), input.reviewQueueHealth(), input.specAlignmentScore())); }
  @GetMapping("/projects/{projectId}/quality-metrics") List<Map<String, Object>> metrics(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return governance.metrics(projectId, jwt.getSubject()); }
  @GetMapping("/organizations/{organizationId}/audit-events") @PreAuthorize("hasRole('admin')")
  List<Map<String, Object>> audit(@PathVariable UUID organizationId) { return audits.findTop100ByOrganizationIdOrderBySequenceDesc(organizationId).stream().map(event -> Map.<String, Object>of("sequence", event.getSequence(), "eventHash", event.getEventHash(), "action", event.getAction(), "occurredAt", event.getOccurredAt())).toList(); }
}
