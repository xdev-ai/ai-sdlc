package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_memberships", uniqueConstraints = @UniqueConstraint(name = "project_subject_uq", columnNames = {"project_id", "subject"}))
public class ProjectMembership {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(nullable = false, length = 120) private String subject;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private DomainTypes.MembershipRole role;
  @Column(nullable = false) private Instant createdAt = Instant.now();
  protected ProjectMembership() {}
  public ProjectMembership(UUID tenantId, UUID projectId, String subject, DomainTypes.MembershipRole role) { this.tenantId = tenantId; this.projectId = projectId; this.subject = subject; this.role = role; }
  /** Test-only compatibility constructor; production creation resolves the project tenant. */
  public ProjectMembership(UUID projectId, String subject, DomainTypes.MembershipRole role) { this(UUID.fromString("00000000-0000-0000-0000-000000000001"), projectId, subject, role); }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getProjectId() { return projectId; }
  public String getSubject() { return subject; }
  public DomainTypes.MembershipRole getRole() { return role; }
  public Instant getCreatedAt() { return createdAt; }
  public void changeRole(DomainTypes.MembershipRole role) { this.role = role; }
}
