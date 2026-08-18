package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * Renders the documentation workspace without a browser, a login, or a database.
 *
 * <p>Every fixture below uses the exact key set the management API returns, read from a live response rather than
 * from memory. That matters more than it sounds: the earlier version of the overview fixture seeded {@code "valid"},
 * a key the API never sends, so the test encoded a bug as the expected contract and {@code /app} returned 500 for
 * every user while its test stayed green. Spring's {@code MapAccessor} reads a property only when the key exists, so
 * a fixture with the wrong keys proves nothing about the real page.
 */
class KnowledgeWorkspaceRenderTest {
  /** Keys as returned by {@code GET .../knowledge/spaces/{id}/pages}. */
  private static Map<String, Object> node(String id, String slug, String title, int depth, int version) {
    Map<String, Object> node = new HashMap<>();
    node.put("id", id);
    node.put("parentPageId", null);
    node.put("slug", slug);
    node.put("title", title); // null in real responses when a page has no version yet: the tree uses a LEFT JOIN
    node.put("currentVersion", version);
    node.put("pageStatus", "DRAFT");
    node.put("position", 0);
    node.put("depth", depth);
    node.put("updatedAt", "2026-08-17T10:00:00Z");
    return node;
  }

  private static Map<String, Object> space() {
    Map<String, Object> space = new HashMap<>();
    space.put("id", "11111111-1111-1111-1111-111111111111");
    space.put("organizationId", "22222222-2222-2222-2222-222222222222");
    space.put("projectId", null);
    space.put("spaceKey", "PRJDOCS");
    space.put("name", "Tài liệu dự án");
    space.put("description", null);
    space.put("createdBy", "platform-admin");
    space.put("createdAt", "2026-08-17T09:00:00Z");
    space.put("archivedAt", null);
    space.put("pageCount", 15);
    return space;
  }

  private static Map<String, Object> document(String body) {
    Map<String, Object> document = new HashMap<>();
    document.put("id", "33333333-3333-3333-3333-333333333333");
    document.put("spaceId", "11111111-1111-1111-1111-111111111111");
    document.put("spaceKey", "PRJDOCS");
    document.put("slug", "tiep-nhan");
    document.put("title", "Tiếp nhận người bệnh");
    document.put("body", body);
    document.put("version", 2);
    document.put("pageStatus", "DRAFT");
    document.put("breadcrumb", List.of("tiep-nhan"));
    document.put("labels", List.of("quy-trinh"));
    document.put("references", List.of(Map.of("id", "44444444-4444-4444-4444-444444444444",
        "targetType", "SPEC_KIT", "targetId", "55555555-5555-5555-5555-555555555555",
        "targetLabel", "core-kit 1.0.0", "referenceNote", "kit này", "linkedBy", "platform-admin",
        "linkedAt", "2026-08-17T10:30:00Z")));
    document.put("authoredBy", "platform-admin");
    document.put("authoredAt", "2026-08-17T10:15:00Z");
    document.put("bodySha256", "a".repeat(64));
    document.put("changeNote", "thêm bước xác minh");
    document.put("chunkCount", 4);
    return document;
  }

  private static Map<String, Object> version(int number, boolean current, String note) {
    Map<String, Object> version = new HashMap<>();
    version.put("version", number);
    version.put("title", "Tiếp nhận người bệnh");
    version.put("changeNote", note);
    version.put("authoredBy", "platform-admin");
    version.put("authoredAt", "2026-08-17T10:15:00Z");
    version.put("bodySha256", "b".repeat(64));
    version.put("bodyChars", 420);
    version.put("current", current);
    return version;
  }

  private static Map<String, Object> hit() {
    Map<String, Object> hit = new HashMap<>();
    hit.put("pageId", "33333333-3333-3333-3333-333333333333");
    hit.put("pageVersionId", "66666666-6666-6666-6666-666666666666");
    hit.put("spaceKey", "PRJDOCS");
    hit.put("slug", "tiep-nhan");
    hit.put("title", "Tiếp nhận người bệnh");
    hit.put("version", 2);
    hit.put("ordinal", 1);
    hit.put("headingPath", "Tiếp nhận người bệnh > Kiểm tra bảo hiểm");
    hit.put("content", "Xác minh thẻ bảo hiểm y tế của người bệnh.");
    hit.put("score", 0.0991);
    hit.put("matchedBy", "keyword");
    return hit;
  }

