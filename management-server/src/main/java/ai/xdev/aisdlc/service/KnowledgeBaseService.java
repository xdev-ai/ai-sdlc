package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.web.KnowledgeBaseContracts.*;
import ai.xdev.aisdlc.web.PageRequests;
import ai.xdev.aisdlc.web.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Confluence-shaped documentation repository, kept in the governed database so an AI can read project
 * documentation and cite where an answer came from.
 *
 * <p>Three properties drive the design.
 *
 * <p><b>Every edit is a new version.</b> There is no update-in-place for page text, enforced by triggers rather than
 * by this class, because the reason to keep documentation in a governed system instead of a shared drive is that the
 * previous wording stays retrievable and attributable. Authoring takes a row lock on the page: two people saving at
 * once would otherwise compute the same next version number, and the winner would silently overwrite nothing while
 * the loser hit a constraint they could not interpret.
 *
 * <p><b>Retrieval reads only the current version.</b> Superseded wording stays in the table for audit but is
 * excluded from search, because a model that retrieves the paragraph a document deliberately replaced will answer
 * with the version someone decided was wrong.
 *
 * <p><b>Retrieval is lexical, and the API says so.</b> pgvector is unavailable in this deployment, so there are no
 * embeddings and no similarity search — there is accent-folded keyword matching plus a trigram fallback for typos.
 * A question worded differently from the document will not match. That limitation travels in the response
 * ({@code ContextBundle.caveat}) rather than living only in a guide, because the caller assembling a prompt is the
 * one who needs it.
 */
@Service
public class KnowledgeBaseService {
  /**
   * How many chunks the ranked query returns before the budget filter runs. Enough that a budget can be filled from
   * genuinely ranked candidates; small enough that one request cannot pull a whole space into memory.
   */
  static final int CANDIDATE_LIMIT = 60;

  static final int DEFAULT_BUDGET_CHARS = 8_000;
  static final int MAX_BUDGET_CHARS = 120_000;

  static final String LEXICAL_CAVEAT =
      "Retrieval is lexical (accent-folded keyword matching plus a trigram fallback), not semantic: this deployment "
          + "has no embedding index, so a question worded differently from the document will not match. Treat an "
          + "empty result as 'no wording matched', not as 'the documentation does not cover it'.";

  private static final String CHUNK_SOURCE = """
      from knowledge_chunks c
      join knowledge_page_versions v on v.id = c.page_version_id
      join knowledge_pages p on p.id = v.page_id
      join knowledge_spaces s on s.id = p.space_id
      """;

  /** Superseded versions stay for audit; only the live one is retrievable. */
  private static final String LIVE_SCOPE = """
      where s.organization_id = ?
        and s.archived_at is null
        and p.page_status <> 'ARCHIVED'
        and v.version = p.current_version
      """;

  private final JdbcTemplate jdbc;
  private final AuditService audit;
  private final MarkdownChunker chunker;
  private final ObjectMapper mapper;

  public KnowledgeBaseService(JdbcTemplate jdbc, AuditService audit, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.mapper = mapper;
    this.chunker = new MarkdownChunker();
  }

  // --- spaces ------------------------------------------------------------------------------------------------

  @Transactional
  public SpaceView createSpace(UUID organizationId, String actor, CreateSpaceRequest request) {
    UUID tenantId = requireOrganization(organizationId);
    if (request.projectId() != null) {
      Integer owned = jdbc.queryForObject("select count(*) from projects where id = ? and organization_id = ?",
          Integer.class, request.projectId(), organizationId);
      if (owned == null || owned != 1) throw new IllegalArgumentException("Project does not belong to this organization");
    }
    Integer taken = jdbc.queryForObject("select count(*) from knowledge_spaces where organization_id = ? and space_key = ?",
        Integer.class, organizationId, request.spaceKey());
    if (taken != null && taken > 0) throw new IllegalStateException("A space with key " + request.spaceKey() + " already exists");

    UUID spaceId = UUID.randomUUID();
    jdbc.update("""
        insert into knowledge_spaces(id, tenant_id, organization_id, project_id, space_key, name, description, created_by)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """, spaceId, tenantId, organizationId, request.projectId(), request.spaceKey(), request.name(),
        request.description(), actor);
    audit.append(organizationId, request.projectId(), actor, "knowledge.space.created", "knowledge_space",
        spaceId.toString(), json("spaceKey", request.spaceKey()));
    return space(organizationId, spaceId);
  }

