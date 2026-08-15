package ai.xdev.aisdlc.domain;

import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAccessLevel;
import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAssetType;
import ai.xdev.aisdlc.domain.DomainTypes.ObjectLockMode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evidence_assets")
public class EvidenceAsset {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "validation_evidence_id") private UUID validationEvidenceId;
  @Enumerated(EnumType.STRING) @Column(name = "asset_type", nullable = false, length = 32) private EvidenceAssetType assetType;
  @Column(nullable = false, length = 255) private String filename;
  @Column(name = "content_type", nullable = false, length = 255) private String contentType;
  @Column(name = "size_bytes", nullable = false) private long sizeBytes;
  @Column(name = "s3_bucket", nullable = false, length = 255) private String s3Bucket;
  @Column(name = "s3_key", nullable = false, length = 1024) private String s3Key;
  @Column(name = "sha256_digest", nullable = false, length = 64) private String sha256Digest;
  @Column(name = "idempotency_key", nullable = false, length = 120) private String idempotencyKey;
  @Enumerated(EnumType.STRING) @Column(name = "object_lock_mode", length = 20) private ObjectLockMode objectLockMode;
  @Column(name = "retention_until") private Instant retentionUntil;
  @Column(name = "uploaded_by", nullable = false, length = 120) private String uploadedBy;
  @Column(name = "uploaded_at", nullable = false) private Instant uploadedAt = Instant.now();
  @Enumerated(EnumType.STRING) @Column(name = "access_level", nullable = false, length = 20) private EvidenceAccessLevel accessLevel = EvidenceAccessLevel.PROJECT;
  @Column(name = "deleted_at") private Instant deletedAt;

  protected EvidenceAsset() {}

  public EvidenceAsset(UUID projectId, UUID validationEvidenceId, EvidenceAssetType assetType, String filename, String contentType, long sizeBytes, String s3Bucket, String s3Key, String sha256Digest, String idempotencyKey, String uploadedBy, EvidenceAccessLevel accessLevel) {
    this.projectId = projectId; this.validationEvidenceId = validationEvidenceId; this.assetType = assetType; this.filename = filename; this.contentType = contentType; this.sizeBytes = sizeBytes; this.s3Bucket = s3Bucket; this.s3Key = s3Key; this.sha256Digest = sha256Digest; this.idempotencyKey = idempotencyKey; this.uploadedBy = uploadedBy; this.accessLevel = accessLevel;
  }

  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public UUID getValidationEvidenceId() { return validationEvidenceId; }
  public EvidenceAssetType getAssetType() { return assetType; }
  public String getFilename() { return filename; }
  public String getContentType() { return contentType; }
  public long getSizeBytes() { return sizeBytes; }
  public String getS3Bucket() { return s3Bucket; }
  public String getS3Key() { return s3Key; }
  public String getSha256Digest() { return sha256Digest; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public ObjectLockMode getObjectLockMode() { return objectLockMode; }
  public Instant getRetentionUntil() { return retentionUntil; }
  public String getUploadedBy() { return uploadedBy; }
  public Instant getUploadedAt() { return uploadedAt; }
  public EvidenceAccessLevel getAccessLevel() { return accessLevel; }
  public Instant getDeletedAt() { return deletedAt; }
  public boolean isDeleted() { return deletedAt != null; }

  public void applyRetention(ObjectLockMode mode, Instant until) { this.objectLockMode = mode; this.retentionUntil = until; }
  public void softDelete(Instant at) { this.deletedAt = at; }
}
