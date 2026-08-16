package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scm_repository_links", uniqueConstraints = {
    @UniqueConstraint(name = "scm_repository_provider_name_uq", columnNames = {"provider", "repository_full_name"}),
    @UniqueConstraint(name = "scm_repository_project_provider_name_uq", columnNames = {"project_id", "provider", "repository_full_name"})
})
public class ScmRepositoryLink {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private DomainTypes.ScmProvider provider;
  @Column(name = "repository_full_name", nullable = false, length = 300) private String repositoryFullName;
  @Column(name = "installation_id") private Long installationId;
  @Column(name = "default_branch", length = 255) private String defaultBranch;
  @Column(name = "policy_gate_enabled", nullable = false) private boolean policyGateEnabled = true;
  @Column(name = "created_by", nullable = false, length = 120) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

  protected ScmRepositoryLink() {}
  public ScmRepositoryLink(UUID projectId, DomainTypes.ScmProvider provider, String repositoryFullName, Long installationId, String defaultBranch, boolean policyGateEnabled, String createdBy) {
    this.projectId = projectId;
    this.provider = provider;
    this.repositoryFullName = repositoryFullName;
    this.installationId = installationId;
    this.defaultBranch = defaultBranch;
    this.policyGateEnabled = policyGateEnabled;
    this.createdBy = createdBy;
  }
  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public DomainTypes.ScmProvider getProvider() { return provider; }
  public String getRepositoryFullName() { return repositoryFullName; }
  public Long getInstallationId() { return installationId; }
  public String getDefaultBranch() { return defaultBranch; }
  public boolean isPolicyGateEnabled() { return policyGateEnabled; }
  public String getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
}
