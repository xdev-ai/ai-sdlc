package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * The portal used to fetch all thirty-one control-plane datasets on every page view, so opening a document also pulled
 * SBOMs, agent sessions, provenance records and risk scores. Each fetch is now gated on whether the page being
 * rendered actually reads it, which trades a performance problem for a correctness risk: a dataset left out of the
 * table renders that page's section blank, or throws, and both are worse than the waste.
 *
 * <p>So this holds the table to the template from two directions. It re-derives what each section reads and requires
 * the controller's declaration to cover it; then it renders every page with the undeclared datasets <em>absent from
 * the model entirely</em>, which is stricter than supplying them empty — a missing variable is what turns an
 * under-declaration into a visible failure rather than a silent one.
 */
class PortalPageDataTest {
  private static final Path TEMPLATE = Path.of("src", "main", "resources", "templates", "app.html");
  private static final Path FRAGMENTS = Path.of("src", "main", "resources", "templates", "fragments.html");
  private static final Path CONTROLLER = Path.of("src", "main", "java", "ai", "xdev", "aisdlc", "portal", "PortalController.java");

  /** Every dataset the controller fetches, by the model attribute it is published under. */
  private static final List<String> DATASETS = List.of("organizations", "projects", "kits", "validations",
      "evidenceAssets", "policies", "constitutions", "reviews", "metrics", "capabilities", "audit", "memberships",
      "projectKits", "exceptions", "scmEvents", "scmRepositories", "notificationChannels", "notificationDeliveries",
      "approvals", "securityExceptions", "sboms", "provenance", "policyBundles", "agentPromptTemplates",
      "agentSessions", "agentEvidence", "riskScores", "trace", "auditVerification", "validationDetail");

  /** Always fetched, because the scope selector in the shared chrome reads them. */
  private static final Set<String> ALWAYS = Set.of("organizations", "projects");

  @Test
  void theControllerDeclaresEveryDatasetItsPagesRead() throws IOException {
    Map<String, Set<String>> declared = declaredTable();
    Map<String, Set<String>> actual = readsPerPage();
    List<String> problems = new ArrayList<>();

    actual.forEach((page, needed) -> needed.forEach(dataset -> {
      if (ALWAYS.contains(dataset)) return;
      Set<String> pages = declared.get(dataset);
      if (pages == null) {
        problems.add(page + " reads " + dataset + ", which the table does not mention at all");
      } else if (!pages.contains(page)) {
        problems.add(page + " reads " + dataset + " but is not listed for it, so that section renders blank");
      }
    }));

    assertTrue(problems.isEmpty(), String.join("\n  ", problems));
  }

  @Test
  void everyPageRendersWithOnlyItsDeclaredDatasetsInTheModel() throws IOException {
    Map<String, Set<String>> declared = declaredTable();
    for (String page : readsPerPage().keySet()) {
      Set<String> allowed = new TreeSet<>(ALWAYS);
      declared.forEach((dataset, pages) -> { if (pages.contains(page)) allowed.add(dataset); });
      try {
        String html = render(page, allowed);
        assertTrue(html.contains("AI—SDLC"), page + " did not render the shell");
      } catch (RuntimeException failure) {
        fail("rendering " + page + " with only " + allowed + " failed, so a dataset it needs is undeclared: "
            + failure.getMessage());
      }
    }
  }

  /** {@code Map.entry("kits", Set.of("kits"))} lines out of PAGE_DATASETS. */
  private static Map<String, Set<String>> declaredTable() throws IOException {
    String source = Files.readString(CONTROLLER);
    Map<String, Set<String>> table = new LinkedHashMap<>();
    Matcher entries = Pattern.compile("Map\\.entry\\(\"(\\w+)\", Set\\.of\\(([^)]*)\\)\\)").matcher(source);
    while (entries.find()) {
      Set<String> pages = new TreeSet<>();
      Matcher names = Pattern.compile("\"([a-z-]+)\"").matcher(entries.group(2));
      while (names.find()) pages.add(names.group(1));
      table.put(entries.group(1), pages);
    }
    assertTrue(table.size() > 20, "PAGE_DATASETS was not parsed; found " + table.size() + " entries");
    return table;
  }

