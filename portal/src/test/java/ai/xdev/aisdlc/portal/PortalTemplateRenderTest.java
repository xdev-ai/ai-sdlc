package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import java.util.Locale;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class PortalTemplateRenderTest {
  @Test
  void rendersOverviewWithoutLiveDataOrJavaScript() {
    String html = render(Map.of("intact", true, "verifiedEvents", 24));

    assertTrue(html.contains("AI—SDLC"));
    assertTrue(html.contains("Make every delivery"));
    assertTrue(html.contains("browser never stores API tokens"));
    assertTrue(html.contains("Keycloak session connected"));
    assertTrue(html.contains("action=\"/logout\""));
    // The overview badge renders "verified"; the audit page renders "hash chain verified". Either way the
    // value has to come from the map, which is what the empty-map case below could not do.
    assertTrue(html.contains("verified"), "a verified chain must render its verified state");
    assertTrue(!html.contains("select org"), "a scoped, verified chain must not show the unscoped state");
  }

  private static String render(Map<String, Object> auditVerification) {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    Map<String, Object> variables = Map.ofEntries(
        Map.entry("page", "overview"), Map.entry("userName", "Administrator"), Map.entry("keycloakSessionStatus", "CONNECTED"),
        Map.entry("_csrf", Map.of("parameterName", "_csrf", "token", "test-token", "headerName", "X-CSRF-TOKEN")),
        Map.entry("organizations", List.of()), Map.entry("projects", List.of()), Map.entry("memberships", List.of()),
        Map.entry("kits", List.of()), Map.entry("projectKits", List.of()), Map.entry("validations", List.of()),
        Map.entry("policies", List.of()), Map.entry("constitutions", List.of()), Map.entry("exceptions", List.of()),
        Map.entry("reviews", List.of()), Map.entry("audit", List.of()), Map.entry("metrics", List.of()),
        Map.entry("apiErrors", List.of()), Map.entry("organizationId", ""), Map.entry("projectId", ""),
        Map.entry("filter", ""), Map.entry("pageNumber", 0), Map.entry("reactEntry", ""),
        Map.entry("validationsJson", "[]"), Map.entry("traceJson", "{\"nodes\":[],\"edges\":[]}"),
        Map.entry("reviewIslandJson", "{\"reviews\":[],\"exceptions\":[]}"),
        Map.entry("projectsPage", Map.of("page", 0, "totalPages", 0)),
        Map.entry("validationsPage", Map.of("page", 0, "totalPages", 0)),
        Map.entry("auditPage", Map.of("page", 0, "totalPages", 0)),
        Map.entry("auditVerification", auditVerification),
        Map.entry("trace", Map.of("nodes", List.of(), "edges", List.of())));
    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);

    return engine.process("app", context);
  }

  /**
   * The state every user is in immediately after their first login: no organization selected, so
   * {@code ManagementApiClient.ObjectData.empty().value()} puts an <em>empty</em> map in the model.
   *
   * <p>This is the case that made {@code /app} return 500 for every user on every load. Spring's {@code MapAccessor}
   * only reads a property when the map actually contains that key; for a missing key SpEL falls through and throws
   * {@code EL1008E: Property or field 'intact' cannot be found}. The template therefore has to index the map rather
   * than treat it as a bean. The previous fixture hid this by seeding a key the API never returns.
   */
  @Test
  void rendersOverviewBeforeAnyOrganisationIsSelected() {
    String html = render(Map.of());

    assertTrue(html.contains("AI—SDLC"), "the shell must render with no verification data at all");
    assertTrue(html.contains("select org"), "the unscoped state is shown rather than failing");
  }
}
