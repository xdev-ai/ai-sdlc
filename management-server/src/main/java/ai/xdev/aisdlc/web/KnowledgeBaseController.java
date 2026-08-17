package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.KnowledgeBaseService;
import ai.xdev.aisdlc.web.KnowledgeBaseContracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * The documentation repository: spaces, nested pages, version history, and the retrieval endpoints an AI reads.
 *
 * <p>Everything hangs off {@code /organizations/{organizationId}} so the scope of a request is visible in its URL and
 * every query can be constrained by it. A page id alone would be enough to read a page otherwise, and ids are
 * guessable in a way an organization membership is not.
 *
 * <p>Reads are open to any control-plane role, including {@code viewer}: documentation nobody may read is not
 * documentation. Writes require {@code admin} or {@code developer}, matching how Spec Kits and policies are governed
 * in this platform — page text grounds AI answers, so authoring it is a governed act, not a comment.
 */
@Validated
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/knowledge")
public class KnowledgeBaseController {
  private final KnowledgeBaseService knowledge;

  public KnowledgeBaseController(KnowledgeBaseService knowledge) { this.knowledge = knowledge; }

  // --- spaces ---

  @PostMapping("/spaces") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('admin','developer')")
  SpaceView createSpace(@PathVariable UUID organizationId, @RequestBody @Valid CreateSpaceRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return knowledge.createSpace(organizationId, jwt.getSubject(), request);
  }

  @GetMapping("/spaces")
  PageResponse<SpaceView> listSpaces(@PathVariable UUID organizationId,
      @RequestParam(defaultValue = "false") boolean includeArchived,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
    return knowledge.listSpaces(organizationId, includeArchived, page, size);
  }

  @GetMapping("/spaces/{spaceId}")
  SpaceView space(@PathVariable UUID organizationId, @PathVariable UUID spaceId) {
    return knowledge.space(organizationId, spaceId);
  }

  /**
   * Archives a space. There is no delete: removing documentation would also remove the record that an AI answer was
   * once grounded in it.
   */
  @DeleteMapping("/spaces/{spaceId}") @PreAuthorize("hasAnyRole('admin','developer')")
  SpaceView archiveSpace(@PathVariable UUID organizationId, @PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    return knowledge.archiveSpace(organizationId, spaceId, jwt.getSubject());
  }

  // --- pages ---

  @PostMapping("/spaces/{spaceId}/pages") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('admin','developer')")
  PageDetail createPage(@PathVariable UUID organizationId, @PathVariable UUID spaceId,
      @RequestBody @Valid CreatePageRequest request, @AuthenticationPrincipal Jwt jwt) {
    return knowledge.createPage(organizationId, spaceId, jwt.getSubject(), request);
  }

  @GetMapping("/spaces/{spaceId}/pages")
  List<PageNode> pageTree(@PathVariable UUID organizationId, @PathVariable UUID spaceId,
      @RequestParam(defaultValue = "false") boolean includeArchived) {
    return knowledge.pageTree(organizationId, spaceId, includeArchived);
  }

  @GetMapping("/pages/{pageId}")
  PageDetail page(@PathVariable UUID organizationId, @PathVariable UUID pageId) {
    return knowledge.page(organizationId, pageId);
  }

  /**
   * Authors a new version. This is a {@code PUT} on the page, but nothing is overwritten: the body becomes version
   * {@code n+1} and the previous text stays readable at its own version number.
   */
  @PutMapping("/pages/{pageId}") @PreAuthorize("hasAnyRole('admin','developer')")
  PageDetail authorVersion(@PathVariable UUID organizationId, @PathVariable UUID pageId,
      @RequestBody @Valid AuthorVersionRequest request, @AuthenticationPrincipal Jwt jwt) {
    return knowledge.authorVersion(organizationId, pageId, jwt.getSubject(), request);
  }

  @GetMapping("/pages/{pageId}/versions")
  PageResponse<VersionSummary> versions(@PathVariable UUID organizationId, @PathVariable UUID pageId,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
    return knowledge.versions(organizationId, pageId, page, size);
  }

  @GetMapping("/pages/{pageId}/versions/{version}")
  PageDetail version(@PathVariable UUID organizationId, @PathVariable UUID pageId, @PathVariable int version) {
    return knowledge.pageAtVersion(organizationId, pageId, version);
  }

  @PutMapping("/pages/{pageId}/parent") @PreAuthorize("hasAnyRole('admin','developer')")
  PageDetail movePage(@PathVariable UUID organizationId, @PathVariable UUID pageId,
      @RequestBody @Valid MovePageRequest request, @AuthenticationPrincipal Jwt jwt) {
    return knowledge.movePage(organizationId, pageId, jwt.getSubject(), request);
  }

  @PutMapping("/pages/{pageId}/status") @PreAuthorize("hasAnyRole('admin','developer')")
  PageDetail setStatus(@PathVariable UUID organizationId, @PathVariable UUID pageId,
      @RequestBody @Valid PageStatusRequest request, @AuthenticationPrincipal Jwt jwt) {
    return knowledge.setPageStatus(organizationId, pageId, jwt.getSubject(), request);
  }

  // --- labels and references ---

  @PostMapping("/pages/{pageId}/labels") @PreAuthorize("hasAnyRole('admin','developer')")
  List<String> applyLabel(@PathVariable UUID organizationId, @PathVariable UUID pageId,
      @RequestBody @Valid LabelRequest request, @AuthenticationPrincipal Jwt jwt) {
    return knowledge.applyLabel(organizationId, pageId, jwt.getSubject(), request);
  }

  @DeleteMapping("/pages/{pageId}/labels/{label}") @PreAuthorize("hasAnyRole('admin','developer')")
  List<String> removeLabel(@PathVariable UUID organizationId, @PathVariable UUID pageId,
      @PathVariable @Size(max = 80) @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,79}$") String label,
      @AuthenticationPrincipal Jwt jwt) {
    return knowledge.removeLabel(organizationId, pageId, label, jwt.getSubject());
  }

  @PostMapping("/pages/{pageId}/references") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('admin','developer')")
  List<ReferenceView> linkReference(@PathVariable UUID organizationId, @PathVariable UUID pageId,
      @RequestBody @Valid LinkReferenceRequest request, @AuthenticationPrincipal Jwt jwt) {
    return knowledge.linkReference(organizationId, pageId, jwt.getSubject(), request);
  }

  // --- retrieval ---

  @GetMapping("/search")
  List<SearchHit> search(@PathVariable UUID organizationId, @RequestParam String q,
      @RequestParam(required = false) String spaceKey, @RequestParam(required = false) String label,
      @RequestParam(defaultValue = "20") int limit) {
    return knowledge.search(organizationId, q, spaceKey, label, limit);
  }

  /**
   * The endpoint an agent calls before answering a question about the project: ranked chunks that fit a character
   * budget, each with a citation, and a caveat naming what this retrieval cannot do.
   */
  @GetMapping("/context")
  ContextBundle context(@PathVariable UUID organizationId, @RequestParam String q,
      @RequestParam(required = false) String spaceKey, @RequestParam(required = false) String label,
      @RequestParam(defaultValue = "0") int budgetChars) {
    return knowledge.assembleContext(organizationId, q, spaceKey, label, budgetChars);
  }
}
