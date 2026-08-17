package ai.xdev.aisdlc.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the knowledge base.
 *
 * <p>Every {@code @Size} bound here is the width of the column the value is written into, not a round number that
 * looked reasonable. A bound larger than its column turns a caller who obeyed the documented limit into a
 * {@code 500} — the insert fails after validation has already passed — and {@code RequestBoundsMatchSchemaTest}
 * checks these against the migrations for exactly that reason.
 */
public final class KnowledgeBaseContracts {
  private KnowledgeBaseContracts() {}

  /**
   * {@code text} columns have no width, but an unbounded request body is still a way to write a hundred-megabyte
   * row through a validated endpoint. These two limits are the deliberate ceiling: generous for real documentation,
   * far below the point where one page degrades the table.
   */
  public static final int MAX_DESCRIPTION = 4_000;
  public static final int MAX_BODY = 400_000;

  // --- spaces ---

  public record CreateSpaceRequest(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{1,59}$",
          message = "must start alphanumeric and contain only letters, digits, dot, underscore or hyphen")
      @Size(max = 60) String spaceKey,
      @NotBlank @Size(max = 200) String name,
      @Size(max = MAX_DESCRIPTION) String description,
      UUID projectId) {}

  public record SpaceView(UUID id, UUID organizationId, UUID projectId, String spaceKey, String name,
      String description, String createdBy, Instant createdAt, Instant archivedAt, long pageCount) {}

  // --- pages ---

  public record CreatePageRequest(
      @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,159}$", message = "must be lowercase alphanumeric with hyphens")
      @Size(max = 160) String slug,
      UUID parentPageId,
      @NotBlank @Size(max = 300) String title,
      @NotBlank @Size(max = MAX_BODY) String body,
      @Size(max = 1000) String changeNote,
      List<@Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,79}$") @Size(max = 80) String> labels) {}

  /** Authoring an edit. There is no update-in-place: a new version is the only way page text changes. */
  public record AuthorVersionRequest(
      @NotBlank @Size(max = 300) String title,
      @NotBlank @Size(max = MAX_BODY) String body,
      @Size(max = 1000) String changeNote) {}

  public record MovePageRequest(UUID parentPageId, @PositiveOrZero Integer position) {}

  public record PageStatusRequest(
      @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|ARCHIVED") String pageStatus) {}

  /** One node of the space tree. {@code depth} is precomputed so a client can indent without walking parents. */
  public record PageNode(UUID id, UUID parentPageId, String slug, String title, int currentVersion,
      String pageStatus, int position, int depth, Instant updatedAt) {}

  public record PageDetail(UUID id, UUID spaceId, String spaceKey, String slug, String title, String body,
      int version, String pageStatus, List<String> breadcrumb, List<String> labels, List<ReferenceView> references,
      String authoredBy, Instant authoredAt, String bodySha256, String changeNote, int chunkCount) {}

  public record VersionSummary(int version, String title, String changeNote, String authoredBy, Instant authoredAt,
      String bodySha256, int bodyChars, boolean current) {}

  // --- labels and references ---

  public record LabelRequest(
      @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,79}$") @Size(max = 80) String label) {}

  /** Exactly one target, which the database enforces independently of this record. */
  public record LinkReferenceRequest(UUID specKitId, UUID traceNodeId, UUID evidenceAssetId,
      @Size(max = 500) String referenceNote) {}

  public record ReferenceView(UUID id, String targetType, UUID targetId, String targetLabel, String referenceNote,
      String linkedBy, Instant linkedAt) {}

  // --- retrieval ---

  /**
   * One chunk that matched, with everything needed to cite it: which page, which version, and where inside that
   * version. {@code matchedBy} names the path that found it, because a keyword match and a fuzzy match are not
   * equally trustworthy and the caller should be able to tell them apart.
   */
  public record SearchHit(UUID pageId, UUID pageVersionId, String spaceKey, String slug, String title, int version,
      int ordinal, String headingPath, String content, double score, String matchedBy) {}

  /**
   * A retrieval bundle sized to a caller's budget.
   *
   * <p>{@code caveat} is part of the response rather than the documentation. This platform's retrieval is lexical:
   * a question phrased with different words than the document will not match. A caller assembling a prompt needs to
   * know that in the response it is acting on, not in a guide it may never read.
   */
  public record ContextBundle(String query, String strategy, int budgetChars, int usedChars, int consideredChunks,
      List<SearchHit> chunks, List<String> citations, String caveat) {}
}
