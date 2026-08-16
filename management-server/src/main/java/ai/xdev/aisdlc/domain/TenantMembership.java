package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_memberships", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "subject"}))
public class TenantMembership {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false, length = 200) private String subject;
  @Enumerated(EnumType.STRING) @Column(name = "tenant_role", nullable = false) private DomainTypes.TenantRole tenantRole;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  protected TenantMembership() {}
  public TenantMembership(UUID tenantId, String subject, DomainTypes.TenantRole tenantRole) { this.tenantId = tenantId; this.subject = subject; this.tenantRole = tenantRole; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public String getSubject() { return subject; } public DomainTypes.TenantRole getTenantRole() { return tenantRole; }
}
