package ai.xdev.aisdlc.portal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
  private static final Set<String> PAGES = Set.of("overview", "projects", "kits", "validations", "evidence", "traceability", "governance", "reviews", "quality", "audit");
  private final ManagementApiClient api;
  private final ReactAssetService reactAssets;
  private final ObjectMapper mapper = new ObjectMapper();
  public PortalController(ManagementApiClient api, ReactAssetService reactAssets) { this.api = api; this.reactAssets = reactAssets; }

  @GetMapping("/") String landing() { return "landing"; }

  @GetMapping({"/app", "/app/{page}"})
  String app(@PathVariable(required = false) String page, @RequestParam(required = false) UUID org, @RequestParam(required = false) UUID project,
      @RequestParam(defaultValue = "0") int p, @RequestParam(required = false) String filter, @RequestParam(required = false) UUID run,
      @AuthenticationPrincipal OidcUser user, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, Model model) {
    String view = page == null || !PAGES.contains(page) ? "overview" : page;
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
    ManagementApiClient.ObjectData trace = project == null ? ManagementApiClient.ObjectData.empty() : api.trace("/api/v1/projects/" + project + "/traceability", token);
    ManagementApiClient.ObjectData auditVerification = org == null ? ManagementApiClient.ObjectData.empty() : api.object("/api/v1/organizations/" + org + "/audit-events/verify", token);
    ManagementApiClient.ObjectData validationDetail = project == null || run == null ? ManagementApiClient.ObjectData.empty() : api.object("/api/v1/projects/" + project + "/validation-runs/" + run, token);

    model.addAttribute("page", view); model.addAttribute("pageNumber", p); model.addAttribute("filter", filter == null ? "" : filter);
    model.addAttribute("organizationId", org == null ? "" : org.toString()); model.addAttribute("projectId", project == null ? "" : project.toString());
    model.addAttribute("userName", Optional.ofNullable(user.getFullName()).orElse(user.getPreferredUsername()));
    model.addAttribute("organizations", organizations.items()); model.addAttribute("projects", projects.items()); model.addAttribute("kits", kits.items()); model.addAttribute("validations", validations.items()); model.addAttribute("evidenceAssets", evidenceAssets.items()); model.addAttribute("policies", policies.items()); model.addAttribute("constitutions", constitutions.items()); model.addAttribute("reviews", reviews.items()); model.addAttribute("metrics", metrics.items()); model.addAttribute("capabilities", capabilities.items()); model.addAttribute("audit", audit.items()); model.addAttribute("memberships", memberships.items()); model.addAttribute("projectKits", projectKits.items()); model.addAttribute("exceptions", exceptions.items());
    model.addAttribute("organizationsPage", organizations); model.addAttribute("projectsPage", projects); model.addAttribute("kitsPage", kits); model.addAttribute("validationsPage", validations); model.addAttribute("evidenceAssetsPage", evidenceAssets); model.addAttribute("policiesPage", policies); model.addAttribute("constitutionsPage", constitutions); model.addAttribute("reviewsPage", reviews); model.addAttribute("metricsPage", metrics); model.addAttribute("capabilitiesPage", capabilities); model.addAttribute("auditPage", audit); model.addAttribute("exceptionsPage", exceptions);
    model.addAttribute("trace", trace.value()); model.addAttribute("auditVerification", auditVerification.value()); model.addAttribute("validationDetail", validationDetail.value()); model.addAttribute("selectedRunId", run == null ? "" : run.toString());
    model.addAttribute("apiErrors", errors(organizations.error(), projects.error(), kits.error(), validations.error(), evidenceAssets.error(), policies.error(), constitutions.error(), reviews.error(), metrics.error(), capabilities.error(), audit.error(), memberships.error(), projectKits.error(), exceptions.error(), trace.error(), auditVerification.error(), validationDetail.error()));
    model.addAttribute("metricsJson", json(metrics.items())); model.addAttribute("traceJson", json(trace.value())); model.addAttribute("validationsJson", json(validations.items()));
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
    Object url = detail.value().get("downloadUrl");
    if (detail.hasError() || !(url instanceof String signedUrl) || signedUrl.isBlank()) return finishMutation(detail.error() == null ? "Evidence download authorization could not be created." : detail.error(), evidenceTarget(org, projectId), redirect);
    return "redirect:" + signedUrl;
  }

  private String mutate(String path, Map<String, Object> payload, OAuth2AuthorizedClient client, String target, RedirectAttributes redirect) { return finishMutation(api.post(path, client.getAccessToken().getTokenValue(), payload), target, redirect); }
  private String mutatePut(String path, Map<String, Object> payload, OAuth2AuthorizedClient client, String target, RedirectAttributes redirect) { return finishMutation(api.put(path, client.getAccessToken().getTokenValue(), payload), target, redirect); }
  private String mutateDelete(String path, OAuth2AuthorizedClient client, String target, RedirectAttributes redirect) { return finishMutation(api.delete(path, client.getAccessToken().getTokenValue()), target, redirect); }
  private String finishMutation(String error, String target, RedirectAttributes redirect) { if (error == null) redirect.addFlashAttribute("flashSuccess", "Governed change recorded in the audit ledger."); else redirect.addFlashAttribute("flashError", error); return "redirect:" + target; }
  private Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) if (values[i + 1] != null) result.put(String.valueOf(values[i]), values[i + 1]); return result; }
  private List<String> errors(String... values) { return Arrays.stream(values).filter(Objects::nonNull).distinct().toList(); }
  private String optional(String key, String value) { return value == null || value.isBlank() ? "" : "&" + key + "=" + value; }
  private String validationDetailTarget(UUID org, UUID project, UUID run) { return "/app/validations?org=" + org + "&project=" + project + "&run=" + run; }
  private String evidenceTarget(UUID org, UUID project) { return "/app/evidence" + (org == null ? "" : "?org=" + org + "&project=" + project); }
  private UUID uuidOrNull(String value) { return value == null || value.isBlank() ? null : UUID.fromString(value); }
  private Instant instantOrNull(String value) { return value == null || value.isBlank() ? null : Instant.parse(value); }
  private Instant retentionInstant(String value) { try { return Instant.parse(value); } catch (RuntimeException ignored) { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC); } }
  private String json(Object value) { try { return mapper.writeValueAsString(value == null ? List.of() : value); } catch (JsonProcessingException ignored) { return "[]"; } }
}
