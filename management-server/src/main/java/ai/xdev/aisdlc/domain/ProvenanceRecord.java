package ai.xdev.aisdlc.domain;

import ai.xdev.aisdlc.domain.DomainTypes.ProvenanceSignatureMethod;
import ai.xdev.aisdlc.domain.DomainTypes.ProvenanceVerificationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provenance_records")
public class ProvenanceRecord {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "sbom_asset_id") private UUID sbomAssetId;
  @Column(name = "attestation_evidence_asset_id") private UUID attestationEvidenceAssetId;
  @Column(name = "artifact_name", nullable = false, length = 300) private String artifactName;
  @Column(name = "artifact_digest", nullable = false, length = 71) private String artifactDigest;
  @Column(name = "source_repository", nullable = false, length = 500) private String sourceRepository;
  @Column(name = "source_revision", nullable = false, length = 128) private String sourceRevision;
  @Column(name = "build_system", nullable = false, length = 120) private String buildSystem;
  @Column(name = "build_url", length = 2000) private String buildUrl;
  @Column(name = "signer_identity", nullable = false, length = 500) private String signerIdentity;
  @Enumerated(EnumType.STRING) @Column(name = "signature_method", nullable = false, length = 40) private ProvenanceSignatureMethod signatureMethod;
  @Column(name = "attestation_reference", length = 2000) private String attestationReference;
  @Enumerated(EnumType.STRING) @Column(name = "verification_status", nullable = false, length = 20) private ProvenanceVerificationStatus verificationStatus = ProvenanceVerificationStatus.DECLARED;
  @Column(name = "verified_by", length = 200) private String verifiedBy;
  @Column(name = "verified_at") private Instant verifiedAt;
  @Column(name = "verification_note", columnDefinition = "text") private String verificationNote;
  @Column(name = "created_by", nullable = false, length = 200) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  protected ProvenanceRecord() {}
  public ProvenanceRecord(UUID projectId, UUID sbomAssetId, UUID attestationEvidenceAssetId, String artifactName, String artifactDigest, String sourceRepository, String sourceRevision, String buildSystem, String buildUrl, String signerIdentity, ProvenanceSignatureMethod signatureMethod, String attestationReference, String createdBy) { this.projectId = projectId; this.sbomAssetId = sbomAssetId; this.attestationEvidenceAssetId = attestationEvidenceAssetId; this.artifactName = artifactName; this.artifactDigest = artifactDigest; this.sourceRepository = sourceRepository; this.sourceRevision = sourceRevision; this.buildSystem = buildSystem; this.buildUrl = buildUrl; this.signerIdentity = signerIdentity; this.signatureMethod = signatureMethod; this.attestationReference = attestationReference; this.createdBy = createdBy; }
  public void verify(ProvenanceVerificationStatus status, String actor, String note) { verificationStatus = status; verifiedBy = actor; verifiedAt = Instant.now(); verificationNote = note; }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public UUID getSbomAssetId() { return sbomAssetId; } public UUID getAttestationEvidenceAssetId() { return attestationEvidenceAssetId; } public String getArtifactName() { return artifactName; } public String getArtifactDigest() { return artifactDigest; } public String getSourceRepository() { return sourceRepository; } public String getSourceRevision() { return sourceRevision; } public String getBuildSystem() { return buildSystem; } public String getBuildUrl() { return buildUrl; } public String getSignerIdentity() { return signerIdentity; } public ProvenanceSignatureMethod getSignatureMethod() { return signatureMethod; } public String getAttestationReference() { return attestationReference; } public ProvenanceVerificationStatus getVerificationStatus() { return verificationStatus; } public String getVerifiedBy() { return verifiedBy; } public Instant getVerifiedAt() { return verifiedAt; } public String getVerificationNote() { return verificationNote; } public String getCreatedBy() { return createdBy; } public Instant getCreatedAt() { return createdAt; }
}