  public PageResponse<SpaceView> listSpaces(UUID organizationId, boolean includeArchived, int page, int size) {
    int offset = PageRequests.offset(page, size);
    String archived = includeArchived ? "" : " and s.archived_at is null";
    Long total = jdbc.queryForObject(
        "select count(*) from knowledge_spaces s where s.organization_id = ?" + archived, Long.class, organizationId);
    List<SpaceView> spaces = jdbc.query(spaceQuery("where s.organization_id = ?" + archived)
        + " order by s.space_key asc limit ? offset ?", (rs, row) -> spaceView(rs), organizationId, size, offset);
    return PageResponse.of(spaces, page, size, total == null ? 0 : total);
  }

  @Transactional
  public SpaceView archiveSpace(UUID organizationId, UUID spaceId, String actor) {
    SpaceView existing = space(organizationId, spaceId);
    if (existing.archivedAt() != null) throw new IllegalStateException("Space is already archived");
    jdbc.update("update knowledge_spaces set archived_at = now(), archived_by = ? where id = ?", actor, spaceId);
    audit.append(organizationId, existing.projectId(), actor, "knowledge.space.archived", "knowledge_space",
        spaceId.toString(), json("spaceKey", existing.spaceKey()));
    return space(organizationId, spaceId);
  }

  public SpaceView space(UUID organizationId, UUID spaceId) {
    List<SpaceView> found = jdbc.query(spaceQuery("where s.organization_id = ? and s.id = ?"),
        (rs, row) -> spaceView(rs), organizationId, spaceId);
    if (found.isEmpty()) throw new IllegalArgumentException("Knowledge space not found");
    return found.get(0);
  }

  private static String spaceQuery(String where) {
    return """
        select s.id, s.organization_id, s.project_id, s.space_key, s.name, s.description, s.created_by, s.created_at,
               s.archived_at,
               (select count(*) from knowledge_pages p where p.space_id = s.id) as page_count
        from knowledge_spaces s
        """ + where;
  }

  private static SpaceView spaceView(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new SpaceView(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
        rs.getObject("project_id", UUID.class), rs.getString("space_key"), rs.getString("name"),
        rs.getString("description"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("archived_at")), rs.getLong("page_count"));
  }

  // --- pages -------------------------------------------------------------------------------------------------

  @Transactional
  public PageDetail createPage(UUID organizationId, UUID spaceId, String actor, CreatePageRequest request) {
    SpaceView space = space(organizationId, spaceId);
    if (space.archivedAt() != null) throw new IllegalStateException("Space is archived; unarchive it before adding pages");
    Integer taken = jdbc.queryForObject("select count(*) from knowledge_pages where space_id = ? and slug = ?",
        Integer.class, spaceId, request.slug());
    if (taken != null && taken > 0) throw new IllegalStateException("A page with slug " + request.slug() + " already exists in this space");
    if (request.parentPageId() != null) {
      // The trigger enforces this too. Checking here turns a raw SQL exception into a message naming the cause.
      Integer sameSpace = jdbc.queryForObject("select count(*) from knowledge_pages where id = ? and space_id = ?",
          Integer.class, request.parentPageId(), spaceId);
      if (sameSpace == null || sameSpace != 1) throw new IllegalArgumentException("Parent page is not in this space");
    }

    UUID pageId = UUID.randomUUID();
    Integer nextPosition = jdbc.queryForObject("""
        select coalesce(max(position), -1) + 1 from knowledge_pages
        where space_id = ? and parent_page_id is not distinct from ?
        """, Integer.class, spaceId, request.parentPageId());
    jdbc.update("""
        insert into knowledge_pages(id, tenant_id, space_id, parent_page_id, slug, position, created_by)
        values (?, ?, ?, ?, ?, ?, ?)
        """, pageId, tenantOf(spaceId), spaceId, request.parentPageId(), request.slug(),
        nextPosition == null ? 0 : nextPosition, actor);

    writeVersion(organizationId, space, pageId, 1, actor, request.title(), request.body(), request.changeNote());
    if (request.labels() != null) {
      for (String label : request.labels().stream().filter(Objects::nonNull).map(String::strip).distinct().toList()) {
        if (!label.isEmpty()) applyLabelRow(pageId, label, actor);
      }
    }
    audit.append(organizationId, space.projectId(), actor, "knowledge.page.created", "knowledge_page",
        pageId.toString(), json("spaceKey", space.spaceKey(), "slug", request.slug()));
    return page(organizationId, pageId);
  }