  /**
   * What each page section references, by line range. This template keeps one section per line, and two sections
   * carry the same page name, so the blocks are accumulated rather than replaced — doing that wrongly is what hid
   * {@code memberships} from the projects page while the map was being built.
   */
  private static Map<String, Set<String>> readsPerPage() throws IOException {
    List<String> lines = List.of(Files.readString(TEMPLATE).split("\n"));
    Pattern sectionStart = Pattern.compile("th:if=\"\\$\\{page == '([a-z-]+)'\\}\"");
    List<int[]> bounds = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      Matcher matcher = sectionStart.matcher(line);
      if (matcher.find() && (line.contains("<section") || line.contains("th:replace"))) {
        bounds.add(new int[]{index, -1});
        names.add(matcher.group(1));
      }
    }
    Map<String, StringBuilder> blocks = new LinkedHashMap<>();
    for (int index = 0; index < bounds.size(); index++) {
      int from = bounds.get(index)[0];
      int to = index + 1 < bounds.size() ? bounds.get(index + 1)[0] : lines.size();
      blocks.computeIfAbsent(names.get(index), key -> new StringBuilder())
          .append(String.join("\n", lines.subList(from, to))).append('\n');
    }
    // The quality panel is a replaced fragment, so its references live in the other file.
    blocks.computeIfAbsent("quality", key -> new StringBuilder()).append(Files.readString(FRAGMENTS));

    Map<String, Set<String>> result = new LinkedHashMap<>();
    blocks.forEach((page, body) -> {
      Set<String> found = new TreeSet<>();
      for (String dataset : DATASETS) {
        if (Pattern.compile("\\$\\{" + dataset + "[.\\[}]").matcher(body).find()
            || Pattern.compile("\\$\\{#lists\\.(?:size|isEmpty)\\(" + dataset + "\\)").matcher(body).find()) {
          found.add(dataset);
        }
      }
      result.put(page, found);
    });
    return result;
  }

  private static String render(String page, Set<String> present) {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    Map<String, Object> variables = new HashMap<>();
    variables.put("page", page);
    variables.put("userName", "Administrator");
    variables.put("keycloakSessionStatus", "CONNECTED");
    variables.put("_csrf", Map.of("parameterName", "_csrf", "token", "t", "headerName", "X-CSRF-TOKEN"));
    variables.put("apiErrors", List.of());
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
    variables.put("riskJson", "[]");
    variables.put("documentHtml", "");
    variables.put("setupSteps", List.of());
    variables.put("setupDone", 0);
    variables.put("setupTotal", 10);
    // The controller publishes a paging envelope for every paged dataset regardless of the view, so the fixture does
    // too. Leaving these out made the scm page fail on scmEventsPage, which was this test's gap rather than a missing
    // dataset — the distinction matters, because the whole point here is to fail only on real under-declaration.
    for (String dataset : DATASETS) {
      variables.put(dataset + "Page", Map.of("page", 0, "totalPages", 0));
    }
    variables.put("organizationsPage", Map.of("page", 0, "totalPages", 0));
    // Knowledge attributes are served by a separate handler, so they are always present for that view.
    if (page.equals("knowledge")) {
      variables.put("spaces", List.of());
      variables.put("spaceId", "");
      variables.put("docTree", List.of());
      variables.put("docId", "");
      variables.put("document", Map.of());
      variables.put("docHistory", List.of());
      variables.put("searchQuery", "");
      variables.put("searchHits", List.of());
    }
    // Only the declared datasets exist. Anything else is absent, not empty.
    for (String dataset : present) {
      variables.put(dataset, dataset.equals("trace") ? Map.of("nodes", List.of(), "edges", List.of())
          : dataset.equals("auditVerification") || dataset.equals("validationDetail") ? Map.of() : List.of());
    }

    var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
    WebContext context = new WebContext(
        application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()), Locale.ROOT, variables);
    return engine.process("app", context);
  }
}
