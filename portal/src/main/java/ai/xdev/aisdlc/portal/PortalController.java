package ai.xdev.aisdlc.portal;

import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PortalController {
  private static final Set<String> PAGES = Set.of("overview", "projects", "kits", "validations", "traceability", "governance", "reviews", "quality", "audit");
  private final ManagementApiClient api;
  public PortalController(ManagementApiClient api) { this.api = api; }

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
    return "app";
  }
}