  /**
   * Authors a new version and rebuilds the chunks that ground AI answers.
   *
   * <p>The page row is locked first. Without it, two concurrent saves both read {@code current_version = 4}, both
   * try to write version 5, and the one that loses reports a unique-constraint violation instead of simply queuing
   * behind the other and becoming version 6.
   *
   * <p>An edit that changes nothing is refused rather than recorded. A history where half the versions are identical
   * is a history nobody reads, and the digest makes the check exact.
   */
  @Transactional
  public PageDetail authorVersion(UUID organizationId, UUID pageId, String actor, AuthorVersionRequest request) {
    PageRow row = requirePage(organizationId, pageId);
    SpaceView space = space(organizationId, row.spaceId());
    if (space.archivedAt() != null) throw new IllegalStateException("Space is archived; unarchive it before editing pages");

    Integer current = jdbc.queryForObject("select current_version from knowledge_pages where id = ? for update",
        Integer.class, pageId);
    int currentVersion = current == null ? 0 : current;
    String digest = sha256(request.body());
    if (currentVersion > 0) {
      Map<String, Object> live = jdbc.queryForMap(
          "select title, body_sha256 from knowledge_page_versions where page_id = ? and version = ?", pageId, currentVersion);
      if (digest.equals(live.get("body_sha256")) && request.title().equals(live.get("title"))) {
        throw new IllegalStateException("This version is identical to version " + currentVersion + "; nothing to author");
      }
    }

    int nextVersion = currentVersion + 1;
    writeVersion(organizationId, space, pageId, nextVersion, actor, request.title(), request.body(), request.changeNote());
    audit.append(organizationId, space.projectId(), actor, "knowledge.page.version_authored", "knowledge_page",
        pageId.toString(), json("version", String.valueOf(nextVersion), "sha256", digest,
            "changeNote", request.changeNote() == null ? "" : request.changeNote()));
    return page(organizationId, pageId);
  }

