package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import ai.xdev.aisdlc.domain.DomainTypes.ProjectStatus;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.service.AuditService;
import ai.xdev.aisdlc.service.ProjectAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProjectController {
  private final OrganizationRepository organizations;
  private final ProjectRepository projects;
  private final MembershipRepository memberships;
  private final ProjectAccessService access;
  private final AuditService audit;

  public ProjectController(OrganizationRepository organizations, ProjectRepository projects, MembershipRepository memberships, ProjectAccessService access, AuditService audit) {
    this.organizations = organizations;
    this.projects = projects;
    this.memberships = memberships;
    this.access = access;
    this.audit = audit;
  }

  public record OrganizationInput(@NotBlank @Pattern(regexp = "[a-z0-9-]{3,80}") String slug, @NotBlank @Size(max = 160) String name) {}
  public record ProjectInput(@NotBlank @Pattern(regexp = "[a-z0-9-]{3,80}") String slug, @NotBlank @Size(max = 160) String name, @Size(max = 4000) String description) {}
  public record MembershipInput(@NotBlank @Size(max = 120) String subject, @NotNull MembershipRole role) {}
  public record MembershipRoleInput(@NotNull MembershipRole role) {}
  public record OrganizationView(UUID id, String slug, String name) {}
  public record ProjectView(UUID id, UUID organizationId, String slug, String name, String description, ProjectStatus status) {}
  public record MembershipView(UUID id, UUID projectId, String subject, MembershipRole role, java.time.Instant createdAt) {}

  @PostMapping("/organizations") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')") @Transactional
  OrganizationView createOrganization(@RequestBody @Valid OrganizationInput input, @AuthenticationPrincipal Jwt jwt) {
    Organization org = organizations.save(new Organization(input.slug(), input.name()));
    audit.append(org.getId(), null, jwt.getSubject(), "organization.created", "organization", org.getId().toString(), "{\"slug\":\"" + escape(input.slug()) + "\"}");
    return view(org);
  }

  @GetMapping("/organizations") @PreAuthorize("hasRole('admin')")
  PageResponse<OrganizationView> listOrganizations(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "createdAt,desc") String sort) {
    return PageResponse.from(organizations.findAll(PageRequests.of(page, size, sort, "createdAt", "slug", "name")).map(this::view));
  }

  @GetMapping("/organizations/{organizationId}/projects") @PreAuthorize("hasRole('admin')")
  PageResponse<ProjectView> listProjects(@PathVariable UUID organizationId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(defaultValue = "createdAt,desc") String sort) {
    requireOrganization(organizationId);
    return PageResponse.from(projects.findByOrganizationId(organizationId, PageRequests.of(page, size, sort, "createdAt", "slug", "name", "status")).map(this::view));
  }

  @PostMapping("/organizations/{organizationId}/projects") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')") @Transactional
  ProjectView createProject(@PathVariable UUID organizationId, @RequestBody @Valid ProjectInput input, @AuthenticationPrincipal Jwt jwt) {
    requireOrganization(organizationId);
    Project project = projects.save(new Project(organizationId, input.slug(), input.name(), input.description()));
    memberships.save(new ProjectMembership(project.getId(), jwt.getSubject(), MembershipRole.OWNER));
    audit.append(organizationId, project.getId(), jwt.getSubject(), "project.created", "project", project.getId().toString(), "{\"slug\":\"" + escape(input.slug()) + "\"}");
    return view(project);
  }

  @GetMapping("/projects/{projectId}")
  ProjectView getProject(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
    return view(access.requireMembership(projectId, jwt.getSubject(), MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER));
  }

  @GetMapping("/projects/{projectId}/memberships")
  List<MembershipView> memberships(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
    access.requireMembership(projectId, jwt.getSubject(), MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return memberships.findByProjectIdOrderByCreatedAtAsc(projectId).stream().map(this::view).toList();
  }

  @PostMapping("/projects/{projectId}/memberships") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')") @Transactional
  MembershipView inviteMembership(@PathVariable UUID projectId, @RequestBody @Valid MembershipInput input, @AuthenticationPrincipal Jwt jwt) {
    Project project = requireProject(projectId);
    if (memberships.findByProjectIdAndSubject(projectId, input.subject()).isPresent()) throw new IllegalStateException("Member already belongs to this project");
    ProjectMembership membership = memberships.save(new ProjectMembership(projectId, input.subject(), input.role()));
    audit.append(project.getOrganizationId(), projectId, jwt.getSubject(), "membership.created", "project_membership", membership.getId().toString(), "{\"subject\":\"" + escape(input.subject()) + "\",\"role\":\"" + input.role().name() + "\"}");
    return view(membership);
  }

  @PutMapping("/projects/{projectId}/memberships/{membershipId}") @PreAuthorize("hasRole('admin')") @Transactional
  MembershipView changeMembershipRole(@PathVariable UUID projectId, @PathVariable UUID membershipId, @RequestBody @Valid MembershipRoleInput input, @AuthenticationPrincipal Jwt jwt) {
    Project project = requireProject(projectId);
    ProjectMembership membership = memberships.findById(membershipId).filter(item -> item.getProjectId().equals(projectId)).orElseThrow(() -> new IllegalArgumentException("Membership not found"));
    if (membership.getRole() == MembershipRole.OWNER && input.role() != MembershipRole.OWNER && memberships.countByProjectIdAndRole(projectId, MembershipRole.OWNER) <= 1) throw new IllegalStateException("A project must retain at least one owner");
    membership.changeRole(input.role());
    ProjectMembership saved = memberships.save(membership);
    audit.append(project.getOrganizationId(), projectId, jwt.getSubject(), "membership.role_changed", "project_membership", membershipId.toString(), "{\"role\":\"" + input.role().name() + "\"}");
    return view(saved);
  }

  @DeleteMapping("/projects/{projectId}/memberships/{membershipId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('admin')") @Transactional
  void removeMembership(@PathVariable UUID projectId, @PathVariable UUID membershipId, @AuthenticationPrincipal Jwt jwt) {
    Project project = requireProject(projectId);
    ProjectMembership membership = memberships.findById(membershipId).filter(item -> item.getProjectId().equals(projectId)).orElseThrow(() -> new IllegalArgumentException("Membership not found"));
    if (membership.getRole() == MembershipRole.OWNER && memberships.countByProjectIdAndRole(projectId, MembershipRole.OWNER) <= 1) throw new IllegalStateException("A project must retain at least one owner");
    memberships.delete(membership);
    audit.append(project.getOrganizationId(), projectId, jwt.getSubject(), "membership.removed", "project_membership", membershipId.toString(), "{\"subject\":\"" + escape(membership.getSubject()) + "\"}");
  }

  private Organization requireOrganization(UUID organizationId) {
    return organizations.findById(organizationId).orElseThrow(() -> new IllegalArgumentException("Organization not found"));
  }

  private Project requireProject(UUID projectId) {
    return projects.findById(projectId).orElseThrow(() -> new IllegalArgumentException("Project not found"));
  }

  private OrganizationView view(Organization org) { return new OrganizationView(org.getId(), org.getSlug(), org.getName()); }
  private ProjectView view(Project project) { return new ProjectView(project.getId(), project.getOrganizationId(), project.getSlug(), project.getName(), project.getDescription(), project.getStatus()); }
  private MembershipView view(ProjectMembership membership) { return new MembershipView(membership.getId(), membership.getProjectId(), membership.getSubject(), membership.getRole(), membership.getCreatedAt()); }
  private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
