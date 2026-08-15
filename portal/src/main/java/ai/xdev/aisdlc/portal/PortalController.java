package ai.xdev.aisdlc.portal;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PortalController {
  private static final Set<String> PAGES = Set.of("overview", "projects", "kits", "validations", "traceability", "governance", "reviews", "quality", "audit");
  private final ManagementApiClient api;
  private final ReactAssetService reactAssets;
  private final ObjectMapper mapper = new ObjectMapper();
  public PortalController(ManagementApiClient api, ReactAssetService reactAssets) { this.api = api; this.reactAssets = reactAssets; }

  @GetMapping("/")
  String landing() { return "landing"; }

  @GetMapping({"/app", "/app/{page}"})
  String app(@PathVariable(required = false) String page, @RequestParam(required = false) UUID org, @RequestParam(required = false) UUID project,
             @AuthenticationPrincipal OidcUser user, @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, Model model) {
    String view = page == null ? "overview" : page;
    if (!PAGES.contains(view)) view = "overview";
    String token = client.getAccessToken().getTokenValue();
    model.addAttribute("page", view);
    model.addAttribute("organizationId", org == null ? "" : org.toString());
    model.addAttribute("projectId", project == null ? "" : project.toString());
    model.addAttribute("userName", Optional.ofNullable(user.getFullName()).orElse(user.getPreferredUsername()));
    model.addAttribute("projects", org == null ? List.of() : api.list("/api/v1/organizations/" + org + "/projects", token));
    model.addAttribute("kits", org == null ? List.of() : api.list("/api/v1/organizations/" + org + "/spec-kits", token));
    model.addAttribute("validations", project == null ? List.of() : api.list("/api/v1/projects/" + project + "/validation-runs", token));
    model.addAttribute("policies", project == null ? List.of() : api.list("/api/v1/projects/" + project + "/policies", token));
    model.addAttribute("constitutions", project == null ? List.of() : api.list("/api/v1/projects/" + project + "/constitutions", token));
    model.addAttribute("reviews", project == null ? List.of() : api.list("/api/v1/projects/" + project + "/review-items", token));
    model.addAttribute("metrics", project == null ? List.of() : api.list("/api/v1/projects/" + project + "/quality-metrics", token));
    model.addAttribute("audit", org == null ? List.of() : api.list("/api/v1/organizations/" + org + "/audit-events", token));
    model.addAttribute("trace", project == null ? Map.of("nodes", List.of(), "edges", List.of()) : api.trace("/api/v1/projects/" + project + "/traceability", token));
    model.addAttribute("metricsJson", json(model.getAttribute("metrics")));
    model.addAttribute("traceJson", json(model.getAttribute("trace")));
    model.addAttribute("validationsJson", json(model.getAttribute("validations")));
    model.addAttribute("reviewsJson", json(model.getAttribute("reviews")));
    model.addAttribute("reviewIslandJson", json(Map.of(
        "reviews", model.getAttribute("reviews"),
        "organizationId", org == null ? "" : org.toString(),
        "projectId", project == null ? "" : project.toString())));
    model.addAttribute("reactEntry", reactAssets.entry());
    return "app";
  }

  @GetMapping("/app/fragments/quality")
  String qualityFragment(@RequestParam(required = false) UUID org, @RequestParam(required = false) UUID project,
                         @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client, Model model) {
    String token = client.getAccessToken().getTokenValue();
    var metrics = project == null ? List.of() : api.list("/api/v1/projects/" + project + "/quality-metrics", token);
    model.addAttribute("organizationId", org == null ? "" : org.toString());
    model.addAttribute("projectId", project == null ? "" : project.toString());
    model.addAttribute("metrics", metrics);
    model.addAttribute("metricsJson", json(metrics));
    return "fragments :: qualitySignals";
  }

  @PostMapping("/app/projects/{projectId}/review-items/{reviewId}/decision")
  String decideReview(@PathVariable UUID projectId, @PathVariable UUID reviewId, @RequestParam UUID org,
                      @RequestParam String decision, @RequestParam(required = false) String note,
                      @RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client) {
    if (!Set.of("APPROVED", "REJECTED").contains(decision)) throw new IllegalArgumentException("Decision must be APPROVED or REJECTED");
    api.post("/api/v1/projects/" + projectId + "/review-items/" + reviewId + "/decision", client.getAccessToken().getTokenValue(),
        Map.of("decision", decision, "note", note == null ? "" : note));
    return "redirect:/app/reviews?org=" + org + "&project=" + projectId;
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value == null ? List.of() : value); }
    catch (JsonProcessingException ignored) { return "[]"; }
  }
}
