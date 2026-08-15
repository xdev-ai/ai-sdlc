package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects", uniqueConstraints = @UniqueConstraint(name = "project_org_slug_uq", columnNames = {"organization_id", "slug"}))
public class Project {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "organization_id", nullable = false) private UUID organizationId;
  @Column(nullable = false, length = 80) private String slug;
  @Column(nullable = false, length = 160) private String name;
  @Column(columnDefinition = "text") private String description;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private DomainTypes.ProjectStatus status = DomainTypes.ProjectStatus.ACTIVE;
  @Column(nullable = false) private Instant createdAt = Instant.now();
  protected Project() {}
  public Project(UUID organizationId, String slug, String name, String description) { this.organizationId = organizationId; this.slug = slug; this.name = name; this.description = description; }
  public UUID getId() { return id; }
  public UUID getOrganizationId() { return organizationId; }
  public String getSlug() { return slug; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public DomainTypes.ProjectStatus getStatus() { return status; }
}

