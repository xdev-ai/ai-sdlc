package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.RequirementSpecificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Requirement → specifying document version, and the history of that assignment.
 *
 * <p>Project scoped like the rest of the traceability surface: every call requires a JWT subject with membership in the
 * project, writes require owner or developer, reads accept viewer.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirement-specifications")
class RequirementSpecificationController {
  private final RequirementSpecificationService service;

  RequirementSpecificationController(RequirementSpecificationService service) { this.service = service; }

  /**
   * {@code sourceDocumentCode} is deliberately loose: it carries the document code exactly as the issuing system
   * writes it, and those codes are not ours to constrain. {@code spec_kits.slug} is restricted to {@code [a-z0-9-]},
   * so a code like {@code SPEC-042_v1.0} cannot round-trip through it, and losing the original reference breaks the
   * link back to the authority that issued the document.
   */
  record LinkInput(@NotNull UUID traceNodeId, @NotNull UUID specKitId,
                   @NotBlank @Size(max = 300) String sourceDocumentCode,
                   @Size(max = 4000) String supersedeReason) {}

  record CloseInput(@NotNull UUID traceNodeId, @NotBlank @Size(max = 4000) String reason) {}

  record Created(UUID id) {}

  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  Created link(@PathVariable UUID projectId, @RequestBody @Valid LinkInput input, @AuthenticationPrincipal Jwt jwt) {
    return new Created(service.link(projectId, jwt.getSubject(), input.traceNodeId(), input.specKitId(),
        input.sourceDocumentCode(), input.supersedeReason()));
  }

  @PostMapping("/close") @ResponseStatus(HttpStatus.NO_CONTENT)
  void close(@PathVariable UUID projectId, @RequestBody @Valid CloseInput input, @AuthenticationPrincipal Jwt jwt) {
    service.unlink(projectId, jwt.getSubject(), input.traceNodeId(), input.reason());
  }

  @GetMapping
  PageResponse<Map<String, Object>> current(@PathVariable UUID projectId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
      @AuthenticationPrincipal Jwt jwt) {
    return service.current(projectId, jwt.getSubject(), page, size);
  }

  @GetMapping("/history/{traceNodeId}")
  List<Map<String, Object>> history(@PathVariable UUID projectId, @PathVariable UUID traceNodeId, @AuthenticationPrincipal Jwt jwt) {
    return service.history(projectId, jwt.getSubject(), traceNodeId);
  }

  /** Requirements nothing currently specifies — the gap a matrix of only-what-is-linked hides. */
  @GetMapping("/unspecified")
  List<Map<String, Object>> unspecified(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
    return service.unspecified(projectId, jwt.getSubject());
  }

  /** Which requirements a document version currently governs, for impact analysis before revising it. */
  @GetMapping("/by-document/{specKitId}")
  List<Map<String, Object>> byDocument(@PathVariable UUID projectId, @PathVariable UUID specKitId, @AuthenticationPrincipal Jwt jwt) {
    return service.requirementsFor(projectId, jwt.getSubject(), specKitId);
  }
}
