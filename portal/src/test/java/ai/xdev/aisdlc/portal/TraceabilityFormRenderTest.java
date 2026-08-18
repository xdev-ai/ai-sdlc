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
 * The traceability graph was read-only in the portal while the API had accepted writes all along, so the screen said
 * "awaiting governed links" and offered no way to add one. These tests cover the forms that close that gap, and the
 * states in which they must not appear.
 *
 * <p>The node fixtures use snake_case keys on purpose: {@code /traceability} returns raw column names
 * ({@code node_type}, {@code external_key}) rather than camelCase, unlike most of the API. A fixture that guessed
 * camelCase would render blank option labels while passing.
 */
class TraceabilityFormRenderTest {
  private static Map<String, Object> node(String id, String type, String key, String label) {
    Map<String, Object> node = new HashMap<>();
    node.put("id", id);
    node.put("node_type", type);
    node.put("external_key", key);
    node.put("label", label);
    node.put("status", "ACTIVE");
    node.put("created_at", "2026-08-18T04:00:00Z");
    return node;
  }

  @Test void aProjectWithTwoNodesOffersBothTheNodeFormAndTheEdgeForm() {
    String html = render("p1", List.of(
        node("n1", "REQUIREMENT", "REQ-1", "Lưu hồ sơ theo mã người bệnh"),
        node("n2", "TEST", "TEST-1", "Kiểm thử lưu hồ sơ")), List.of(Map.of("id", "e1")));

    assertTrue(html.contains("/app/projects/p1/trace/nodes"), "the node form must post to the node endpoint");
    assertTrue(html.contains("/app/projects/p1/trace/edges"), "the edge form must post to the edge endpoint");
    assertTrue(html.contains("Thêm nút") && html.contains("Nối liên kết"), "both actions must be offered");
    // Every value the API's enum accepts, or a type cannot be created from the UI at all.
    for (String type : List.of("REQUIREMENT", "SPEC", "TASK", "TEST", "EVIDENCE")) {
      assertTrue(html.contains("value=\"" + type + "\""), "missing node type " + type);
    }
    // The picker has to identify a node, which is what the snake_case keys are for.
    assertTrue(html.contains("REQUIREMENT · REQ-1 — Lưu hồ sơ theo mã người bệnh"),
        "the edge picker must label nodes so a person can tell them apart");
    assertTrue(html.contains("TEST · TEST-1 — Kiểm thử lưu hồ sơ"), html.contains("TEST-1") ? "label format changed" : "second node missing");
  }

  /** A write form without a CSRF token is a form that fails on submit. */
  @Test void bothFormsCarryTheCsrfToken() {
    String html = render("p1", List.of(node("n1", "SPEC", "SPEC-1", "Đặc tả"), node("n2", "TASK", "T-1", "Việc")), List.of());

    int forms = html.split("/trace/", -1).length - 1;
    int tokens = html.split("value=\"test-token\"", -1).length - 1;
    assertTrue(forms >= 2, "expected both trace forms, found " + forms);
    assertTrue(tokens >= forms, "every trace form needs a CSRF token: " + forms + " forms, " + tokens + " tokens");
  }

  /** One node cannot be linked to anything, so offering the edge form would only produce a rejected request. */
  @Test void theEdgeFormIsWithheldUntilThereAreTwoNodes() {
    String html = render("p1", List.of(node("n1", "REQUIREMENT", "REQ-1", "Chỉ một nút")), List.of());

    assertTrue(html.contains("/app/projects/p1/trace/nodes"), "a node can still be added");
    assertFalse(html.contains("/app/projects/p1/trace/edges"), "the edge form must not be offered with one node");
    assertFalse(html.contains("Nối liên kết"), "nor its button");
  }

  /** Without a project every write would be unscoped, so the screen explains instead of offering a broken form. */
  @Test void noSelectedProjectOffersNoFormAndSaysWhy() {
    String html = render("", List.of(), List.of());

    assertFalse(html.contains("/trace/nodes"), "no project means no node form");
    assertFalse(html.contains("/trace/edges"), "and no edge form");
    assertTrue(html.contains("Chọn một dự án ở thanh trên"), "the reason must be on the screen");
  }

  private static String render(String projectId, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    Map<String, Object> variables = new HashMap<>();
    variables.put("page", "traceability");
    variables.put("userName", "Administrator");
    variables.put("keycloakSessionStatus", "CONNECTED");
    variables.put("_csrf", Map.of("parameterName", "_csrf", "token", "test-token", "headerName", "X-CSRF-TOKEN"));
    for (String empty : List.of("organizations", "projects", "memberships", "kits", "projectKits", "validations",
        "policies", "constitutions", "exceptions", "reviews", "audit", "metrics", "apiErrors", "evidenceAssets")) {
      variables.put(empty, List.of());
    }
    variables.put("organizationId", "o1");
    variables.put("projectId", projectId);
    variables.put("filter", "");
    variables.put("pageNumber", 0);
    variables.put("selectedRunId", "");
    variables.put("reactEntry", "");
    variables.put("validationsJson", "[]");
    variables.put("reviewIslandJson", "{\"reviews\":[],\"exceptions\":[]}");
    variables.put("metricsJson", "[]");
    variables.put("auditVerification", Map.of());
    variables.put("setupSteps", List.of());
    variables.put("setupDone", 0);
    variables.put("setupTotal", 10);
    variables.put("trace", Map.of("nodes", nodes, "edges", edges));
    variables.put("traceJson", "{\"nodes\":[],\"edges\":[]}");
    for (String paged : List.of("projectsPage", "validationsPage", "auditPage", "evidenceAssetsPage")) {
      variables.put(paged, Map.of("page", 0, "totalPages", 0));
    }

    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(
        application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);
    return engine.process("app", context);
  }
}
