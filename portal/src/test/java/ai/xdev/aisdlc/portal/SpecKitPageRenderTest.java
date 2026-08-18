package ai.xdev.aisdlc.portal;

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
 * The Spec Kit registry returned 500 for every administrator as soon as one kit existed.
 *
 * <p>The template read {@code item.lifecycleStatus} while the API returned {@code lifecycle_status}, and Thymeleaf's
 * {@code MapAccessor} raises {@code EL1008E} on a missing key instead of yielding null. Nothing in the test suite
 * noticed, because no fixture used the shape the API actually sent — and this is step 5 of the setup sequence the
 * overview tells an administrator to follow, so the flow broke at the moment it started working.
 *
 * <p>The fixture below is the live response, key for key. That is the whole value of it.
 */
class SpecKitPageRenderTest {
  /** Exactly what {@code GET /organizations/{id}/spec-kits} returns for one registered kit. */
  private static Map<String, Object> kit(String id, String lifecycle) {
    Map<String, Object> kit = new HashMap<>();
    kit.put("id", id);
    kit.put("slug", "core-kit");
    kit.put("version", "1.0.0");
    kit.put("layer", "CORE");
    kit.put("pinned", false);
    kit.put("lifecycleStatus", lifecycle);
    kit.put("deprecatedAt", null);
    kit.put("deprecationReason", null);
    kit.put("createdAt", "2026-08-18T04:00:00Z");
    return kit;
  }

  @Test void theRegistryRendersWithARealKitAndOffersPinAndDeprecate() {
    String html = render(List.of(kit("k1", "ACTIVE")), List.of());

    assertTrue(html.contains("core-kit"), "the kit must be listed");
    assertTrue(html.contains("ACTIVE"), "its lifecycle must be shown, which is the column that used to throw");
    assertTrue(html.contains("/spec-kits/k1/pin"), "an ACTIVE kit must offer pinning — step 6 of the setup sequence");
    assertTrue(html.contains("/spec-kits/k1/deprecate"), "and deprecation");
  }

  /** A deprecated kit must not offer either action, or the page would invite a request the API refuses. */
  @Test void aDeprecatedKitOffersNeitherPinNorDeprecate() {
    String html = render(List.of(kit("k2", "DEPRECATED")), List.of());

    assertTrue(html.contains("DEPRECATED"), "its state must still be visible");
    assertTrue(!html.contains("/spec-kits/k2/pin"), "a deprecated kit cannot be pinned");
    assertTrue(!html.contains("/spec-kits/k2/deprecate"), "nor deprecated twice");
  }

  @Test void aPinnedKitShowsItsPrecedenceInTheResolvedStack() {
    Map<String, Object> pinned = new HashMap<>();
    pinned.put("id", "k1");
    pinned.put("slug", "core-kit");
    pinned.put("version", "1.0.0");
    pinned.put("layer", "CORE");
    pinned.put("lifecycleStatus", "ACTIVE");
    pinned.put("precedence", 100);
    pinned.put("pinnedAt", "2026-08-18T04:05:00Z");

    String html = render(List.of(kit("k1", "ACTIVE")), List.of(pinned));

    assertTrue(html.contains("core-kit · 1.0.0"), "the resolved stack must name the kit");
    assertTrue(html.contains("precedence 100"), "and its precedence");
  }

  private static String render(List<Map<String, Object>> kits, List<Map<String, Object>> projectKits) {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    Map<String, Object> variables = new HashMap<>();
    variables.put("page", "kits");
    variables.put("userName", "Administrator");
    variables.put("keycloakSessionStatus", "CONNECTED");
    variables.put("_csrf", Map.of("parameterName", "_csrf", "token", "t", "headerName", "X-CSRF-TOKEN"));
    for (String empty : List.of("organizations", "projects", "apiErrors")) variables.put(empty, List.of());
    variables.put("kits", kits);
    variables.put("projectKits", projectKits);
    variables.put("organizationId", "o1");
    variables.put("projectId", "p1");
    variables.put("filter", "");
    variables.put("pageNumber", 0);
    variables.put("selectedRunId", "");
    variables.put("reactEntry", "");
    variables.put("setupSteps", List.of());
    variables.put("setupDone", 0);
    variables.put("setupTotal", 10);
    for (String paged : List.of("kitsPage", "projectsPage")) {
      variables.put(paged, Map.of("page", 0, "totalPages", 0));
    }

    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(
        application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);
    return engine.process("app", context);
  }
}
