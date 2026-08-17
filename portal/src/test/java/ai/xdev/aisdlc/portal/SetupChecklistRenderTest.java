package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The overview now answers "what do I do first" instead of showing a slogan and four counters.
 *
 * <p>The existing overview test kept passing when the checklist was added, because it never seeded the checklist
 * attributes and Thymeleaf renders a missing number as {@code null} rather than failing. A passing test that does not
 * supply what the page reads proves only that the page did not crash, so the state is seeded explicitly here.
 */
class SetupChecklistRenderTest {
  private static Map<String, Object> step(int number, String title, String hint, boolean done, String href) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("number", number);
    entry.put("title", title);
    entry.put("hint", hint);
    entry.put("done", done);
    entry.put("href", href);
    return entry;
  }

  /** Mirrors what {@code PortalController.setupChecklist} produces partway through setup. */
  private static List<Map<String, Object>> partiallyComplete() {
    return List.of(
        step(1, "Tạo tổ chức", "Gốc của mọi dữ liệu.", true, "/app/projects"),
        step(2, "Chọn phạm vi tổ chức và dự án", "Thanh chọn ở đầu trang.", true, ""),
        step(3, "Tạo dự án", "Mỗi dự án có bằng chứng riêng.", true, "/app/projects"),
        step(6, "Ghim Spec Kit vào dự án", "Không ghim thì xác thực không chạy.", false, "/app/kits"),
        step(10, "Bằng chứng xác thực chỉ vào bằng CLI", "Chạy: aisdlc init → validate → sync.", false, ""));
  }

  @Test void theOverviewStatesHowFarSetupHasProgressed() {
    String html = render(partiallyComplete(), 3, 10);

    assertTrue(html.contains("3/10 bước đã xong"), "the count must be rendered from the model, not hardcoded");
    assertTrue(html.contains("BẮT ĐẦU TỪ ĐÂU"), "the panel must say this is where to start");
    assertFalse(html.contains("null/"), "a missing count must never render as null");
  }

  @Test void anUnfinishedStepLinksToWhereItIsDoneAndAFinishedOneDoesNot() {
    String html = render(partiallyComplete(), 3, 10);

    assertTrue(html.contains("Ghim Spec Kit vào dự án"), "the outstanding step must be listed");
    assertTrue(html.contains("Làm ngay"), "an outstanding step must offer the way to complete it");
    assertTrue(html.contains("/app/kits?org=") || html.contains("/app/kits"),
        "and the link must carry the current scope to the right screen");
    // The completed steps keep their titles but must not offer an action.
    int actions = html.split("Làm ngay", -1).length - 1;
    assertTrue(actions == 1, "only the one outstanding step with a destination may offer an action, found " + actions);
  }

  /**
   * The step that matters most on this screen. Validation evidence enters through the CLI or an SCM webhook, never
   * through the UI, so an administrator who waits for a button waits forever — the screen has to say so.
   */
  @Test void theStepThatCannotBeDoneInTheUiSaysSoInsteadOfOfferingALink() {
    String html = render(partiallyComplete(), 3, 10);

    assertTrue(html.contains("Bằng chứng xác thực chỉ vào bằng CLI"), "the CLI-only path must be named");
    assertTrue(html.contains("aisdlc init → validate → sync"), "and the actual command must be shown");
    assertTrue(html.contains("không làm ở giao diện"), "and it must be marked as not doable here");
  }

  @Test void aFullyCompletedChecklistShowsNoOutstandingAction() {
    List<Map<String, Object>> all = List.of(
        step(1, "Tạo tổ chức", "hint", true, "/app/projects"),
        step(2, "Chọn phạm vi", "hint", true, ""));

    String html = render(all, 2, 2);

    assertTrue(html.contains("2/2 bước đã xong"));
    assertFalse(html.contains("Làm ngay"), "nothing is outstanding, so no action may be offered");
  }

  private static String render(List<Map<String, Object>> steps, int done, int total) {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    Map<String, Object> variables = new HashMap<>();
    variables.put("page", "overview");
    variables.put("userName", "Administrator");
    variables.put("keycloakSessionStatus", "CONNECTED");
    variables.put("_csrf", Map.of("parameterName", "_csrf", "token", "t", "headerName", "X-CSRF-TOKEN"));
    for (String empty : List.of("organizations", "projects", "memberships", "kits", "projectKits", "validations",
        "policies", "constitutions", "exceptions", "reviews", "audit", "metrics", "apiErrors")) {
      variables.put(empty, List.of());
    }
    variables.put("organizationId", "");
    variables.put("projectId", "");
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
    for (String paged : List.of("projectsPage", "validationsPage", "auditPage")) {
      variables.put(paged, Map.of("page", 0, "totalPages", 0));
    }
    variables.put("setupSteps", steps);
    variables.put("setupDone", done);
    variables.put("setupTotal", total);

    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(
        application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);
    return engine.process("app", context);
  }
}