  @Test void rendersTheTreeTheDocumentAndItsHistory() {
    String html = render(Map.of(
        "spaces", List.of(space()),
        "spaceId", "11111111-1111-1111-1111-111111111111",
        "docTree", List.of(node("33333333-3333-3333-3333-333333333333", "tiep-nhan", "Tiếp nhận người bệnh", 0, 2)),
        "docId", "33333333-3333-3333-3333-333333333333",
        "document", document("# Tiếp nhận người bệnh\n\nNhân viên kiểm tra giấy tờ."),
        "docHistory", List.of(version(2, true, "thêm bước xác minh"), version(1, false, "bản đầu")),
        "searchQuery", "",
        "searchHits", List.of()));

    assertTrue(html.contains("Kho tài liệu dự án"), "the page heading must render for this view");
    assertTrue(html.contains("PRJDOCS"), "the space key identifies which space is open");
    assertTrue(html.contains("15 trang"), "the space must report how many pages it holds");
    assertTrue(html.contains("Tiếp nhận người bệnh"), "the document title must render");
    assertTrue(html.contains("<p>Nhân viên kiểm tra giấy tờ.</p>"),
        "the body must render as prose, not only inside the raw-source block");
    assertTrue(html.contains("<h3>Tiếp nhận người bệnh</h3>"), "a markdown heading must become a real heading");
    assertTrue(html.contains("Xem nguồn Markdown"), "the exact stored source must stay available");
    assertTrue(html.contains("v2 · DRAFT"), "the version and status chip must render");
    assertTrue(html.contains("4 đoạn cho AI"), "a reader should see how many chunks an AI receives");
    assertTrue(html.contains("thêm bước xác minh"), "the reason for the edit must be visible");
    assertTrue(html.contains("quy-trinh"), "labels must render");
    assertTrue(html.contains("core-kit 1.0.0"), "a citation must name the artifact, not just its id");
    assertTrue(html.contains("Lịch sử phiên bản"), "the history panel must render");
    assertTrue(html.contains("bản đầu"), "a superseded version must still be listed");
    assertTrue(html.contains("420 ký tự"), "each version reports its size");
  }

  /**
   * The state a first-time visitor is in: an organization with no spaces yet. {@code ObjectData.empty().value()}
   * puts an <em>empty map</em> in the model, which is exactly the shape that made {@code /app} return 500.
   */
  @Test void rendersBeforeAnySpaceOrDocumentExists() {
    String html = render(Map.of(
        "spaces", List.of(), "spaceId", "", "docTree", List.of(), "docId", "",
        "document", Map.of(), "docHistory", List.of(), "searchQuery", "", "searchHits", List.of()));

    assertTrue(html.contains("Chưa có không gian tài liệu nào."), "the empty state must explain the situation");
    assertTrue(html.contains("workbook-to-pages.py"), "and must name the command that loads documents");
    assertFalse(html.contains("Lịch sử phiên bản"), "there is no history to show yet");
  }

  /** A page created without a version has {@code title: null} in the tree, because the tree LEFT JOINs its version. */
  @Test void aPageWithNoVersionFallsBackToItsSlugInsteadOfPrintingNull() {
    String html = render(Map.of(
        "spaces", List.of(space()), "spaceId", "11111111-1111-1111-1111-111111111111",
        "docTree", List.of(node("77777777-7777-7777-7777-777777777777", "chua-co-noi-dung", null, 0, 0)),
        "docId", "", "document", Map.of(), "docHistory", List.of(),
        "searchQuery", "", "searchHits", List.of()));

    assertTrue(html.contains("chua-co-noi-dung"), "the slug stands in for a missing title");
    assertFalse(html.contains(">null<"), "a null title must never reach the page");
  }

  @Test void searchResultsCarryTheirHeadingPathAndSayHowTheyMatched() {
    String html = render(Map.of(
        "spaces", List.of(space()), "spaceId", "11111111-1111-1111-1111-111111111111",
        "docTree", List.of(), "docId", "", "document", Map.of(), "docHistory", List.of(),
        "searchQuery", "bao hiem", "searchHits", List.of(hit())));

    assertTrue(html.contains("1 đoạn khớp"), "the number of matching chunks must be stated");
    assertTrue(html.contains("Tiếp nhận người bệnh &gt; Kiểm tra bảo hiểm"), "the heading path is the citation");
    assertTrue(html.contains("keyword"), "how it matched must be visible, since a fuzzy match deserves less trust");
    assertTrue(html.contains("không phải tìm theo ngữ nghĩa"),
        "the lexical-only limit belongs on the screen, not only in the docs");
    assertTrue(html.contains("value=\"bao hiem\""), "the query stays in the box so it can be refined");
  }

  @Test void anEmptySearchExplainsThatNothingMatchedRatherThanThatNothingExists() {
    String html = render(Map.of(
        "spaces", List.of(space()), "spaceId", "11111111-1111-1111-1111-111111111111",
        "docTree", List.of(), "docId", "", "document", Map.of(), "docHistory", List.of(),
        "searchQuery", "zzqqxx", "searchHits", List.of()));

    assertTrue(html.contains("Không đoạn nào khớp."), "an empty result needs its own state");
    assertTrue(html.contains("không phải tài liệu không đề cập"),
        "the distinction between 'no wording matched' and 'not covered' must be stated to the reader");
  }

