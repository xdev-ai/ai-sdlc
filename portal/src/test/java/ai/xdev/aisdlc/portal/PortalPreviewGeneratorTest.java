package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Renders the portal's pages to {@code target/preview/} so the design can be looked at.
 *
 * <p>Why this exists. The data-bearing screens live behind a Keycloak session, and the screenshot policy in
 * {@code docs/screenshots/README.md} rightly forbids publishing captures of a simulated authentication state. But
 * being unable to *publish* a capture is not a reason to write CSS blind: these files are a development aid, written
 * under {@code target/}, never committed, and never presented as runtime evidence.
 *
 * <p>The fixtures use the key sets the real API returns, for the same reason the render tests do — a preview built
 * from invented shapes would show a page that cannot exist.
 */
class PortalPreviewGeneratorTest {
  private static final Path OUTPUT = Path.of("target", "preview");

  @Test void writesPreviewsOfTheScreensThatNeedLookingAt() throws IOException {
    Files.createDirectories(OUTPUT);
    // The stylesheet is loaded by absolute path in the template, so the preview is served from a directory that also
    // holds css/ and js/. Copying keeps the preview self-contained rather than depending on a running portal.
    copyStatic("css/portal.css");
    copyStatic("css/locale.css");

    Map<String, Long> written = new LinkedHashMap<>();
    written.put("overview.html", write("overview.html", overviewModel()));
    written.put("knowledge.html", write("knowledge.html", knowledgeModel()));
    written.put("knowledge-search.html", write("knowledge-search.html", searchModel()));
    written.put("projects.html", write("projects.html", projectsModel()));
    written.put("traceability.html", write("traceability.html", traceabilityModel()));

    written.forEach((name, size) ->
        assertTrue(size > 4_000, name + " rendered only " + size + " bytes, which cannot be a full page"));
    System.out.println("preview written to " + OUTPUT.toAbsolutePath() + " -> " + written);
  }

  private static void copyStatic(String relative) throws IOException {
    Path source = Path.of("src", "main", "resources", "static", relative);
    Path target = OUTPUT.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  private static long write(String name, Map<String, Object> model) throws IOException {
    String html = render(model);
    Path target = OUTPUT.resolve(name);
    Files.writeString(target, html, StandardCharsets.UTF_8);
    return Files.size(target);
  }

  // --- fixtures -------------------------------------------------------------------------------------------------

  private static Map<String, Object> step(int number, String title, String hint, boolean done, String href) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("number", number);
    entry.put("title", title);
    entry.put("hint", hint);
    entry.put("done", done);
    entry.put("href", href);
    return entry;
  }

  private static Map<String, Object> overviewModel() {
    Map<String, Object> model = new HashMap<>();
    model.put("page", "overview");
    model.put("setupSteps", List.of(
        step(1, "Tạo tổ chức", "Gốc của mọi dữ liệu. Form nằm ở trang Projects.", true, "/app/projects"),
        step(2, "Chọn phạm vi tổ chức và dự án", "Thanh chọn ở đầu trang. Chưa chọn thì hầu hết màn hình sẽ trống.", true, ""),
        step(3, "Tạo dự án", "Mỗi dự án có bằng chứng và thành viên riêng.", true, "/app/projects"),
        step(4, "Mời thành viên", "Cần Keycloak subject của người đó, không phải email.", true, "/app/projects"),
        step(5, "Đăng ký Spec Kit", "Phải tự dán manifest JSON.", true, "/app/kits"),
        step(6, "Ghim Spec Kit vào dự án", "Không ghim thì xác thực không chạy.", false, "/app/kits"),
        step(7, "Ban hành và kích hoạt Constitution", "Tạo xong chưa có hiệu lực; phải bấm Activate.", false, "/app/governance"),
        step(8, "Ghi và kích hoạt Policy", "Cũng phải Activate riêng.", false, "/app/governance"),
        step(9, "Nạp tài liệu dự án", "Kho tài liệu để AI đọc và trích dẫn được.", true, "/app/knowledge"),
        step(10, "Bằng chứng xác thực chỉ vào bằng CLI",
            "Không có nút nào trên giao diện tạo lần chạy xác thực. Chạy: aisdlc init → validate → sync.", false, "")));
    model.put("setupDone", 6);
    model.put("setupTotal", 10);
    model.put("auditVerification", Map.of("intact", true, "verifiedEvents", 128));
    return model;
  }

  private static Map<String, Object> node(String id, String slug, String title, int depth, int version) {
    Map<String, Object> node = new HashMap<>();
    node.put("id", id);
    node.put("parentPageId", null);
    node.put("slug", slug);
    node.put("title", title);
    node.put("currentVersion", version);
    node.put("pageStatus", "PUBLISHED");
    node.put("position", 0);
    node.put("depth", depth);
    node.put("updatedAt", "2026-08-17T10:00:00Z");
    return node;
  }

