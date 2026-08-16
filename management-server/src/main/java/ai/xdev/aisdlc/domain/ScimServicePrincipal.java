package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scim_service_principals")
public class ScimServicePrincipal {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "display_name", nullable = false, length = 160) private String displayName;
  @Column(name = "token_sha256", nullable = false, unique = true, columnDefinition = "char(64)") private String tokenSha256;
  @Column(nullable = false) private boolean active = true;
  @Column(name = "created_by", nullable = false, length = 200) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "revoked_at") private Instant revokedAt;
  protected ScimServicePrincipal() {}
  public ScimServicePrincipal(UUID tenantId, String displayName, String tokenSha256, String createdBy) { this.tenantId = tenantId; this.displayName = displayName; this.tokenSha256 = tokenSha256; this.createdBy = createdBy; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public String getDisplayName() { return displayName; } public String getTokenSha256() { return tokenSha256; } public boolean isActive() { return active; }
  public void revoke() { active = false; revokedAt = Instant.now(); }
}
