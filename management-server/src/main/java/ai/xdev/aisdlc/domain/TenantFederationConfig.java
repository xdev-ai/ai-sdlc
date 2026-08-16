package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_federation_configs")
public class TenantFederationConfig {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private DomainTypes.FederationProtocol protocol;
  @Column(name = "issuer_uri", nullable = false, length = 500) private String issuerUri;
  @Column(name = "client_id", length = 300) private String clientId;
  @Column(name = "client_secret_ciphertext") private String clientSecretCiphertext;
  @Column(name = "metadata_uri", length = 500) private String metadataUri;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "claim_mapping_json", nullable = false, columnDefinition = "jsonb") private String claimMappingJson;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "created_by", nullable = false, length = 200) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  protected TenantFederationConfig() {}
  public TenantFederationConfig(UUID tenantId, DomainTypes.FederationProtocol protocol, String issuerUri, String clientId, String clientSecretCiphertext, String metadataUri, String claimMappingJson, boolean enabled, String createdBy) { this.tenantId = tenantId; this.protocol = protocol; this.issuerUri = issuerUri; this.clientId = clientId; this.clientSecretCiphertext = clientSecretCiphertext; this.metadataUri = metadataUri; this.claimMappingJson = claimMappingJson; this.enabled = enabled; this.createdBy = createdBy; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public DomainTypes.FederationProtocol getProtocol() { return protocol; } public String getIssuerUri() { return issuerUri; } public String getClientId() { return clientId; } public String getMetadataUri() { return metadataUri; } public String getClaimMappingJson() { return claimMappingJson; } public boolean isEnabled() { return enabled; }
}
