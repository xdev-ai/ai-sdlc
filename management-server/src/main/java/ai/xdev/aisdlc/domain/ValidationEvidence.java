package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "validation_evidences")
public class ValidationEvidence {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "validation_run_id", nullable = false) private UUID validationRunId;
  @Column(name = "evidence_type", nullable = false, length = 80) private String evidenceType;
  @Column(name = "digest_sha256", nullable = false, length = 64) private String digestSha256;
  @Column(length = 1000) private String uri;
  @Column(columnDefinition = "jsonb") private String metadata = "{}";
  protected ValidationEvidence() {}
  public ValidationEvidence(UUID runId, String type, String digestSha256, String uri) { this.validationRunId = runId; this.evidenceType = type; this.digestSha256 = digestSha256; this.uri = uri; }
}

