package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.ValidationStatus;
import ai.xdev.aisdlc.service.ValidationService;
import ai.xdev.aisdlc.web.ValidationContracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ValidationController {
  private final ValidationService validation;
  public ValidationController(ValidationService validation) { this.validation = validation; }

  @PostMapping("/cli/projects/{projectId}/validation-runs") @ResponseStatus(HttpStatus.CREATED)
  ValidationRunView ingest(@PathVariable UUID projectId, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey, @RequestBody @Valid ValidationRunRequest request, @AuthenticationPrincipal Jwt jwt) { return validation.ingest(projectId, jwt.getSubject(), idempotencyKey, request); }

  @GetMapping("/projects/{projectId}/validation-runs")
  PageResponse<ValidationRunListItem> list(@PathVariable UUID projectId, @RequestParam(required = false) ValidationStatus status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return validation.list(projectId, jwt.getSubject(), status, page, size); }

  @GetMapping("/projects/{projectId}/validation-runs/{runId}")
  ValidationRunDetailView detail(@PathVariable UUID projectId, @PathVariable UUID runId, @AuthenticationPrincipal Jwt jwt) { return validation.detail(projectId, runId, jwt.getSubject()); }

  @PutMapping("/projects/{projectId}/validation-runs/{runId}/findings/{findingId}/triage")
  FindingDetailView triageFinding(@PathVariable UUID projectId, @PathVariable UUID runId, @PathVariable UUID findingId, @RequestBody @Valid FindingTriageRequest request, @AuthenticationPrincipal Jwt jwt) { return validation.triageFinding(projectId, runId, findingId, jwt.getSubject(), request); }

  @PutMapping("/projects/{projectId}/validation-runs/{runId}/evidence/{evidenceId}/retention")
  EvidenceView setEvidenceRetention(@PathVariable UUID projectId, @PathVariable UUID runId, @PathVariable UUID evidenceId, @RequestBody @Valid EvidenceRetentionRequest request, @AuthenticationPrincipal Jwt jwt) { return validation.setEvidenceRetention(projectId, runId, evidenceId, jwt.getSubject(), request); }
}