  private static Map<String, Object> space(String key, String name, int pages) {
    Map<String, Object> space = new HashMap<>();
    space.put("id", "11111111-1111-1111-1111-11111111111" + key.length());
    space.put("organizationId", "22222222-2222-2222-2222-222222222222");
    space.put("projectId", null);
    space.put("spaceKey", key);
    space.put("name", name);
    space.put("description", null);
    space.put("createdBy", "platform-admin");
    space.put("createdAt", "2026-08-17T09:00:00Z");
    space.put("archivedAt", null);
    space.put("pageCount", pages);
    return space;
  }

  private static Map<String, Object> knowledgeBase() {
    Map<String, Object> model = new HashMap<>();
    model.put("page", "knowledge");
    model.put("spaces", List.of(space("PRJDOCS", "Tài liệu dự án", 15), space("RUNBOOKS", "Quy trình vận hành", 6)));
    model.put("spaceId", "11111111-1111-1111-1111-111111111117");
    model.put("docTree", List.of(
        node("aaa", "tong-quan", "Tổng quan hệ thống", 0, 3),
        node("bbb", "tiep-nhan", "Tiếp nhận người bệnh", 0, 2),
        node("ccc", "tiep-nhan-noi-tru", "Tiếp nhận nội trú", 1, 1),
        node("ddd", "kiem-tra-bao-hiem", "Kiểm tra bảo hiểm y tế", 1, 4),
        node("eee", "bao-cao", "Báo cáo và thống kê", 0, 1)));
    model.put("docId", "bbb");
    return model;
  }

  private static Map<String, Object> knowledgeModel() {
    Map<String, Object> model = knowledgeBase();
    Map<String, Object> document = new HashMap<>();
    document.put("id", "bbb");
    document.put("spaceId", "11111111-1111-1111-1111-111111111117");
    document.put("spaceKey", "PRJDOCS");
    document.put("slug", "tiep-nhan");
    document.put("title", "Tiếp nhận người bệnh");
    document.put("body", """
        # Tiếp nhận người bệnh

        Nhân viên tiếp nhận kiểm tra giấy tờ tùy thân trước khi lập hồ sơ.

        ## Kiểm tra bảo hiểm

        Xác minh thẻ bảo hiểm y tế và tra cứu cổng thông tin. Trường hợp thẻ hết hạn thì chuyển sang luồng thu phí
        trực tiếp và ghi rõ lý do vào hồ sơ.

        ## Lập hồ sơ

        - Nhập thông tin hành chính
        - Gán mã người bệnh
        - In phiếu tiếp nhận
        """);
    document.put("version", 2);
    document.put("pageStatus", "PUBLISHED");
    document.put("breadcrumb", List.of("tiep-nhan"));
    document.put("labels", List.of("quy-trinh", "tiep-nhan"));
    document.put("references", List.of(Map.of("id", "r1", "targetType", "SPEC_KIT", "targetId", "k1",
        "targetLabel", "core-kit 1.2.0", "referenceNote", "kit mô tả luồng này",
        "linkedBy", "platform-admin", "linkedAt", "2026-08-17T10:30:00Z")));
    document.put("authoredBy", "platform-admin");
    document.put("authoredAt", "2026-08-17T10:15:00Z");
    document.put("bodySha256", "a".repeat(64));
    document.put("changeNote", "bỏ yêu cầu cũ, thêm tra cứu cổng thông tin");
    document.put("chunkCount", 4);
    model.put("document", document);
    model.put("docHistory", List.of(
        versionRow(2, true, "bỏ yêu cầu cũ, thêm tra cứu cổng thông tin", 512),
        versionRow(1, false, "bản đầu tiên", 388)));
    model.put("searchQuery", "");
    model.put("searchHits", List.of());
    return model;
  }

  private static Map<String, Object> versionRow(int number, boolean current, String note, int chars) {
    Map<String, Object> row = new HashMap<>();
    row.put("version", number);
    row.put("title", "Tiếp nhận người bệnh");
    row.put("changeNote", note);
    row.put("authoredBy", "platform-admin");
    row.put("authoredAt", "2026-08-17T10:15:00Z");
    row.put("bodySha256", "b".repeat(64));
    row.put("bodyChars", chars);
    row.put("current", current);
    return row;
  }

  private static Map<String, Object> hit(String heading, String content, String matchedBy) {
    Map<String, Object> hit = new HashMap<>();
    hit.put("pageId", "bbb");
    hit.put("pageVersionId", "v1");
    hit.put("spaceKey", "PRJDOCS");
    hit.put("slug", "tiep-nhan");
    hit.put("title", "Tiếp nhận người bệnh");
    hit.put("version", 2);
    hit.put("ordinal", 1);
    hit.put("headingPath", heading);
    hit.put("content", content);
    hit.put("score", 0.0991);
    hit.put("matchedBy", matchedBy);
    return hit;
  }

  private static Map<String, Object> searchModel() {
    Map<String, Object> model = knowledgeBase();
    model.put("document", Map.of());
    model.put("docHistory", List.of());
    model.put("searchQuery", "bao hiem");
    model.put("searchHits", List.of(
        hit("Tiếp nhận người bệnh > Kiểm tra bảo hiểm",
            "Xác minh thẻ bảo hiểm y tế và tra cứu cổng thông tin. Trường hợp thẻ hết hạn thì chuyển sang luồng thu phí trực tiếp.", "keyword"),
        hit("Kiểm tra bảo hiểm y tế > Trường hợp ngoại lệ",
            "Thẻ bảo hiểm sai thông tin hành chính thì lập biên bản và gửi về cơ quan phát hành.", "keyword"),
        hit("Báo cáo và thống kê > Bảo hiểm",
            "Thống kê số lượt khám có bảo hiểm theo tháng, đối chiếu với dữ liệu thanh toán.", "keyword")));
    return model;
  }

