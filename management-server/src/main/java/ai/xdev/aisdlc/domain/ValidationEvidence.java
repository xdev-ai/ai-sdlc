package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "validation_evidences")
public class ValidationEvidence {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "validation_run_id", nullable = false) private UUID validationRunId;
  @Column(name = "evidence_type", nullable = false, length = 80) private String evidenceType;
  @Column(name = "digest_sha256", nullable = false, length = 64) private String digestSha256;
  @Column(length = 1000) private String uri;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String metadata = "{}";
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "retention_until") private Instant retentionUntil;
  protected ValidationEvidence() {}
  public ValidationEvidence(UUID runId, String type, String digestSha256, String uri) { this.validationRunId = runId; this.evidenceType = type; this.digestSha256 = digestSha256; this.uri = uri; }
  public UUID getId() { return id; }
  public UUID getValidationRunId() { return validationRunId; }
  public String getEvidenceType() { return evidenceType; }
  public String getDigestSha256() { return digestSha256; }
  public String getUri() { return uri; }
  public String getMetadata() { return metadata; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getRetentionUntil() { return retentionUntil; }
  public void setRetentionUntil(Instant retentionUntil) { this.retentionUntil = retentionUntil; }
}
