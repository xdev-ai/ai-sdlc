package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import ai.xdev.aisdlc.domain.DomainTypes.ProjectStatus;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.service.AuditService;
import ai.xdev.aisdlc.service.ProjectAccessService;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProjectController {
  private final OrganizationRepository organizations; private final ProjectRepository projects; private final MembershipRepository memberships; private final ProjectAccessService access; private final AuditService audit;
  public ProjectController(OrganizationRepository organizations, ProjectRepository projects, MembershipRepository memberships, ProjectAccessService access, AuditService audit) { this.organizations = organizations; this.projects = projects; this.memberships = memberships; this.access = access; this.audit = audit; }
  public record OrganizationInput(@NotBlank @Pattern(regexp = "[a-z0-9-]{3,80}") String slug, @NotBlank @Size(max = 160) String name) {}
  public record ProjectInput(@NotBlank @Pattern(regexp = "[a-z0-9-]{3,80}") String slug, @NotBlank @Size(max = 160) String name, @Size(max = 4000) String description) {}
  public record OrganizationView(UUID id, String slug, String name) {}
  public record ProjectView(UUID id, UUID organizationId, String slug, String name, String description, ProjectStatus status) {}

  @PostMapping("/organizations") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  OrganizationView createOrganization(@RequestBody @jakarta.validation.Valid OrganizationInput input, @AuthenticationPrincipal Jwt jwt) {
    Organization org = organizations.save(new Organization(input.slug(), input.name()));
    audit.append(org.getId(), null, jwt.getSubject(), "organization.created", "organization", org.getId().toString(), "{\"slug\":\"" + input.slug() + "\"}");
    return new OrganizationView(org.getId(), org.getSlug(), org.getName());
  }
  @GetMapping("/organizations/{organizationId}/projects")
  List<ProjectView> listProjects(@PathVariable UUID organizationId) { return projects.findByOrganizationId(organizationId).stream().map(this::view).toList(); }
  @PostMapping("/organizations/{organizationId}/projects") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  ProjectView createProject(@PathVariable UUID organizationId, @RequestBody @jakarta.validation.Valid ProjectInput input, @AuthenticationPrincipal Jwt jwt) {
    organizations.findById(organizationId).orElseThrow(() -> new IllegalArgumentException("Organization not found"));
    Project project = projects.save(new Project(organizationId, input.slug(), input.name(), input.description()));
    memberships.save(new ProjectMembership(project.getId(), jwt.getSubject(), MembershipRole.OWNER));
    audit.append(organizationId, project.getId(), jwt.getSubject(), "project.created", "project", project.getId().toString(), "{\"slug\":\"" + input.slug() + "\"}");
    return view(project);
  }
  @GetMapping("/projects/{projectId}")
  ProjectView getProject(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return view(access.requireMembership(projectId, jwt.getSubject(), MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER)); }
  private ProjectView view(Project project) { return new ProjectView(project.getId(), project.getOrganizationId(), project.getSlug(), project.getName(), project.getDescription(), project.getStatus()); }
}
