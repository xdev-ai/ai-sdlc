package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import ai.xdev.aisdlc.domain.Project;
import ai.xdev.aisdlc.repo.Repositories.MembershipRepository;
import ai.xdev.aisdlc.repo.Repositories.ProjectRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ProjectAccessService {
  private final ProjectRepository projects;
  private final MembershipRepository memberships;
  public ProjectAccessService(ProjectRepository projects, MembershipRepository memberships) { this.projects = projects; this.memberships = memberships; }

  public Project requireProject(UUID projectId) { return projects.findById(projectId).orElseThrow(() -> new IllegalArgumentException("Project not found")); }

  public Project requireMembership(UUID projectId, String subject, MembershipRole... allowed) {
    Project project = requireProject(projectId);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean admin = authentication != null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_admin"));
    if (admin) return project;
    MembershipRole actual = memberships.findByProjectIdAndSubject(projectId, subject).map(m -> m.getRole()).orElseThrow(() -> new SecurityException("Project membership is required"));
    for (MembershipRole role : allowed) if (role == actual) return project;
    throw new SecurityException("Project membership role does not grant this operation");
  }
}

