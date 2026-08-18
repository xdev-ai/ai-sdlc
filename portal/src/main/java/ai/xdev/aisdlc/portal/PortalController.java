package ai.xdev.aisdlc.portal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PortalController {
  private static final Set<String> PAGES = Set.of("overview", "projects", "kits", "validations", "evidence", "traceability", "governance", "policy-as-code", "agent-governance", "risk-intelligence", "scm", "supply-chain", "reviews", "notifications", "quality", "audit");
  private final ManagementApiClient api;
  private final ReactAssetService reactAssets;
  private final ObjectMapper mapper = new ObjectMapper();
  public PortalController(ManagementApiClient api, ReactAssetService reactAssets) { this.api = api; this.reactAssets = reactAssets; }

  @GetMapping("/") String landing() { return "landing"; }
  @GetMapping("/session-expired") String sessionExpired() { return "session-expired"; }

  /**
   * The documentation workspace: spaces, the page tree, one page's text, its version history, and search.
   *
   * <p>Deliberately a separate handler from {@link #app}, which issues thirty-one control-plane requests on every
   * view because one method serves sixteen pages. Opening a document should not also fetch SBOMs, agent sessions and
   * risk scores. This one requests only what the screen shows, and only when a scope has actually been selected.
   *
   * <p>Both {@code /app/knowledge} and {@code /app/{page}} would match this path; Spring prefers the literal mapping,
   * so this method wins and {@code knowledge} is deliberately absent from {@code PAGES}.
   */
  @GetMapping("/app/knowledge")
  String knowledge(@RequestParam(required = false) UUID org, @RequestParam(required = false) UUID project,
      @RequestParam(required = false) UUID space, @RequestParam(name = "doc", required = false) UUID doc,
      @RequestParam(name = "v", required = false) Integer version, @RequestParam(required = false) String q,
      @AuthenticationPrincipal OidcUser user, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
      Model model) {
    if (client == null || client.getAccessToken() == null || client.getAccessToken().getTokenValue().isBlank()) {
      return "redirect:/session-expired";
    }
    String token = client.getAccessToken().getTokenValue();
    String base = org == null ? null : "/api/v1/organizations/" + org + "/knowledge";

    ManagementApiClient.PageData organizations = api.page("/api/v1/organizations?size=100", token);
    ManagementApiClient.PageData projects = org == null
        ? ManagementApiClient.PageData.empty() : api.page("/api/v1/organizations/" + org + "/projects?size=100", token);
    ManagementApiClient.PageData spaces = base == null
        ? ManagementApiClient.PageData.empty() : api.page(base + "/spaces?size=100", token);

    // A space must be chosen before a tree can be shown. Falling back to the first space means a fresh visitor sees
    // documents immediately instead of an empty panel telling them to pick something.
    UUID activeSpace = space;
    if (activeSpace == null && !spaces.items().isEmpty()) {
      Object first = spaces.items().get(0).get("id");
      if (first != null) activeSpace = UUID.fromString(first.toString());
    }

    ManagementApiClient.ListData tree = activeSpace == null || base == null
        ? ManagementApiClient.ListData.empty() : api.list(base + "/spaces/" + activeSpace + "/pages", token);

    // Same reasoning one level down: open the first page of the tree rather than showing a reader with nothing in it.
    UUID activeDoc = doc;
    if (activeDoc == null && !tree.items().isEmpty()) {
      Object first = tree.items().get(0).get("id");
      if (first != null) activeDoc = UUID.fromString(first.toString());
    }

    String docPath = base == null || activeDoc == null ? null
        : base + "/pages/" + activeDoc + (version == null ? "" : "/versions/" + version);
    ManagementApiClient.ObjectData document = docPath == null
        ? ManagementApiClient.ObjectData.empty() : api.object(docPath, token);
    ManagementApiClient.PageData history = base == null || activeDoc == null
        ? ManagementApiClient.PageData.empty() : api.page(base + "/pages/" + activeDoc + "/versions?size=25", token);

    String query = q == null ? "" : q.strip();
    ManagementApiClient.ListData hits = base == null || query.isEmpty()
        ? ManagementApiClient.ListData.empty()
        : api.list(base + "/search?limit=20&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8), token);

    List<String> apiErrors = errors(organizations.error(), projects.error(), spaces.error(), tree.error(),
        document.error(), history.error(), hits.error());
    if (apiErrors.stream().anyMatch(ManagementApiClient::requiresSessionRecovery)) return "redirect:/session-expired";

    model.addAttribute("page", "knowledge");
    model.addAttribute("pageNumber", 0);
    model.addAttribute("filter", "");
    model.addAttribute("selectedRunId", "");
    model.addAttribute("organizationId", org == null ? "" : org.toString());
    model.addAttribute("projectId", project == null ? "" : project.toString());
    model.addAttribute("userName", Optional.ofNullable(user.getFullName()).orElse(user.getPreferredUsername()));
    model.addAttribute("organizations", organizations.items());
    model.addAttribute("projects", projects.items());
    model.addAttribute("spaces", spaces.items());
    model.addAttribute("spaceId", activeSpace == null ? "" : activeSpace.toString());
    model.addAttribute("docTree", tree.items());
    model.addAttribute("docId", activeDoc == null ? "" : activeDoc.toString());
    model.addAttribute("document", document.value());
    // Rendered here, not in the template: the conversion escapes first and only then adds its own tags, so the
    // template can safely use th:utext. A page body is user-authored, and this is the one place it becomes markup.
    Object body = document.value().get("body");
    model.addAttribute("documentHtml", body == null ? "" : MarkdownToSafeHtml.render(body.toString()));
    model.addAttribute("docHistory", history.items());
    model.addAttribute("searchQuery", query);
    model.addAttribute("searchHits", hits.items());
    model.addAttribute("apiErrors", apiErrors);
    model.addAttribute("keycloakSessionStatus", "CONNECTED");
    model.addAttribute("reactEntry", reactAssets.entry());
    return "app";
  }

  /**
   * The setup sequence, with the state each step is actually in.
   *
   * <p>This platform has a required order — an organization before a project, a registered kit before a pinned one, a
   * recorded constitution before an activated one — and the sidebar presented sixteen equal doors instead. A new
   * administrator could not tell which one came first, so the overview now answers that directly.
   *
   * <p>The last entry is never "done" and has no link. Validation evidence enters through the CLI or an SCM webhook,
   * never through this UI, so an administrator who waits for a button will wait forever. Saying so is the single most
   * useful thing this screen can do.
   */
  private static List<Map<String, Object>> setupChecklist(boolean hasOrganizations, boolean scopeSelected,
      boolean hasProjects, boolean hasMemberships, boolean hasKits, boolean hasPinnedKits,
      boolean hasActiveConstitution, boolean hasActivePolicy, boolean hasDocuments, boolean hasValidations) {
    return List.of(
        step(1, "Tạo tổ chức", "Gốc của mọi dữ liệu. Form nằm ở trang Projects.", hasOrganizations, "/app/projects"),
        step(2, "Chọn phạm vi tổ chức và dự án", "Thanh chọn ở đầu trang. Chưa chọn thì hầu hết màn hình sẽ trống.", scopeSelected, null),
        step(3, "Tạo dự án", "Mỗi dự án có bằng chứng và thành viên riêng.", hasProjects, "/app/projects"),
        step(4, "Mời thành viên", "Cần Keycloak subject của người đó, không phải email.", hasMemberships, "/app/projects"),
        step(5, "Đăng ký Spec Kit", "Phải tự dán manifest JSON.", hasKits, "/app/kits"),
        step(6, "Ghim Spec Kit vào dự án", "Không ghim thì xác thực không chạy — hệ thống cố ý không đoán bản mặc định.", hasPinnedKits, "/app/kits"),
        step(7, "Ban hành và kích hoạt Constitution", "Tạo xong chưa có hiệu lực; phải bấm Activate.", hasActiveConstitution, "/app/governance"),
        step(8, "Ghi và kích hoạt Policy", "Cũng phải Activate riêng.", hasActivePolicy, "/app/governance"),
        step(9, "Nạp tài liệu dự án", "Kho tài liệu để AI đọc và trích dẫn được.", hasDocuments, "/app/knowledge"),
        step(10, hasValidations ? "Bằng chứng xác thực đã có" : "Bằng chứng xác thực chỉ vào bằng CLI",
            "Không có nút nào trên giao diện tạo lần chạy xác thực. Chạy: aisdlc init → validate → sync, hoặc để webhook SCM đẩy vào.",
            hasValidations, null));
  }

  private static Map<String, Object> step(int number, String title, String hint, boolean done, String href) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("number", number);
    entry.put("title", title);
    entry.put("hint", hint);
    entry.put("done", done);
    entry.put("href", href == null ? "" : href);
    return entry;
  }

  private static boolean anyActive(List<Map<String, Object>> items) {
    return items.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("active")));
  }

  @GetMapping({"/app", "/app/{page}"})
  String app(@PathVariable(required = false) String page, @RequestParam(required = false) UUID org, @RequestParam(required = false) UUID project,
      @RequestParam(defaultValue = "0") int p, @RequestParam(required = false) String filter, @RequestParam(required = false) UUID run,
      @AuthenticationPrincipal OidcUser user, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, Model model) {
    String view = page == null || !PAGES.contains(page) ? "overview" : page;
    if (client == null || client.getAccessToken() == null || client.getAccessToken().getTokenValue().isBlank()) return "redirect:/session-expired";
    String token = client.getAccessToken().getTokenValue();
    ManagementApiClient.PageData organizations = api.page("/api/v1/organizations?size=100", token);
    ManagementApiClient.PageData projects = org == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/organizations/" + org + "/projects?page=" + p + "&size=25", token);
    ManagementApiClient.PageData kits = org == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/organizations/" + org + "/spec-kits?page=" + p + "&size=25", token);
    ManagementApiClient.PageData validations = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/validation-runs?page=" + p + "&size=25" + optional("status", filter), token);
    ManagementApiClient.PageData evidenceAssets = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/evidence-assets?page=" + p + "&size=25", token);
    ManagementApiClient.PageData policies = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/policies?page=" + p + "&size=25&includeInactive=true", token);
    ManagementApiClient.PageData constitutions = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/constitutions?page=" + p + "&size=25&includeInactive=true", token);
    ManagementApiClient.PageData reviews = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/review-items?page=" + p + "&size=25" + optional("status", filter), token);
    ManagementApiClient.PageData metrics = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/quality-metrics?page=0&size=24", token);
    ManagementApiClient.PageData capabilities = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/capability-grants?page=0&size=25&includeExpired=true", token);
    ManagementApiClient.PageData audit = org == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/organizations/" + org + "/audit-events?page=" + p + "&size=25" + optional("action", filter), token);
    ManagementApiClient.ListData memberships = project == null ? ManagementApiClient.ListData.empty() : api.list("/api/v1/projects/" + project + "/memberships", token);
    ManagementApiClient.ListData projectKits = project == null ? ManagementApiClient.ListData.empty() : api.list("/api/v1/projects/" + project + "/spec-kits", token);
    ManagementApiClient.PageData exceptions = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/exception-requests?page=" + p + "&size=25", token);
    ManagementApiClient.PageData scmEvents = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/scm-events?page=" + p + "&size=25", token);
    ManagementApiClient.ListData scmRepositories = project == null ? ManagementApiClient.ListData.empty() : api.list("/api/v1/projects/" + project + "/scm-repositories", token);
    ManagementApiClient.ListData notificationChannels = project == null ? ManagementApiClient.ListData.empty() : api.list("/api/v1/projects/" + project + "/notification-channels", token);
    ManagementApiClient.PageData notificationDeliveries = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/notification-deliveries?page=" + p + "&size=25", token);
    ManagementApiClient.PageData approvals = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/approvals?page=" + p + "&size=25", token);
    ManagementApiClient.PageData securityExceptions = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/security-exceptions?page=" + p + "&size=25", token);
    ManagementApiClient.PageData sboms = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/supply-chain/sboms?page=" + p + "&size=25", token);
    ManagementApiClient.PageData provenance = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/supply-chain/provenance?page=" + p + "&size=25", token);
    ManagementApiClient.PageData policyBundles = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/policy-bundles?page=" + p + "&size=25", token);
    ManagementApiClient.PageData agentPromptTemplates = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/agent-governance/prompt-templates?page=" + p + "&size=25", token);
    ManagementApiClient.PageData agentSessions = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/agent-governance/sessions?page=" + p + "&size=25", token);
    ManagementApiClient.PageData agentEvidence = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/agent-governance/evidence?page=" + p + "&size=25", token);
    ManagementApiClient.PageData riskScores = project == null ? ManagementApiClient.PageData.empty() : api.page("/api/v1/projects/" + project + "/risk-intelligence/trend?page=0&size=30", token);
    ManagementApiClient.ObjectData trace = project == null ? ManagementApiClient.ObjectData.empty() : api.trace("/api/v1/projects/" + project + "/traceability", token);
    ManagementApiClient.ObjectData auditVerification = org == null ? ManagementApiClient.ObjectData.empty() : api.object("/api/v1/organizations/" + org + "/audit-events/verify", token);
    ManagementApiClient.ObjectData validationDetail = project == null || run == null ? ManagementApiClient.ObjectData.empty() : api.object("/api/v1/projects/" + project + "/validation-runs/" + run, token);
    // Only the overview needs this, and only to answer "are there documents yet".
    ManagementApiClient.PageData knowledgeSpaces = org == null || !view.equals("overview")
        ? ManagementApiClient.PageData.empty() : api.page("/api/v1/organizations/" + org + "/knowledge/spaces?size=1", token);

    List<String> apiErrors = errors(organizations.error(), projects.error(), kits.error(), validations.error(), evidenceAssets.error(), policies.error(), constitutions.error(), reviews.error(), metrics.error(), capabilities.error(), audit.error(), memberships.error(), projectKits.error(), exceptions.error(), scmEvents.error(), scmRepositories.error(), notificationChannels.error(), notificationDeliveries.error(), approvals.error(), securityExceptions.error(), sboms.error(), provenance.error(), policyBundles.error(), agentPromptTemplates.error(), agentSessions.error(), agentEvidence.error(), riskScores.error(), trace.error(), auditVerification.error(), validationDetail.error());
    if (apiErrors.stream().anyMatch(ManagementApiClient::requiresSessionRecovery)) return "redirect:/session-expired";
    model.addAttribute("page", view); model.addAttribute("pageNumber", p); model.addAttribute("filter", filter == null ? "" : filter);
    model.addAttribute("organizationId", org == null ? "" : org.toString()); model.addAttribute("projectId", project == null ? "" : project.toString());
    model.addAttribute("userName", Optional.ofNullable(user.getFullName()).orElse(user.getPreferredUsername()));
    model.addAttribute("organizations", organizations.items()); model.addAttribute("projects", projects.items()); model.addAttribute("kits", kits.items()); model.addAttribute("validations", validations.items()); model.addAttribute("evidenceAssets", evidenceAssets.items()); model.addAttribute("policies", policies.items()); model.addAttribute("constitutions", constitutions.items()); model.addAttribute("reviews", reviews.items()); model.addAttribute("metrics", metrics.items()); model.addAttribute("capabilities", capabilities.items()); model.addAttribute("audit", audit.items()); model.addAttribute("memberships", memberships.items()); model.addAttribute("projectKits", projectKits.items()); model.addAttribute("exceptions", exceptions.items()); model.addAttribute("scmEvents", scmEvents.items()); model.addAttribute("scmRepositories", scmRepositories.items()); model.addAttribute("notificationChannels", notificationChannels.items()); model.addAttribute("notificationDeliveries", notificationDeliveries.items()); model.addAttribute("approvals", approvals.items()); model.addAttribute("securityExceptions", securityExceptions.items()); model.addAttribute("sboms", sboms.items()); model.addAttribute("provenance", provenance.items()); model.addAttribute("policyBundles", policyBundles.items()); model.addAttribute("agentPromptTemplates", agentPromptTemplates.items()); model.addAttribute("agentSessions", agentSessions.items()); model.addAttribute("agentEvidence", agentEvidence.items()); model.addAttribute("riskScores", riskScores.items());
    model.addAttribute("organizationsPage", organizations); model.addAttribute("projectsPage", projects); model.addAttribute("kitsPage", kits); model.addAttribute("validationsPage", validations); model.addAttribute("evidenceAssetsPage", evidenceAssets); model.addAttribute("policiesPage", policies); model.addAttribute("constitutionsPage", constitutions); model.addAttribute("reviewsPage", reviews); model.addAttribute("metricsPage", metrics); model.addAttribute("capabilitiesPage", capabilities); model.addAttribute("auditPage", audit); model.addAttribute("exceptionsPage", exceptions); model.addAttribute("scmEventsPage", scmEvents); model.addAttribute("notificationDeliveriesPage", notificationDeliveries); model.addAttribute("approvalsPage", approvals); model.addAttribute("securityExceptionsPage", securityExceptions); model.addAttribute("sbomsPage", sboms); model.addAttribute("provenancePage", provenance); model.addAttribute("policyBundlesPage", policyBundles); model.addAttribute("agentPromptTemplatesPage", agentPromptTemplates); model.addAttribute("agentSessionsPage", agentSessions); model.addAttribute("agentEvidencePage", agentEvidence); model.addAttribute("riskScoresPage", riskScores);
    model.addAttribute("trace", trace.value()); model.addAttribute("auditVerification", auditVerification.value()); model.addAttribute("validationDetail", validationDetail.value()); model.addAttribute("selectedRunId", run == null ? "" : run.toString());
    model.addAttribute("apiErrors", apiErrors); model.addAttribute("keycloakSessionStatus", "CONNECTED");
    List<Map<String, Object>> setupSteps = setupChecklist(!organizations.items().isEmpty(), org != null && project != null,
        !projects.items().isEmpty(), !memberships.items().isEmpty(), !kits.items().isEmpty(),
        !projectKits.items().isEmpty(), anyActive(constitutions.items()), anyActive(policies.items()),
        !knowledgeSpaces.items().isEmpty(), !validations.items().isEmpty());
    model.addAttribute("setupSteps", setupSteps);
    model.addAttribute("setupDone", setupSteps.stream().filter(s -> Boolean.TRUE.equals(s.get("done"))).count());
    model.addAttribute("setupTotal", setupSteps.size());
    model.addAttribute("metricsJson", json(metrics.items())); model.addAttribute("traceJson", json(trace.value())); model.addAttribute("validationsJson", json(validations.items())); model.addAttribute("riskJson", json(riskScores.items()));
    model.addAttribute("reviewIslandJson", json(Map.of("reviews", reviews.items(), "exceptions", exceptions.items(), "organizationId", org == null ? "" : org.toString(), "projectId", project == null ? "" : project.toString())));
    model.addAttribute("reactEntry", reactAssets.entry());
    return "app";
  }

  @PostMapping("/app/organizations")
  String createOrganization(@RequestParam String slug, @RequestParam String name, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/organizations", Map.of("slug", slug, "name", name), client, "/app/projects", redirect); }
  @PostMapping("/app/organizations/{organizationId}/projects")
  String createProject(@PathVariable UUID organizationId, @RequestParam String slug, @RequestParam String name, @RequestParam(required = false) String description, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/organizations/" + organizationId + "/projects", Map.of("slug", slug, "name", name, "description", description == null ? "" : description), client, "/app/projects?org=" + organizationId, redirect); }
  @PostMapping("/app/projects/{projectId}/memberships")
  String inviteMember(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String subject, @RequestParam String role, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/memberships", Map.of("subject", subject, "role", role), client, "/app/projects?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/memberships/{membershipId}/role")
  String changeMembershipRole(@PathVariable UUID projectId, @PathVariable UUID membershipId, @RequestParam UUID org, @RequestParam String role, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutatePut("/api/v1/projects/" + projectId + "/memberships/" + membershipId, Map.of("role", role), client, "/app/projects?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/memberships/{membershipId}/remove")
  String removeMembership(@PathVariable UUID projectId, @PathVariable UUID membershipId, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutateDelete("/api/v1/projects/" + projectId + "/memberships/" + membershipId, client, "/app/projects?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/organizations/{organizationId}/spec-kits")
  String registerKit(@PathVariable UUID organizationId, @RequestParam String slug, @RequestParam String version, @RequestParam String layer, @RequestParam String manifest, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/organizations/" + organizationId + "/spec-kits", Map.of("slug", slug, "version", version, "layer", layer, "manifest", manifest), client, "/app/kits?org=" + organizationId, redirect); }
  @PostMapping("/app/projects/{projectId}/spec-kits/{kitId}/pin")
  String pinKit(@PathVariable UUID projectId, @PathVariable UUID kitId, @RequestParam UUID org, @RequestParam int precedence, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/spec-kits/" + kitId + "/pin?precedence=" + precedence, Map.of(), client, "/app/kits?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/spec-kits/{kitId}/unpin")
  String unpinKit(@PathVariable UUID projectId, @PathVariable UUID kitId, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutateDelete("/api/v1/projects/" + projectId + "/spec-kits/" + kitId + "/pin", client, "/app/kits?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/organizations/{organizationId}/spec-kits/{kitId}/deprecate")
  String deprecateKit(@PathVariable UUID organizationId, @PathVariable UUID kitId, @RequestParam String reason, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/organizations/" + organizationId + "/spec-kits/" + kitId + "/deprecate", Map.of("reason", reason), client, "/app/kits?org=" + organizationId, redirect); }
  @PostMapping("/app/organizations/{organizationId}/policies")
  String createPolicy(@PathVariable UUID organizationId, @RequestParam(required = false) String project, @RequestParam String key, @RequestParam String version, @RequestParam String rule, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { UUID projectId = uuidOrNull(project); return mutate("/api/v1/organizations/" + organizationId + "/policies", map("projectId", projectId, "key", key, "version", version, "rule", rule), client, "/app/governance?org=" + organizationId + optional("project", project), redirect); }
  @PostMapping("/app/organizations/{organizationId}/policies/{policyId}/activation")
  String changePolicyActivation(@PathVariable UUID organizationId, @PathVariable UUID policyId, @RequestParam(required = false) String project, @RequestParam boolean active, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { String suffix = uuidOrNull(project) == null ? "" : "?projectId=" + project; return mutate("/api/v1/organizations/" + organizationId + "/policies/" + policyId + "/" + (active ? "activate" : "deactivate") + suffix, Map.of(), client, "/app/governance?org=" + organizationId + optional("project", project), redirect); }
  @PostMapping("/app/organizations/{organizationId}/constitutions")
  String createConstitution(@PathVariable UUID organizationId, @RequestParam(required = false) String project, @RequestParam String version, @RequestParam String content, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { UUID projectId = uuidOrNull(project); return mutate("/api/v1/organizations/" + organizationId + "/constitutions", map("projectId", projectId, "version", version, "content", content), client, "/app/governance?org=" + organizationId + optional("project", project), redirect); }
  @PostMapping("/app/organizations/{organizationId}/constitutions/{constitutionId}/activation")
  String changeConstitutionActivation(@PathVariable UUID organizationId, @PathVariable UUID constitutionId, @RequestParam(required = false) String project, @RequestParam boolean active, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { String suffix = uuidOrNull(project) == null ? "" : "?projectId=" + project; return mutate("/api/v1/organizations/" + organizationId + "/constitutions/" + constitutionId + "/" + (active ? "activate" : "deactivate") + suffix, Map.of(), client, "/app/governance?org=" + organizationId + optional("project", project), redirect); }
  @PostMapping("/app/projects/{projectId}/exception-requests")
  String requestException(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String policyKey, @RequestParam String rationale, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/exception-requests", Map.of("policyKey", policyKey, "rationale", rationale), client, "/app/governance?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/exception-requests/{exceptionId}/decision")
  String decideException(@PathVariable UUID projectId, @PathVariable UUID exceptionId, @RequestParam UUID org, @RequestParam String decision, @RequestParam String note, @RequestParam(required = false) String expiresAt, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/exception-requests/" + exceptionId + "/decision", map("decision", decision, "note", note, "expiresAt", instantOrNull(expiresAt)), client, "/app/reviews?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/capability-grants")
  String grantCapability(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String subject, @RequestParam String capability, @RequestParam(required = false) String expiresAt, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/capability-grants", map("subject", subject, "capability", capability, "expiresAt", instantOrNull(expiresAt)), client, "/app/governance?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/review-items")
  String requestReview(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String type, @RequestParam String title, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/review-items", Map.of("type", type, "title", title), client, "/app/reviews?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/review-items/{reviewId}/decision")
  String decideReview(@PathVariable UUID projectId, @PathVariable UUID reviewId, @RequestParam UUID org, @RequestParam String decision, @RequestParam(required = false) String note, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/review-items/" + reviewId + "/decision", Map.of("decision", decision, "note", note == null ? "" : note), client, "/app/reviews?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/scm-repositories")
  String linkScmRepository(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String provider, @RequestParam String repositoryFullName, @RequestParam(required = false) Long installationId, @RequestParam(required = false) String defaultBranch, @RequestParam(defaultValue = "false") boolean policyGateEnabled, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/scm-repositories", map("provider", provider, "repositoryFullName", repositoryFullName, "installationId", installationId, "defaultBranch", defaultBranch, "policyGateEnabled", policyGateEnabled), client, "/app/scm?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/scm-events/{eventId}/validation-run")
  String linkScmValidation(@PathVariable UUID projectId, @PathVariable UUID eventId, @RequestParam UUID org, @RequestParam UUID validationRunId, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/scm-events/" + eventId + "/validation-run", Map.of("validationRunId", validationRunId), client, "/app/scm?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/notification-channels")
  String createNotificationChannel(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String type, @RequestParam String name, @RequestParam String destination, @RequestParam(required = false) String sharedSecret, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/notification-channels", map("type", type, "name", name, "destination", destination, "sharedSecret", sharedSecret), client, notificationTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/notification-channels/{channelId}/enabled")
  String setNotificationChannelEnabled(@PathVariable UUID projectId, @PathVariable UUID channelId, @RequestParam UUID org, @RequestParam boolean enabled, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutatePut("/api/v1/projects/" + projectId + "/notification-channels/" + channelId, Map.of("enabled", enabled), client, notificationTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/approvals")
  String createApproval(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String sourceType, @RequestParam(required = false) String sourceId, @RequestParam String title, @RequestParam(required = false) String details, @RequestParam int requiredQuorum, @RequestParam(required = false) String requestedApproverSubject, @RequestParam String dueAt, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/approvals", map("sourceType", sourceType, "sourceId", sourceId, "title", title, "details", details, "requiredQuorum", requiredQuorum, "requestedApproverSubject", requestedApproverSubject, "dueAt", retentionInstant(dueAt)), client, notificationTarget(org, projectId), redirect); }
  @PostMapping("/app/approvals/{approvalId}/decision")
  String decideApproval(@PathVariable UUID approvalId, @RequestParam UUID org, @RequestParam UUID project, @RequestParam String decision, @RequestParam(required = false) String comment, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/approvals/" + approvalId + "/decisions", map("decision", decision, "comment", comment), client, notificationTarget(org, project), redirect); }
  @PostMapping("/app/approvals/{approvalId}/delegation")
  String delegateApproval(@PathVariable UUID approvalId, @RequestParam UUID org, @RequestParam UUID project, @RequestParam String delegateSubject, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/approvals/" + approvalId + "/delegation", Map.of("delegateSubject", delegateSubject), client, notificationTarget(org, project), redirect); }
  @PostMapping("/app/projects/{projectId}/security-exceptions")
  String createSecurityException(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String sourceReference, @RequestParam String justification, @RequestParam String expiresAt, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/security-exceptions", Map.of("sourceReference", sourceReference, "justification", justification, "expiresAt", retentionInstant(expiresAt)), client, notificationTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/supply-chain/sboms")
  String uploadSbom(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam(defaultValue = "PROJECT") String accessLevel, @RequestParam(required = false) String releaseReference, @RequestParam(required = false) String digest, @RequestParam("file") org.springframework.web.multipart.MultipartFile file, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) {
    if (file.isEmpty()) return finishMutation("Select a non-empty CycloneDX or SPDX JSON file before upload.", supplyChainTarget(org, projectId), redirect);
    Map<String, String> fields = new LinkedHashMap<>(); fields.put("accessLevel", accessLevel); if (releaseReference != null && !releaseReference.isBlank()) fields.put("releaseReference", releaseReference);
    return finishMutation(api.uploadEvidence("/api/v1/projects/" + projectId + "/supply-chain/sboms", client.getAccessToken().getTokenValue(), file, fields, digest), supplyChainTarget(org, projectId), redirect);
  }
  @PostMapping("/app/projects/{projectId}/supply-chain/provenance")
  String declareProvenance(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam(required = false) String sbomAssetId, @RequestParam(required = false) String attestationEvidenceAssetId, @RequestParam String artifactName, @RequestParam String artifactDigest, @RequestParam String sourceRepository, @RequestParam String sourceRevision, @RequestParam String buildSystem, @RequestParam(required = false) String buildUrl, @RequestParam String signerIdentity, @RequestParam String signatureMethod, @RequestParam(required = false) String attestationReference, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/supply-chain/provenance", map("sbomAssetId", uuidOrNull(sbomAssetId), "attestationEvidenceAssetId", uuidOrNull(attestationEvidenceAssetId), "artifactName", artifactName, "artifactDigest", artifactDigest, "sourceRepository", sourceRepository, "sourceRevision", sourceRevision, "buildSystem", buildSystem, "buildUrl", buildUrl, "signerIdentity", signerIdentity, "signatureMethod", signatureMethod, "attestationReference", attestationReference), client, supplyChainTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/supply-chain/provenance/{recordId}/verification")
  String verifyProvenance(@PathVariable UUID projectId, @PathVariable UUID recordId, @RequestParam UUID org, @RequestParam String status, @RequestParam String note, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/supply-chain/provenance/" + recordId + "/verification", Map.of("status", status, "note", note), client, supplyChainTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/policy-bundles")
  String createPolicyBundle(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String key, @RequestParam String semanticVersion, @RequestParam(required = false) String description, @RequestParam String expression, @RequestParam(defaultValue = "[]") String fixtures, @RequestParam(defaultValue = "true") boolean dryRunDefault, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) {
    try { return mutate("/api/v1/projects/" + projectId + "/policy-bundles", map("key", key, "semanticVersion", semanticVersion, "description", description, "expression", expression, "fixtures", mapper.readTree(fixtures), "dryRunDefault", dryRunDefault), client, policyBundleTarget(org, projectId), redirect); }
    catch (JsonProcessingException ex) { return finishMutation("Fixtures must be a valid JSON array.", policyBundleTarget(org, projectId), redirect); }
  }
  @PostMapping("/app/projects/{projectId}/policy-bundles/{bundleId}/activation")
  String activatePolicyBundle(@PathVariable UUID projectId, @PathVariable UUID bundleId, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/policy-bundles/" + bundleId + "/activate", Map.of(), client, policyBundleTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/policy-bundles/{bundleId}/retirement")
  String retirePolicyBundle(@PathVariable UUID projectId, @PathVariable UUID bundleId, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/policy-bundles/" + bundleId + "/retire", Map.of(), client, policyBundleTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/policy-bundles/{bundleId}/test")
  String testPolicyBundle(@PathVariable UUID projectId, @PathVariable UUID bundleId, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/policy-bundles/" + bundleId + "/test", Map.of(), client, policyBundleTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/policy-bundles/{bundleId}/evaluate")
  String evaluatePolicyBundle(@PathVariable UUID projectId, @PathVariable UUID bundleId, @RequestParam UUID org, @RequestParam String context, @RequestParam(defaultValue = "true") boolean dryRun, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) {
    try { return mutate("/api/v1/projects/" + projectId + "/policy-bundles/" + bundleId + "/evaluate", map("context", mapper.readTree(context), "dryRun", dryRun), client, policyBundleTarget(org, projectId), redirect); }
    catch (JsonProcessingException ex) { return finishMutation("Evaluation context must be a valid JSON object.", policyBundleTarget(org, projectId), redirect); }
  }
  @PostMapping("/app/projects/{projectId}/agent-governance/prompt-templates")
  String registerAgentPromptTemplate(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String templateKey, @RequestParam String semanticVersion, @RequestParam String displayName, @RequestParam(required = false) String sourceReference, @RequestParam String templateSha256, @RequestParam(required = false) String classification, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/agent-governance/prompt-templates", map("templateKey", templateKey, "semanticVersion", semanticVersion, "displayName", displayName, "sourceReference", sourceReference, "templateSha256", templateSha256, "classification", classification), client, agentGovernanceTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/agent-governance/sessions")
  String declareAgentSession(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam(required = false) String promptTemplateId, @RequestParam String agentIdentity, @RequestParam String provider, @RequestParam String modelName, @RequestParam String modelVersion, @RequestParam String sessionFingerprint, @RequestParam(required = false) String contextSha256, @RequestParam(defaultValue = "0") int toolInvocationCount, @RequestParam(required = false) String toolInvocationSha256, @RequestParam(required = false) String purpose, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/agent-governance/sessions", map("promptTemplateId", uuidOrNull(promptTemplateId), "agentIdentity", agentIdentity, "provider", provider, "modelName", modelName, "modelVersion", modelVersion, "sessionFingerprint", sessionFingerprint, "contextSha256", contextSha256, "toolInvocationCount", toolInvocationCount, "toolInvocationSha256", toolInvocationSha256, "purpose", purpose), client, agentGovernanceTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/agent-governance/sessions/{sessionId}/{action}")
  String transitionAgentSession(@PathVariable UUID projectId, @PathVariable UUID sessionId, @PathVariable String action, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { if (!Set.of("complete", "block").contains(action)) return finishMutation("Unsupported agent session transition.", agentGovernanceTarget(org, projectId), redirect); return mutate("/api/v1/projects/" + projectId + "/agent-governance/sessions/" + sessionId + "/" + action, Map.of(), client, agentGovernanceTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/agent-governance/sessions/{sessionId}/evidence")
  String declareAgentEvidence(@PathVariable UUID projectId, @PathVariable UUID sessionId, @RequestParam UUID org, @RequestParam(required = false) String validationRunId, @RequestParam(required = false) String evidenceAssetId, @RequestParam String changeReference, @RequestParam String generatedChangeSha256, @RequestParam String policyDecision, @RequestParam(required = false) String policyReference, @RequestParam String approvalTitle, @RequestParam(required = false) String approvalDetails, @RequestParam(defaultValue = "1") int requiredQuorum, @RequestParam(required = false) String requestedApprover, @RequestParam String approvalDueAt, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/agent-governance/sessions/" + sessionId + "/evidence", map("validationRunId", uuidOrNull(validationRunId), "evidenceAssetId", uuidOrNull(evidenceAssetId), "changeReference", changeReference, "generatedChangeSha256", generatedChangeSha256, "policyDecision", policyDecision, "policyReference", policyReference, "approvalTitle", approvalTitle, "approvalDetails", approvalDetails, "requiredQuorum", requiredQuorum, "requestedApprover", requestedApprover, "approvalDueAt", retentionInstant(approvalDueAt)), client, agentGovernanceTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/risk-intelligence/recompute")
  String recomputeRiskIntelligence(@PathVariable UUID projectId, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/risk-intelligence/recompute", Map.of(), client, riskIntelligenceTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/quality-metrics")
  String writeMetrics(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String periodStart, @RequestParam String periodEnd, @RequestParam(required = false) BigDecimal deploymentFrequency, @RequestParam(required = false) BigDecimal leadTimeHours, @RequestParam(required = false) BigDecimal changeFailureRate, @RequestParam(required = false) BigDecimal prReviewTimeDeltaHours, @RequestParam(required = false) BigDecimal reworkRate, @RequestParam(required = false) BigDecimal reviewQueueHealth, @RequestParam(required = false) BigDecimal specAlignmentScore, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutate("/api/v1/projects/" + projectId + "/quality-metrics", map("periodStart", Instant.parse(periodStart), "periodEnd", Instant.parse(periodEnd), "deploymentFrequency", deploymentFrequency, "leadTimeHours", leadTimeHours, "changeFailureRate", changeFailureRate, "prReviewTimeDeltaHours", prReviewTimeDeltaHours, "reworkRate", reworkRate, "reviewQueueHealth", reviewQueueHealth, "specAlignmentScore", specAlignmentScore), client, "/app/quality?org=" + org + "&project=" + projectId, redirect); }
  @PostMapping("/app/projects/{projectId}/validation-runs/{runId}/findings/{findingId}/triage")
  String triageFinding(@PathVariable UUID projectId, @PathVariable UUID runId, @PathVariable UUID findingId, @RequestParam UUID org, @RequestParam String status, @RequestParam(required = false) String note, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutatePut("/api/v1/projects/" + projectId + "/validation-runs/" + runId + "/findings/" + findingId + "/triage", Map.of("status", status, "note", note == null ? "" : note), client, validationDetailTarget(org, projectId, runId), redirect); }
  @PostMapping("/app/projects/{projectId}/validation-runs/{runId}/evidence/{evidenceId}/retention")
  String setEvidenceRetention(@PathVariable UUID projectId, @PathVariable UUID runId, @PathVariable UUID evidenceId, @RequestParam UUID org, @RequestParam String retentionUntil, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutatePut("/api/v1/projects/" + projectId + "/validation-runs/" + runId + "/evidence/" + evidenceId + "/retention", Map.of("retentionUntil", Instant.parse(retentionUntil)), client, validationDetailTarget(org, projectId, runId), redirect); }
  @PostMapping("/app/projects/{projectId}/evidence-assets")
  String uploadEvidenceAsset(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String assetType, @RequestParam String accessLevel, @RequestParam(required = false) String validationEvidenceId, @RequestParam(required = false) String digest, @RequestParam("file") org.springframework.web.multipart.MultipartFile file, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) {
    if (file.isEmpty()) return finishMutation("Select a non-empty evidence file before upload.", evidenceTarget(org, projectId), redirect);
    Map<String, String> fields = new LinkedHashMap<>(); fields.put("assetType", assetType); fields.put("accessLevel", accessLevel); if (validationEvidenceId != null && !validationEvidenceId.isBlank()) fields.put("validationEvidenceId", validationEvidenceId);
    return finishMutation(api.uploadEvidence("/api/v1/projects/" + projectId + "/evidence-assets", client.getAccessToken().getTokenValue(), file, fields, digest), evidenceTarget(org, projectId), redirect);
  }
  @PostMapping("/app/projects/{projectId}/evidence-assets/{assetId}/retention")
  String lockEvidenceAsset(@PathVariable UUID projectId, @PathVariable UUID assetId, @RequestParam UUID org, @RequestParam String mode, @RequestParam String retentionUntil, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutatePut("/api/v1/projects/" + projectId + "/evidence-assets/" + assetId + "/retention", Map.of("mode", mode, "retentionUntil", retentionInstant(retentionUntil)), client, evidenceTarget(org, projectId), redirect); }
  @PostMapping("/app/projects/{projectId}/evidence-assets/{assetId}/delete")
  String deleteEvidenceAsset(@PathVariable UUID projectId, @PathVariable UUID assetId, @RequestParam UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) { return mutateDelete("/api/v1/projects/" + projectId + "/evidence-assets/" + assetId, client, evidenceTarget(org, projectId), redirect); }
  @GetMapping("/app/projects/{projectId}/evidence-assets/{assetId}/download")
  String downloadEvidenceAsset(@PathVariable UUID projectId, @PathVariable UUID assetId, @RequestParam(required = false) UUID org, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) {
    ManagementApiClient.ObjectData detail = api.object("/api/v1/projects/" + projectId + "/evidence-assets/" + assetId, client.getAccessToken().getTokenValue());
    if (ManagementApiClient.requiresSessionRecovery(detail.error())) return "redirect:/session-expired";
    Object url = detail.value().get("downloadUrl");
    if (detail.hasError() || !(url instanceof String signedUrl) || signedUrl.isBlank()) return finishMutation(detail.error() == null ? "Evidence download authorization could not be created." : detail.error(), evidenceTarget(org, projectId), redirect);
    return "redirect:" + signedUrl;
  }

  /**
   * Create a traceability node.
   *
   * <p>The graph was read-only in the portal while the API had accepted writes all along, so the screen showed
   * "awaiting governed links" with no way to add one and the only route in was the CLI or a raw API call. A link is a
   * deliberate human assertion — the platform never infers one — so a form is the right shape for it.
   */
  @PostMapping("/app/projects/{projectId}/trace/nodes")
  String createTraceNode(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam String type,
      @RequestParam String externalKey, @RequestParam String label,
      @RequestParam(required = false) String status,
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", type);
    payload.put("externalKey", externalKey);
    payload.put("label", label);
    // Status is optional. An empty form field is omitted rather than sent as "": the API accepts an empty string
    // (@Size(max = 50) permits it, verified against the running service), so this is not about avoiding a rejection —
    // it is so an unset status is stored as absent instead of as a value that happens to be empty.
    if (status != null && !status.isBlank()) payload.put("status", status);
    return mutate("/api/v1/projects/" + projectId + "/trace/nodes", payload, client,
        traceTarget(org, projectId), redirect);
  }

  @PostMapping("/app/projects/{projectId}/trace/edges")
  String createTraceEdge(@PathVariable UUID projectId, @RequestParam UUID org, @RequestParam UUID sourceNodeId,
      @RequestParam UUID targetNodeId, @RequestParam String relation,
      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, RedirectAttributes redirect) {
    return mutate("/api/v1/projects/" + projectId + "/trace/edges",
        Map.of("sourceNodeId", sourceNodeId, "targetNodeId", targetNodeId, "relation", relation), client,
        traceTarget(org, projectId), redirect);
  }

  private String mutate(String path, Map<String, Object> payload, OAuth2AuthorizedClient client, String target, RedirectAttributes redirect) { return finishMutation(api.post(path, client.getAccessToken().getTokenValue(), payload), target, redirect); }
  private String mutatePut(String path, Map<String, Object> payload, OAuth2AuthorizedClient client, String target, RedirectAttributes redirect) { return finishMutation(api.put(path, client.getAccessToken().getTokenValue(), payload), target, redirect); }
  private String mutateDelete(String path, OAuth2AuthorizedClient client, String target, RedirectAttributes redirect) { return finishMutation(api.delete(path, client.getAccessToken().getTokenValue()), target, redirect); }
  private String finishMutation(String error, String target, RedirectAttributes redirect) { if (ManagementApiClient.requiresSessionRecovery(error)) return "redirect:/session-expired"; if (error == null) redirect.addFlashAttribute("flashSuccess", "Governed change recorded in the audit ledger."); else redirect.addFlashAttribute("flashError", error); return "redirect:" + target; }
  private Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) if (values[i + 1] != null) result.put(String.valueOf(values[i]), values[i + 1]); return result; }
  private List<String> errors(String... values) { return Arrays.stream(values).filter(Objects::nonNull).distinct().toList(); }
  private String optional(String key, String value) { return value == null || value.isBlank() ? "" : "&" + key + "=" + value; }
  private String validationDetailTarget(UUID org, UUID project, UUID run) { return "/app/validations?org=" + org + "&project=" + project + "&run=" + run; }
  private String traceTarget(UUID org, UUID project) { return "/app/traceability?org=" + org + "&project=" + project; }
  private String evidenceTarget(UUID org, UUID project) { return "/app/evidence" + (org == null ? "" : "?org=" + org + "&project=" + project); }
  private String notificationTarget(UUID org, UUID project) { return "/app/notifications?org=" + org + "&project=" + project; }
  private String supplyChainTarget(UUID org, UUID project) { return "/app/supply-chain?org=" + org + "&project=" + project; }
  private String policyBundleTarget(UUID org, UUID project) { return "/app/policy-as-code?org=" + org + "&project=" + project; }
  private String agentGovernanceTarget(UUID org, UUID project) { return "/app/agent-governance?org=" + org + "&project=" + project; }
  private String riskIntelligenceTarget(UUID org, UUID project) { return "/app/risk-intelligence?org=" + org + "&project=" + project; }
  private UUID uuidOrNull(String value) { return value == null || value.isBlank() ? null : UUID.fromString(value); }
  private Instant instantOrNull(String value) { return value == null || value.isBlank() ? null : Instant.parse(value); }
  private Instant retentionInstant(String value) { try { return Instant.parse(value); } catch (RuntimeException ignored) { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC); } }
  private String json(Object value) { try { return mapper.writeValueAsString(value == null ? List.of() : value); } catch (JsonProcessingException ignored) { return "[]"; } }
}