  /**
   * Page bodies are Markdown written by users. The reader renders them as escaped, pre-formatted text; converting
   * Markdown to HTML in the browser would turn every document into stored XSS against everyone who can read the space.
   */
  @Test void aDocumentBodyCannotInjectScriptIntoTheReader() {
    String hostile = "# Title\n<script>window.stolen=document.cookie</script>\n<img src=x onerror=alert(1)>";
    String html = render(Map.of(
        "spaces", List.of(space()), "spaceId", "11111111-1111-1111-1111-111111111111",
        "docTree", List.of(), "docId", "33333333-3333-3333-3333-333333333333",
        "document", document(hostile), "docHistory", List.of(),
        "searchQuery", "", "searchHits", List.of()));

    // What matters is that no *element* or *attribute* is created. The characters "onerror=alert(1)" legitimately
    // survive as escaped text — asserting on that substring alone would be testing the wrong thing, since text inside
    // an escaped node cannot execute no matter what it spells.
    // The page now renders prose through th:utext, so this is the assertion that matters most on the whole screen:
    // a hostile body must still not create an element, in the prose or in the raw-source block.
    assertFalse(html.contains("<script>window.stolen"), "a script tag from a page body must not reach the DOM");
    assertFalse(html.contains("<img src=x"), "nor an img element carrying an inline event handler");
    assertTrue(html.contains("&lt;script&gt;window.stolen"), "the script must appear as escaped text instead");
    assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), "and so must the img, angle brackets included");
  }

  @Test void everyNavigationDestinationSurvivesTheGrouping() {
    String html = render(Map.of(
        "spaces", List.of(), "spaceId", "", "docTree", List.of(), "docId", "",
        "document", Map.of(), "docHistory", List.of(), "searchQuery", "", "searchHits", List.of()));

    for (String destination : List.of("/app/projects", "/app/kits", "/app/validations", "/app/evidence",
        "/app/traceability", "/app/governance", "/app/policy-as-code", "/app/agent-governance",
        "/app/risk-intelligence", "/app/scm", "/app/supply-chain", "/app/reviews", "/app/notifications",
        "/app/quality", "/app/audit", "/app/knowledge")) {
      assertTrue(html.contains("/app" + destination.substring(4)),
          "regrouping the sidebar dropped " + destination);
    }
    assertTrue(html.contains("TÀI LIỆU / DOCUMENTS"), "documents lead the sidebar");
    assertTrue(html.contains("BẰNG CHỨNG / EVIDENCE"), "governance surfaces are grouped, not listed flat");
  }

  /**
   * A fragment guarded by {@code th:if} on the same tag as {@code th:replace} is not guarded at all: {@code th:replace}
   * has the higher precedence, so it is processed first and the condition never runs.
   *
   * <p>This was live. The quality-signals fragment was evaluated on every page, and nothing failed only because the
   * one controller method serving sixteen pages always happened to put {@code metrics} in the model. The first handler
   * that supplied only what its own screen needed turned it into a template exception — which is how it was found.
   */
  @Test void theQualityFragmentDoesNotLeakOntoOtherPages() {
    String html = render(Map.of(
        "spaces", List.of(), "spaceId", "", "docTree", List.of(), "docId", "",
        "document", Map.of(), "docHistory", List.of(), "searchQuery", "", "searchHits", List.of()));

    assertFalse(html.contains("DORA COUNTER-METRICS"),
        "the quality panel rendered on a documentation page, so its th:if guard is still ineffective");
  }

  private static String render(Map<String, Object> knowledge) {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    Map<String, Object> variables = new HashMap<>();
    variables.put("page", "knowledge");
    variables.put("userName", "Administrator");
    variables.put("keycloakSessionStatus", "CONNECTED");
    variables.put("_csrf", Map.of("parameterName", "_csrf", "token", "test-token", "headerName", "X-CSRF-TOKEN"));
    variables.put("organizations", List.of());
    variables.put("projects", List.of());
    variables.put("apiErrors", List.of());
    variables.put("organizationId", "22222222-2222-2222-2222-222222222222");
    variables.put("projectId", "");
    variables.put("filter", "");
    variables.put("pageNumber", 0);
    variables.put("selectedRunId", "");
    variables.put("reactEntry", "");
    variables.put("documentHtml", "");
    variables.putAll(knowledge);
    // The controller derives this from the body; deriving it here too keeps the fixture honest about what the page
    // receives. Seeding an empty string would let a content assertion pass off the raw-source <details> block instead.
    Object document = variables.get("document");
    if (document instanceof Map<?, ?> map && map.get("body") != null) {
      variables.put("documentHtml", MarkdownToSafeHtml.render(map.get("body").toString()));
    }

    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(
        application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);
    return engine.process("app", context);
  }
}