  private static Map<String, Object> projectsModel() {
    Map<String, Object> model = new HashMap<>();
    model.put("page", "projects");
    model.put("projects", List.of(
        Map.of("id", "p1", "name", "Acceptance Project", "slug", "acceptance", "status", "ACTIVE"),
        Map.of("id", "p2", "name", "Hồ sơ sức khoẻ", "slug", "ho-so-suc-khoe", "status", "ACTIVE")));
    model.put("memberships", List.of(
        Map.of("id", "m1", "subject", "b81f0d28-a37a-49cb-939b-830396c058c5", "role", "OWNER", "createdAt", "2026-08-10T08:00:00Z"),
        Map.of("id", "m2", "subject", "c92a1e39-b48b-4ade-a4ac-941407d169e6", "role", "DEVELOPER", "createdAt", "2026-08-12T09:30:00Z")));
    model.put("projectsPage", Map.of("page", 0, "totalPages", 1));
    return model;
  }

  private static Map<String, Object> traceNode(String id, String type, String key, String label) {
    Map<String, Object> node = new HashMap<>();
    node.put("id", id);
    node.put("nodeType", type);
    node.put("externalKey", key);
    node.put("label", label);
    node.put("status", "ACTIVE");
    node.put("createdAt", "2026-08-18T04:00:00Z");
    return node;
  }

  /** The traceability screen with enough nodes that both write forms are offered. */
  private static Map<String, Object> traceabilityModel() {
    Map<String, Object> model = new HashMap<>();
    model.put("page", "traceability");
    model.put("trace", Map.of(
        "nodes", List.of(
            traceNode("n1", "REQUIREMENT", "REQ-1", "Lưu hồ sơ theo mã người bệnh"),
            traceNode("n2", "SPEC", "SPEC-1", "Đặc tả lưu hồ sơ"),
            traceNode("n3", "TEST", "TEST-1", "Kiểm thử lưu hồ sơ")),
        "edges", List.of(Map.of("id", "e1", "sourceNodeId", "n1", "targetNodeId", "n2", "relation", "SPECIFIED_BY"))));
    return model;
  }

  // --- rendering ------------------------------------------------------------------------------------------------

  private static String render(Map<String, Object> overrides) {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    Map<String, Object> variables = new HashMap<>();
    variables.put("page", "overview");
    variables.put("userName", "Duy Trần");
    variables.put("keycloakSessionStatus", "CONNECTED");
    variables.put("_csrf", Map.of("parameterName", "_csrf", "token", "preview", "headerName", "X-CSRF-TOKEN"));
    for (String empty : List.of("organizations", "projects", "memberships", "kits", "projectKits", "validations",
        "policies", "constitutions", "exceptions", "reviews", "audit", "metrics", "apiErrors", "evidenceAssets",
        "spaces", "docTree", "docHistory", "searchHits", "capabilities", "scmEvents", "scmRepositories",
        "notificationChannels", "notificationDeliveries", "approvals", "securityExceptions", "sboms", "provenance",
        "policyBundles", "agentPromptTemplates", "agentSessions", "agentEvidence", "riskScores")) {
      variables.put(empty, List.of());
    }
    variables.put("organizations", List.of(Map.of("id", "o1", "name", "XDev AI", "slug", "xdev-ai")));
    variables.put("organizationId", "o1");
    variables.put("projectId", "p1");
    variables.put("filter", "");
    variables.put("pageNumber", 0);
    variables.put("selectedRunId", "");
    variables.put("reactEntry", "");
    variables.put("validationsJson", "[]");
    variables.put("traceJson", "{\"nodes\":[],\"edges\":[]}");
    variables.put("reviewIslandJson", "{\"reviews\":[],\"exceptions\":[]}");
    variables.put("metricsJson", "[]");
    variables.put("trace", Map.of("nodes", List.of(), "edges", List.of()));
    variables.put("auditVerification", Map.of());
    variables.put("document", Map.of());
    variables.put("spaceId", "");
    variables.put("docId", "");
    variables.put("searchQuery", "");
    variables.put("setupSteps", List.of());
    variables.put("setupDone", 0);
    variables.put("setupTotal", 10);
    for (String paged : List.of("projectsPage", "validationsPage", "auditPage", "evidenceAssetsPage")) {
      variables.put(paged, Map.of("page", 0, "totalPages", 0));
    }
    variables.putAll(overrides);
    Object document = variables.get("document");
    variables.put("documentHtml", document instanceof Map<?, ?> map && map.get("body") != null
        ? MarkdownToSafeHtml.render(map.get("body").toString()) : "");

    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(
        application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);
    return engine.process("app", context);
  }
}
