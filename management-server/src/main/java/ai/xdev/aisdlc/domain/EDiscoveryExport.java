package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "e_discovery_exports")
public class EDiscoveryExport {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "requested_by", nullable = false, length = 200) private String requestedBy;
  @Column(name = "scope_json", nullable = false, columnDefinition = "jsonb") private String scopeJson;
  @Enumerated(EnumType.STRING) @Column(name = "export_status", nullable = false) private DomainTypes.EDiscoveryExportStatus exportStatus = DomainTypes.EDiscoveryExportStatus.REQUESTED;
  @Column(name = "object_bucket", length = 160) private String objectBucket;
  @Column(name = "object_key", length = 500) private String objectKey;
  @Column(name = "manifest_sha256", length = 64) private String manifestSha256;
  @Column(name = "size_bytes") private Long sizeBytes;
  @Column(name = "retention_until") private Instant retentionUntil;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "ready_at") private Instant readyAt;
  protected EDiscoveryExport() {}
  public EDiscoveryExport(UUID tenantId, String requestedBy, String scopeJson) { this.tenantId = tenantId; this.requestedBy = requestedBy; this.scopeJson = scopeJson; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public String getRequestedBy() { return requestedBy; } public String getScopeJson() { return scopeJson; } public DomainTypes.EDiscoveryExportStatus getExportStatus() { return exportStatus; } public String getObjectBucket() { return objectBucket; } public String getObjectKey() { return objectKey; } public String getManifestSha256() { return manifestSha256; } public Long getSizeBytes() { return sizeBytes; } public Instant getCreatedAt() { return createdAt; } public Instant getReadyAt() { return readyAt; }
  public void markReady(String bucket, String key, String sha256, long size, Instant retentionUntil) { exportStatus = DomainTypes.EDiscoveryExportStatus.READY; objectBucket = bucket; objectKey = key; manifestSha256 = sha256; sizeBytes = size; this.retentionUntil = retentionUntil; readyAt = Instant.now(); }
  public void markFailed() { exportStatus = DomainTypes.EDiscoveryExportStatus.FAILED; }
}
