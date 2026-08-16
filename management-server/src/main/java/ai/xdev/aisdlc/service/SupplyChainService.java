package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.web.PageRequests;
import ai.xdev.aisdlc.web.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplyChainService {
  public record SbomView(UUID id, UUID evidenceAssetId, SbomFormat format, String specVersion, String serialNumber, int componentCount, String releaseReference, String documentSha256, String ingestedBy, java.time.Instant ingestedAt) {}
  public record ProvenanceView(UUID id, UUID sbomAssetId, UUID attestationEvidenceAssetId, String artifactName, String artifactDigest, String sourceRepository, String sourceRevision, String buildSystem, String buildUrl, String signerIdentity, ProvenanceSignatureMethod signatureMethod, String attestationReference, ProvenanceVerificationStatus verificationStatus, String verifiedBy, java.time.Instant verifiedAt, String verificationNote, java.time.Instant createdAt) {}
  private final ProjectAccessService access;
  private final EvidenceRepositoryService evidence;
  private final EvidenceAssetRepository evidenceAssets;
  private final SbomAssetRepository sboms;
  private final ProvenanceRecordRepository provenance;
  private final AuditService audit;
  private final ObjectMapper mapper;

  public SupplyChainService(ProjectAccessService access, EvidenceRepositoryService evidence, EvidenceAssetRepository evidenceAssets, SbomAssetRepository sboms, ProvenanceRecordRepository provenance, AuditService audit, ObjectMapper mapper) { this.access = access; this.evidence = evidence; this.evidenceAssets = evidenceAssets; this.sboms = sboms; this.provenance = provenance; this.audit = audit; this.mapper = mapper; }

  @Transactional
  public SbomAsset ingestSbom(UUID projectId, String subject, String idempotencyKey, String filename, String contentType, byte[] document, String expectedSha256, EvidenceAccessLevel accessLevel, String releaseReference) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    ParsedSbom parsed = parse(document);
    EvidenceAsset stored = evidence.upload(projectId, subject, idempotencyKey, EvidenceAssetType.SBOM, accessLevel == null ? EvidenceAccessLevel.PROJECT : accessLevel, null, filename, contentType == null ? "application/json" : contentType, document, expectedSha256);
    Optional<SbomAsset> existing = sboms.findByProjectIdAndDocumentSha256(projectId, stored.getSha256Digest());
    if (existing.isPresent()) return existing.get();
    SbomAsset asset = sboms.save(new SbomAsset(projectId, stored.getId(), parsed.format(), parsed.specVersion(), parsed.serialNumber(), parsed.componentCount(), normalized(releaseReference, 200), stored.getSha256Digest(), subject));
    audit.append(project.getOrganizationId(), projectId, subject, "supply_chain.sbom.ingested", "sbom_asset", asset.getId().toString(), "{\"sha256\":\"" + asset.getDocumentSha256() + "\",\"format\":\"" + asset.getSbomFormat() + "\",\"components\":" + asset.getComponentCount() + "}");
    return asset;
  }

  @Transactional
  public ProvenanceRecord recordProvenance(UUID projectId, String subject, UUID sbomAssetId, UUID attestationEvidenceAssetId, String artifactName, String artifactDigest, String sourceRepository, String sourceRevision, String buildSystem, String buildUrl, String signerIdentity, ProvenanceSignatureMethod signatureMethod, String attestationReference) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    if (sbomAssetId != null) requireSbom(projectId, sbomAssetId);
    if (attestationEvidenceAssetId != null) requireEvidence(projectId, attestationEvidenceAssetId);
    require(artifactName, "Artifact name", 300);
    String digest = normalized(artifactDigest, 71);
    if (digest == null || !digest.matches("sha256:[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Artifact digest must be sha256 followed by 64 hexadecimal characters");
    require(sourceRepository, "Source repository", 500);
    String revision = normalized(sourceRevision, 128);
    if (revision == null || !revision.matches("[A-Za-z0-9._/-]{7,128}")) throw new IllegalArgumentException("Source revision must be a stable source identifier");
    require(buildSystem, "Build system", 120); require(signerIdentity, "Signer identity", 500);
    if (signatureMethod == null) throw new IllegalArgumentException("Signature method is required");
    requireHttps(buildUrl, "Build URL"); requireHttps(attestationReference, "Attestation reference");
    ProvenanceRecord record = provenance.save(new ProvenanceRecord(projectId, sbomAssetId, attestationEvidenceAssetId, artifactName.trim(), digest.toLowerCase(Locale.ROOT), sourceRepository.trim(), revision, buildSystem.trim(), normalized(buildUrl, 2000), signerIdentity.trim(), signatureMethod, normalized(attestationReference, 2000), subject));
    audit.append(project.getOrganizationId(), projectId, subject, "supply_chain.provenance.declared", "provenance_record", record.getId().toString(), "{\"artifactDigest\":\"" + record.getArtifactDigest() + "\",\"signatureMethod\":\"" + signatureMethod + "\"}");
    return record;
  }

  @Transactional
  public ProvenanceView verifyProvenance(UUID projectId, UUID recordId, String subject, ProvenanceVerificationStatus status, String note) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.REVIEWER);
    if (status != ProvenanceVerificationStatus.VERIFIED && status != ProvenanceVerificationStatus.REJECTED) throw new IllegalArgumentException("Verification status must be VERIFIED or REJECTED");
    if (note == null || note.isBlank() || note.length() > 4000) throw new IllegalArgumentException("A bounded verification note is required");
    ProvenanceRecord record = provenance.findByIdAndProjectId(recordId, projectId).orElseThrow(() -> new IllegalArgumentException("Provenance record not found"));
    record.verify(status, subject, note.trim());
    audit.append(project.getOrganizationId(), projectId, subject, "supply_chain.provenance." + status.name().toLowerCase(Locale.ROOT), "provenance_record", record.getId().toString(), "{\"artifactDigest\":\"" + record.getArtifactDigest() + "\"}");
    return view(record);
  }

  public PageResponse<SbomView> listSboms(UUID projectId, String subject, int page, int size) { access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); return PageResponse.from(sboms.findByProjectIdOrderByIngestedAtDesc(projectId, PageRequests.of(page, size, "ingestedAt,desc", "ingestedAt", "releaseReference")).map(this::view)); }
  public PageResponse<ProvenanceView> listProvenance(UUID projectId, String subject, int page, int size) { access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); return PageResponse.from(provenance.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequests.of(page, size, "createdAt,desc", "createdAt", "artifactName", "verificationStatus")).map(this::view)); }

  private ParsedSbom parse(byte[] body) {
    try {
      JsonNode root = mapper.readTree(body);
      if (root == null || !root.isObject()) throw new IllegalArgumentException("SBOM must be a JSON object");
      if ("CycloneDX".equals(root.path("bomFormat").asText())) {
        String version = requiredJson(root, "specVersion");
        return new ParsedSbom(SbomFormat.CYCLONEDX_JSON, version, optionalJson(root, "serialNumber"), arraySize(root, "components"));
      }
      if (root.hasNonNull("spdxVersion")) return new ParsedSbom(SbomFormat.SPDX_JSON, requiredJson(root, "spdxVersion"), optionalJson(root, "documentNamespace"), arraySize(root, "packages"));
      throw new IllegalArgumentException("Unsupported SBOM: expected CycloneDX JSON or SPDX JSON");
    } catch (java.io.IOException ex) { throw new IllegalArgumentException("SBOM is not valid JSON", ex); }
  }
  private String requiredJson(JsonNode root, String name) { String value = optionalJson(root, name); if (value == null) throw new IllegalArgumentException("SBOM field " + name + " is required"); return value; }
  private String optionalJson(JsonNode root, String name) { String value = root.path(name).asText(null); return value == null || value.isBlank() ? null : normalized(value, 500); }
  private int arraySize(JsonNode root, String name) { JsonNode node = root.path(name); return node.isArray() ? node.size() : 0; }
  private SbomAsset requireSbom(UUID projectId, UUID id) { return sboms.findByIdAndProjectId(id, projectId).orElseThrow(() -> new IllegalArgumentException("SBOM asset not found")); }
  private EvidenceAsset requireEvidence(UUID projectId, UUID id) { return evidenceAssets.findByIdAndProjectIdAndDeletedAtIsNull(id, projectId).orElseThrow(() -> new IllegalArgumentException("Attestation evidence asset not found")); }
  private void require(String value, String name, int maximum) { if (normalized(value, maximum) == null) throw new IllegalArgumentException(name + " is required"); }
  private String normalized(String value, int maximum) { if (value == null || value.isBlank()) return null; String trimmed = value.trim(); if (trimmed.length() > maximum) throw new IllegalArgumentException("Value exceeds " + maximum + " characters"); return trimmed; }
  private void requireHttps(String value, String name) { if (value == null || value.isBlank()) return; try { URI uri = URI.create(value); if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException(); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException(name + " must be an HTTPS URL"); } }
  private SbomView view(SbomAsset value) { return new SbomView(value.getId(), value.getEvidenceAssetId(), value.getSbomFormat(), value.getSpecVersion(), value.getSerialNumber(), value.getComponentCount(), value.getReleaseReference(), value.getDocumentSha256(), value.getIngestedBy(), value.getIngestedAt()); }
  private ProvenanceView view(ProvenanceRecord value) { return new ProvenanceView(value.getId(), value.getSbomAssetId(), value.getAttestationEvidenceAssetId(), value.getArtifactName(), value.getArtifactDigest(), value.getSourceRepository(), value.getSourceRevision(), value.getBuildSystem(), value.getBuildUrl(), value.getSignerIdentity(), value.getSignatureMethod(), value.getAttestationReference(), value.getVerificationStatus(), value.getVerifiedBy(), value.getVerifiedAt(), value.getVerificationNote(), value.getCreatedAt()); }
  private record ParsedSbom(SbomFormat format, String specVersion, String serialNumber, int componentCount) {}
}
