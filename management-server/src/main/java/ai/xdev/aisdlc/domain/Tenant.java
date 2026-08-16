package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(nullable = false, unique = true, length = 80) private String slug;
  @Column(name = "display_name", nullable = false, length = 160) private String displayName;
  @Enumerated(EnumType.STRING) @Column(name = "tenant_status", nullable = false) private DomainTypes.TenantStatus tenantStatus = DomainTypes.TenantStatus.ACTIVE;
  @Column(name = "data_residency", nullable = false, length = 80) private String dataResidency;
  @Column(name = "encryption_key_reference", length = 300) private String encryptionKeyReference;
  @Column(name = "legal_hold_enabled", nullable = false) private boolean legalHoldEnabled;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  protected Tenant() {}
  public Tenant(String slug, String displayName, String dataResidency, String encryptionKeyReference) { this.slug = slug; this.displayName = displayName; this.dataResidency = dataResidency; this.encryptionKeyReference = encryptionKeyReference; }
  public UUID getId() { return id; } public String getSlug() { return slug; } public String getDisplayName() { return displayName; }
  public DomainTypes.TenantStatus getTenantStatus() { return tenantStatus; } public String getDataResidency() { return dataResidency; }
  public String getEncryptionKeyReference() { return encryptionKeyReference; } public boolean isLegalHoldEnabled() { return legalHoldEnabled; }
  public void setLegalHoldEnabled(boolean enabled) { this.legalHoldEnabled = enabled; this.updatedAt = Instant.now(); }
}