  /** Writes one version plus its chunks and points the page at it. Shared by creation and editing. */
  private void writeVersion(UUID organizationId, SpaceView space, UUID pageId, int version, String actor,
      String title, String body, String changeNote) {
    UUID tenantId = tenantOf(space.id());
    UUID versionId = UUID.randomUUID();
    jdbc.update("""
        insert into knowledge_page_versions(id, tenant_id, page_id, version, title, body, body_sha256, change_note, authored_by)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, versionId, tenantId, pageId, version, title, body, sha256(body), changeNote, actor);

    List<MarkdownChunker.Chunk> chunks = chunker.chunk(title, body);
    for (MarkdownChunker.Chunk chunk : chunks) {
      jdbc.update("""
          insert into knowledge_chunks(id, tenant_id, page_version_id, ordinal, heading_path, content, content_sha256)
          values (?, ?, ?, ?, ?, ?, ?)
          """, UUID.randomUUID(), tenantId, versionId, chunk.ordinal(), chunk.headingPath(), chunk.content(),
          sha256(chunk.content()));
    }
    jdbc.update("update knowledge_pages set current_version = ? where id = ?", version, pageId);
  }

  /**
   * The space tree, ordered so a client can render it without sorting: parents before children, siblings by position.
   *
   * <p>The archived filter is applied inside the recursion, not to the finished rows. Filtering at the end would
   * drop an archived parent while keeping its children, and those children would arrive pointing at a
   * {@code parentPageId} not present in the response — a tree the client cannot draw.
   *
   * <p>{@code depth < 100} matches the limit the cycle trigger enforces. The trigger makes a loop impossible; this
   * bound means that if one ever exists, the query returns rather than running until the connection dies.
   */
  public List<PageNode> pageTree(UUID organizationId, UUID spaceId, boolean includeArchived) {
    space(organizationId, spaceId);
    return jdbc.query("""
        with recursive tree as (
          select p.id, p.parent_page_id, p.slug, p.current_version, p.page_status, p.position, 0 as depth,
                 array[p.position] as ordering
          from knowledge_pages p
          where p.space_id = ? and p.parent_page_id is null
            and (cast(? as boolean) or p.page_status <> 'ARCHIVED')
          union all
          select c.id, c.parent_page_id, c.slug, c.current_version, c.page_status, c.position, t.depth + 1,
                 t.ordering || c.position
          from knowledge_pages c join tree t on c.parent_page_id = t.id
          where t.depth < 100
            and (cast(? as boolean) or c.page_status <> 'ARCHIVED')
        )
        select t.id, t.parent_page_id, t.slug, t.current_version, t.page_status, t.position, t.depth,
               v.title, v.authored_at
        from tree t
        left join knowledge_page_versions v on v.page_id = t.id and v.version = t.current_version
        order by t.ordering, t.slug
        """, (rs, index) -> new PageNode(rs.getObject("id", UUID.class), rs.getObject("parent_page_id", UUID.class),
            rs.getString("slug"), rs.getString("title"), rs.getInt("current_version"), rs.getString("page_status"),
            rs.getInt("position"), rs.getInt("depth"), instant(rs.getTimestamp("authored_at"))),
        spaceId, includeArchived, includeArchived);
  }

  public PageDetail page(UUID organizationId, UUID pageId) {
    PageRow row = requirePage(organizationId, pageId);
    if (row.currentVersion() < 1) throw new IllegalStateException("Page has no version yet");
    return pageAtVersion(organizationId, pageId, row.currentVersion());
  }

  public PageDetail pageAtVersion(UUID organizationId, UUID pageId, int version) {
    requirePage(organizationId, pageId); // scope check: a page in another organization must not be readable by id
    List<PageDetail> found = jdbc.query("""
        select p.id, p.space_id, s.space_key, p.slug, p.page_status, v.id as version_id, v.version, v.title, v.body,
               v.body_sha256, v.change_note, v.authored_by, v.authored_at,
               (select count(*) from knowledge_chunks c where c.page_version_id = v.id) as chunk_count
        from knowledge_pages p
        join knowledge_spaces s on s.id = p.space_id
        join knowledge_page_versions v on v.page_id = p.id and v.version = ?
        where p.id = ?
        """, (rs, index) -> new PageDetail(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
            rs.getString("space_key"), rs.getString("slug"), rs.getString("title"), rs.getString("body"),
            rs.getInt("version"), rs.getString("page_status"), List.of(), List.of(), List.of(),
            rs.getString("authored_by"), instant(rs.getTimestamp("authored_at")), rs.getString("body_sha256"),
            rs.getString("change_note"), rs.getInt("chunk_count")),
        version, pageId);
    if (found.isEmpty()) throw new IllegalArgumentException("Page version " + version + " not found");
    PageDetail detail = found.get(0);
    return new PageDetail(detail.id(), detail.spaceId(), detail.spaceKey(), detail.slug(), detail.title(),
        detail.body(), detail.version(), detail.pageStatus(), breadcrumb(pageId), labels(pageId),
        references(organizationId, pageId), detail.authoredBy(), detail.authoredAt(), detail.bodySha256(),
        detail.changeNote(), detail.chunkCount());
  }

  public PageResponse<VersionSummary> versions(UUID organizationId, UUID pageId, int page, int size) {
    PageRow row = requirePage(organizationId, pageId);
    int offset = PageRequests.offset(page, size);
    Long total = jdbc.queryForObject("select count(*) from knowledge_page_versions where page_id = ?", Long.class, pageId);
    List<VersionSummary> summaries = jdbc.query("""
        select version, title, change_note, authored_by, authored_at, body_sha256, length(body) as body_chars
        from knowledge_page_versions where page_id = ? order by version desc limit ? offset ?
        """, (rs, index) -> new VersionSummary(rs.getInt("version"), rs.getString("title"), rs.getString("change_note"),
            rs.getString("authored_by"), instant(rs.getTimestamp("authored_at")), rs.getString("body_sha256"),
            rs.getInt("body_chars"), rs.getInt("version") == row.currentVersion()),
        pageId, size, offset);
    return PageResponse.of(summaries, page, size, total == null ? 0 : total);
  }

  @Transactional
  public PageDetail movePage(UUID organizationId, UUID pageId, String actor, MovePageRequest request) {
    PageRow row = requirePage(organizationId, pageId);
    if (request.parentPageId() != null) {
      Integer sameSpace = jdbc.queryForObject("select count(*) from knowledge_pages where id = ? and space_id = ?",
          Integer.class, request.parentPageId(), row.spaceId());
      if (sameSpace == null || sameSpace != 1) throw new IllegalArgumentException("Parent page is not in this space");
      if (request.parentPageId().equals(pageId)) throw new IllegalArgumentException("A page cannot be its own parent");
    }
    // The cycle trigger is the real guard. It raises a plain SQL exception, so it is translated here into the
    // 409 a caller can act on rather than the 500 an unhandled DataAccessException becomes.
    try {
      jdbc.update("update knowledge_pages set parent_page_id = ?, position = coalesce(?, position) where id = ?",
          request.parentPageId(), request.position(), pageId);
    } catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.jdbc.UncategorizedSQLException rejected) {
      if (String.valueOf(rejected.getMostSpecificCause().getMessage()).contains("cycle")) {
        throw new IllegalStateException("That move would put the page underneath one of its own descendants");
      }
      throw rejected;
    }
    audit.append(organizationId, null, actor, "knowledge.page.moved", "knowledge_page", pageId.toString(),
        json("parentPageId", String.valueOf(request.parentPageId()), "position", String.valueOf(request.position())));
    return page(organizationId, pageId);
  }

  @Transactional
  public PageDetail setPageStatus(UUID organizationId, UUID pageId, String actor, PageStatusRequest request) {
    PageRow row = requirePage(organizationId, pageId);
    if (row.currentVersion() < 1 && "PUBLISHED".equals(request.pageStatus())) {
      throw new IllegalStateException("A page with no version cannot be published");
    }
    jdbc.update("update knowledge_pages set page_status = ? where id = ?", request.pageStatus(), pageId);
    audit.append(organizationId, null, actor, "knowledge.page.status_changed", "knowledge_page", pageId.toString(),
        json("pageStatus", request.pageStatus()));
    return page(organizationId, pageId);
  }

  // --- labels and references ---------------------------------------------------------------------------------

  @Transactional
  public List<String> applyLabel(UUID organizationId, UUID pageId, String actor, LabelRequest request) {
    requirePage(organizationId, pageId);
    applyLabelRow(pageId, request.label(), actor);
    audit.append(organizationId, null, actor, "knowledge.label.applied", "knowledge_page", pageId.toString(),
        json("label", request.label()));
    return labels(pageId);
  }

  @Transactional
  public List<String> removeLabel(UUID organizationId, UUID pageId, String label, String actor) {
    requirePage(organizationId, pageId);
    int removed = jdbc.update("delete from knowledge_page_labels where page_id = ? and label = ?", pageId, label);
    if (removed != 1) throw new IllegalArgumentException("Label is not applied to this page");
    audit.append(organizationId, null, actor, "knowledge.label.removed", "knowledge_page", pageId.toString(),
        json("label", label));
    return labels(pageId);
  }

  @Transactional
  public List<ReferenceView> linkReference(UUID organizationId, UUID pageId, String actor, LinkReferenceRequest request) {
    PageRow row = requirePage(organizationId, pageId);
    long targets = (request.specKitId() != null ? 1 : 0) + (request.traceNodeId() != null ? 1 : 0)
        + (request.evidenceAssetId() != null ? 1 : 0);
    if (targets != 1) throw new IllegalArgumentException("Exactly one of specKitId, traceNodeId or evidenceAssetId is required");

    // Each target is verified to be inside this organization. Without it a page could cite another customer's
    // artifact by guessing a uuid, and the reference would read as a legitimate governance link.
    if (request.specKitId() != null) {
      requireOwned("select count(*) from spec_kits where id = ? and organization_id = ?", request.specKitId(),
          organizationId, "Spec Kit");
    } else if (request.traceNodeId() != null) {
      requireOwned("""
          select count(*) from trace_nodes n join projects pr on pr.id = n.project_id
          where n.id = ? and pr.organization_id = ?
          """, request.traceNodeId(), organizationId, "Trace node");
    } else {
      requireOwned("""
          select count(*) from evidence_assets e join projects pr on pr.id = e.project_id
          where e.id = ? and pr.organization_id = ?
          """, request.evidenceAssetId(), organizationId, "Evidence asset");
    }

    UUID referenceId = UUID.randomUUID();
    jdbc.update("""
        insert into knowledge_page_references(id, tenant_id, page_id, spec_kit_id, trace_node_id, evidence_asset_id,
                                             reference_note, linked_by)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """, referenceId, row.tenantId(), pageId, request.specKitId(), request.traceNodeId(),
        request.evidenceAssetId(), request.referenceNote(), actor);
    audit.append(organizationId, null, actor, "knowledge.reference.linked", "knowledge_page", pageId.toString(),
        json("referenceId", referenceId.toString()));
    return references(organizationId, pageId);
  }

  private void requireOwned(String sql, UUID id, UUID organizationId, String label) {
    Integer owned = jdbc.queryForObject(sql, Integer.class, id, organizationId);
    if (owned == null || owned != 1) throw new IllegalArgumentException(label + " does not belong to this organization");
  }

  private void applyLabelRow(UUID pageId, String label, String actor) {
    jdbc.update("""
        insert into knowledge_page_labels(page_id, label, applied_by) values (?, ?, ?)
        on conflict (page_id, label) do nothing
        """, pageId, label, actor);
  }

  // --- retrieval ---------------------------------------------------------------------------------------------

  /**
   * Ranked chunk search.
   *
   * <p>The query goes through {@code websearch_to_tsquery}, which accepts whatever a person types: it never raises a
   * syntax error on stray operators, unlike {@code to_tsquery}, so a query of {@code "&&"} returns nothing instead of
   * a {@code 500}. It is accent-folded through the same function used to build the index, so "tiep nhan" matches
   * "tiếp nhận".
   *
   * <p>When keyword matching finds nothing, one trigram pass runs. It uses the {@code <%} word-similarity operator so
   * the GIN index still applies — {@code similarity()} in an {@code ORDER BY} would be a sequential scan — and it
   * gives typo tolerance for a correctly-accented query. It is reported as {@code matchedBy = "trigram"} because a
   * fuzzy match deserves less trust than an exact one.
   */
  public List<SearchHit> search(UUID organizationId, String query, String spaceKey, String label, int limit) {
    if (query == null || query.isBlank()) throw new IllegalArgumentException("A search query is required");
    if (limit < 1 || limit > CANDIDATE_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + CANDIDATE_LIMIT);
    }
    String trimmed = query.strip();

    // Filters are shared by both statements, so their arguments are collected once and spliced into each argument
    // list at the position that statement puts them. Positional binding punishes any drift between the two.
    StringBuilder filters = new StringBuilder();
    List<Object> filterArguments = new ArrayList<>();
    if (spaceKey != null && !spaceKey.isBlank()) {
      filters.append(" and s.space_key = ?");
      filterArguments.add(spaceKey.strip());
    }
    if (label != null && !label.isBlank()) {
      filters.append(" and exists (select 1 from knowledge_page_labels l where l.page_id = p.id and l.label = ?)");
      filterArguments.add(label.strip());
    }

    // cast(? as text): the parameter is the sole argument of a function call, so without the cast PostgreSQL has
    // nothing to infer a type from and refuses the statement outright.
    List<Object> keywordArguments = new ArrayList<>();
    keywordArguments.add(trimmed);
    keywordArguments.add(organizationId);
    keywordArguments.addAll(filterArguments);
    keywordArguments.add(limit);
    List<SearchHit> keyword = jdbc.query("select " + HIT_COLUMNS
        + ", ts_rank(c.search_vector, q.query) as score, 'keyword' as matched_by\n"
        + CHUNK_SOURCE
        + "cross join websearch_to_tsquery('simple', immutable_unaccent(cast(? as text))) as q(query)\n"
        + LIVE_SCOPE + filters
        + " and c.search_vector @@ q.query\n"
        + " order by score desc, p.slug asc, c.ordinal asc limit ?",
        KnowledgeBaseService::hit, keywordArguments.toArray());
    if (!keyword.isEmpty()) return keyword;

    List<Object> trigramArguments = new ArrayList<>();
    trigramArguments.add(trimmed); // the select-list similarity argument comes first in the statement text
    trigramArguments.add(organizationId);
    trigramArguments.addAll(filterArguments);
    trigramArguments.add(trimmed); // and again in the where clause, where the index is applied
    trigramArguments.add(limit);
    return jdbc.query("select " + HIT_COLUMNS
        + ", word_similarity(cast(? as text), c.content) as score, 'trigram' as matched_by\n"
        + CHUNK_SOURCE + LIVE_SCOPE + filters
        + " and cast(? as text) <% c.content\n"
        + " order by score desc, p.slug asc, c.ordinal asc limit ?",
        KnowledgeBaseService::hit, trigramArguments.toArray());
  }

  private static final String HIT_COLUMNS = """
      p.id as page_id, v.id as page_version_id, s.space_key, p.slug, v.title, v.version, c.ordinal, c.heading_path,
      c.content""";

  private static SearchHit hit(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
    return new SearchHit(rs.getObject("page_id", UUID.class), rs.getObject("page_version_id", UUID.class),
        rs.getString("space_key"), rs.getString("slug"), rs.getString("title"), rs.getInt("version"),
        rs.getInt("ordinal"), rs.getString("heading_path"), rs.getString("content"), rs.getDouble("score"),
        rs.getString("matched_by"));
  }

  /**
   * Assembles a prompt-sized bundle of grounded context.
   *
   * <p>Chunks are taken in rank order until the character budget is spent, and a chunk that does not fit is skipped
   * rather than truncated: half a section produces a confident answer built on a sentence that was cut in half. Each
   * chunk arrives with a citation string naming space, page, version and section, so an answer can be checked against
   * the document it came from.
   */
  public ContextBundle assembleContext(UUID organizationId, String query, String spaceKey, String label, int budgetChars) {
    int budget = budgetChars <= 0 ? DEFAULT_BUDGET_CHARS : budgetChars;
    if (budget > MAX_BUDGET_CHARS) throw new IllegalArgumentException("budgetChars must not exceed " + MAX_BUDGET_CHARS);

    List<SearchHit> candidates = search(organizationId, query, spaceKey, label, CANDIDATE_LIMIT);
    List<SearchHit> selected = new ArrayList<>();
    List<String> citations = new ArrayList<>();
    int used = 0;
    for (SearchHit candidate : candidates) {
      int cost = candidate.content().length();
      if (used + cost > budget) continue;
      used += cost;
      selected.add(candidate);
      citations.add("%s/%s v%d § %s".formatted(candidate.spaceKey(), candidate.slug(), candidate.version(),
          candidate.headingPath()));
    }
    String strategy = candidates.isEmpty() ? "none"
        : candidates.get(0).matchedBy().equals("trigram") ? "trigram-fallback" : "lexical-keyword";
    return new ContextBundle(query.strip(), strategy, budget, used, candidates.size(), selected, citations,
        LEXICAL_CAVEAT);
  }

  // --- shared ------------------------------------------------------------------------------------------------

  /** Identity and scope of a page, resolved once so every operation fails the same way on a foreign page. */
  private record PageRow(UUID id, UUID spaceId, UUID tenantId, int currentVersion) {}

  private PageRow requirePage(UUID organizationId, UUID pageId) {
    List<PageRow> found = jdbc.query("""
        select p.id, p.space_id, p.tenant_id, p.current_version
        from knowledge_pages p join knowledge_spaces s on s.id = p.space_id
        where p.id = ? and s.organization_id = ?
        """, (rs, index) -> new PageRow(rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
            rs.getObject("tenant_id", UUID.class), rs.getInt("current_version")), pageId, organizationId);
    if (found.isEmpty()) throw new IllegalArgumentException("Knowledge page not found");
    return found.get(0);
  }

  private UUID requireOrganization(UUID organizationId) {
    List<UUID> tenants = jdbc.queryForList("select tenant_id from organizations where id = ?", UUID.class, organizationId);
    if (tenants.isEmpty()) throw new IllegalArgumentException("Organization not found");
    return tenants.get(0);
  }

  private UUID tenantOf(UUID spaceId) {
    return jdbc.queryForObject("select tenant_id from knowledge_spaces where id = ?", UUID.class, spaceId);
  }

  /** Root first, so a client can render "Space > Parent > Page" without another round trip. */
  private List<String> breadcrumb(UUID pageId) {
    return jdbc.queryForList("""
        with recursive up as (
          select p.id, p.parent_page_id, p.slug, 0 as depth from knowledge_pages p where p.id = ?
          union all
          select parent.id, parent.parent_page_id, parent.slug, up.depth + 1
          from knowledge_pages parent join up on up.parent_page_id = parent.id
          where up.depth < 100
        )
        select slug from up order by depth desc
        """, String.class, pageId);
  }

  private List<String> labels(UUID pageId) {
    return jdbc.queryForList("select label from knowledge_page_labels where page_id = ? order by label", String.class, pageId);
  }

  private List<ReferenceView> references(UUID organizationId, UUID pageId) {
    return jdbc.query("""
        select r.id, r.spec_kit_id, r.trace_node_id, r.evidence_asset_id, r.reference_note, r.linked_by, r.linked_at,
               k.slug as kit_slug, k.version as kit_version, n.external_key as node_key, n.label as node_label,
               e.filename as asset_filename
        from knowledge_page_references r
        left join spec_kits k on k.id = r.spec_kit_id
        left join trace_nodes n on n.id = r.trace_node_id
        left join evidence_assets e on e.id = r.evidence_asset_id
        where r.page_id = ? order by r.linked_at asc
        """, (rs, index) -> {
          UUID kit = rs.getObject("spec_kit_id", UUID.class);
          UUID node = rs.getObject("trace_node_id", UUID.class);
          UUID asset = rs.getObject("evidence_asset_id", UUID.class);
          String type = kit != null ? "SPEC_KIT" : node != null ? "TRACE_NODE" : "EVIDENCE_ASSET";
          UUID target = kit != null ? kit : node != null ? node : asset;
          String targetLabel = kit != null ? rs.getString("kit_slug") + " " + rs.getString("kit_version")
              : node != null ? rs.getString("node_key") + " " + rs.getString("node_label")
              : rs.getString("asset_filename");
          return new ReferenceView(rs.getObject("id", UUID.class), type, target, targetLabel,
              rs.getString("reference_note"), rs.getString("linked_by"), instant(rs.getTimestamp("linked_at")));
        }, pageId);
  }

  private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

  private String json(String... pairs) {
    Map<String, String> payload = new LinkedHashMap<>();
    for (int index = 0; index + 1 < pairs.length; index += 2) payload.put(pairs[index], pairs[index + 1]);
    try {
      return mapper.writeValueAsString(payload);
    } catch (Exception unserializable) {
      // Page titles carry quotes, newlines and Vietnamese punctuation. Hand-built JSON would break the audit ledger
      // hash chain on the first apostrophe, so a failure here refuses the write rather than writing invalid JSON.
      throw new IllegalStateException("Unable to serialise the audit payload", unserializable);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
