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
        Map.entry("auditVerification", Map.of("valid", false)),
        Map.entry("trace", Map.of("nodes", List.of(), "edges", List.of())));
    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);

    String html = engine.process("app", context);

    assertTrue(html.contains("AI—SDLC"));
    assertTrue(html.contains("Make every delivery"));
    assertTrue(html.contains("browser never stores API tokens"));
    assertTrue(html.contains("Keycloak session connected"));
    assertTrue(html.contains("action=\"/logout\""));
  }
}
