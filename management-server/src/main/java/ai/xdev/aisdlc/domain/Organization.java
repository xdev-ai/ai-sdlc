package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false, unique = true, length = 80) private String slug;
  @Column(nullable = false, length = 160) private String name;
  @Column(nullable = false) private Instant createdAt = Instant.now();
  protected Organization() {}
  public Organization(UUID tenantId, String slug, String name) { this.tenantId = tenantId; this.slug = slug; this.name = name; }
  /** Test-only compatibility constructor; production creation resolves a concrete tenant. */
  public Organization(String slug, String name) { this(UUID.fromString("00000000-0000-0000-0000-000000000001"), slug, name); }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getSlug() { return slug; }
  public String getName() { return name; }
}
