package ai.xdev.aisdlc.domain;

import ai.xdev.aisdlc.domain.DomainTypes.SbomFormat;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sbom_assets")
public class SbomAsset {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "evidence_asset_id", nullable = false, unique = true) private UUID evidenceAssetId;
  @Enumerated(EnumType.STRING) @Column(name = "sbom_format", nullable = false, length = 20) private SbomFormat sbomFormat;
  @Column(name = "spec_version", length = 40) private String specVersion;
  @Column(name = "serial_number", length = 500) private String serialNumber;
  @Column(name = "component_count", nullable = false) private int componentCount;
  @Column(name = "release_reference", length = 200) private String releaseReference;
  @Column(name = "document_sha256", nullable = false, length = 64) private String documentSha256;
  @Column(name = "ingested_by", nullable = false, length = 200) private String ingestedBy;
  @Column(name = "ingested_at", nullable = false) private Instant ingestedAt = Instant.now();
  protected SbomAsset() {}
  public SbomAsset(UUID projectId, UUID evidenceAssetId, SbomFormat sbomFormat, String specVersion, String serialNumber, int componentCount, String releaseReference, String documentSha256, String ingestedBy) { this.projectId = projectId; this.evidenceAssetId = evidenceAssetId; this.sbomFormat = sbomFormat; this.specVersion = specVersion; this.serialNumber = serialNumber; this.componentCount = componentCount; this.releaseReference = releaseReference; this.documentSha256 = documentSha256; this.ingestedBy = ingestedBy; }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public UUID getEvidenceAssetId() { return evidenceAssetId; } public SbomFormat getSbomFormat() { return sbomFormat; } public String getSpecVersion() { return specVersion; } public String getSerialNumber() { return serialNumber; } public int getComponentCount() { return componentCount; } public String getReleaseReference() { return releaseReference; } public String getDocumentSha256() { return documentSha256; } public String getIngestedBy() { return ingestedBy; } public Instant getIngestedAt() { return ingestedAt; }
}
