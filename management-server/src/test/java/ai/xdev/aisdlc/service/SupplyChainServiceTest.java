package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplyChainServiceTest {
  @Test
  void rejectsUnrecognizedSbomBeforeWritingEvidence() {
    Fixture fixture = fixture();
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> fixture.service.ingestSbom(fixture.projectId, "developer-1", "sbom-reject-key", "bom.json", "application/json", "{\"unexpected\":true}".getBytes(), null, EvidenceAccessLevel.PROJECT, "v1.0.0"));
    assertTrue(error.getMessage().contains("Unsupported SBOM"));
    verifyNoInteractions(fixture.evidence, fixture.sboms, fixture.audit);
  }

  @Test
  void ingestsCycloneDxSbomWithEvidenceDigestAndAudit() throws Exception {
    Fixture fixture = fixture();
    EvidenceAsset evidence = new EvidenceAsset(fixture.projectId, null, EvidenceAssetType.SBOM, "bom.json", "application/json", 88, "evidence", "bom", "a".repeat(64), "sbom-ingest-key", "developer-1", EvidenceAccessLevel.PROJECT);
    setId(evidence, UUID.randomUUID());
    when(fixture.evidence.upload(eq(fixture.projectId), eq("developer-1"), eq("sbom-ingest-key"), eq(EvidenceAssetType.SBOM), eq(EvidenceAccessLevel.PROJECT), isNull(), eq("bom.json"), eq("application/json"), any(), isNull())).thenReturn(evidence);
    when(fixture.sboms.findByProjectIdAndDocumentSha256(fixture.projectId, evidence.getSha256Digest())).thenReturn(Optional.empty());
    when(fixture.sboms.save(any(SbomAsset.class))).thenAnswer(call -> { SbomAsset value = call.getArgument(0); setId(value, UUID.randomUUID()); return value; });

    SbomAsset asset = fixture.service.ingestSbom(fixture.projectId, "developer-1", "sbom-ingest-key", "bom.json", "application/json", "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\"urn:uuid:123\",\"components\":[{},{}]}".getBytes(), null, EvidenceAccessLevel.PROJECT, "v1.0.0");

    assertEquals(SbomFormat.CYCLONEDX_JSON, asset.getSbomFormat());
    assertEquals(2, asset.getComponentCount());
    assertEquals(evidence.getId(), asset.getEvidenceAssetId());
    verify(fixture.audit).append(eq(fixture.project.getOrganizationId()), eq(fixture.projectId), eq("developer-1"), eq("supply_chain.sbom.ingested"), eq("sbom_asset"), eq(asset.getId().toString()), contains(evidence.getSha256Digest()));
  }

  @Test
  void verificationRequiresReviewerDecisionAndCreatesAuditEvidence() throws Exception {
    Fixture fixture = fixture();
    ProvenanceRecord record = new ProvenanceRecord(fixture.projectId, null, null, "aisdlc.jar", "sha256:" + "b".repeat(64), "https://github.com/xdev-ai/ai-sdlc", "deadbeef", "GitHub Actions", "https://github.com/xdev-ai/ai-sdlc/actions/runs/1", "https://github.com/xdev-ai", ProvenanceSignatureMethod.GITHUB_ATTESTATION, "https://github.com/xdev-ai/ai-sdlc/attestations/1", "developer-1");
    UUID recordId = UUID.randomUUID(); setId(record, recordId);
    when(fixture.provenance.findByIdAndProjectId(recordId, fixture.projectId)).thenReturn(Optional.of(record));

    SupplyChainService.ProvenanceView verified = fixture.service.verifyProvenance(fixture.projectId, recordId, "reviewer-1", ProvenanceVerificationStatus.VERIFIED, "Verified with gh attestation verify against the release artifact.");

    assertEquals(ProvenanceVerificationStatus.VERIFIED, verified.verificationStatus());
    assertEquals("reviewer-1", verified.verifiedBy());
    verify(fixture.access).requireMembership(eq(fixture.projectId), eq("reviewer-1"), eq(MembershipRole.OWNER), eq(MembershipRole.REVIEWER));
    verify(fixture.audit).append(eq(fixture.project.getOrganizationId()), eq(fixture.projectId), eq("reviewer-1"), eq("supply_chain.provenance.verified"), eq("provenance_record"), eq(recordId.toString()), contains(record.getArtifactDigest()));
  }

  private Fixture fixture() {
    UUID projectId = UUID.randomUUID(); Project project = new Project(UUID.randomUUID(), "demo", "Demo", "");
    ProjectAccessService access = mock(ProjectAccessService.class); EvidenceRepositoryService evidence = mock(EvidenceRepositoryService.class); EvidenceAssetRepository evidenceAssets = mock(EvidenceAssetRepository.class); SbomAssetRepository sboms = mock(SbomAssetRepository.class); ProvenanceRecordRepository provenance = mock(ProvenanceRecordRepository.class); AuditService audit = mock(AuditService.class);
    when(access.requireMembership(eq(projectId), anyString(), any(MembershipRole[].class))).thenReturn(project);
    return new Fixture(projectId, project, access, evidence, sboms, provenance, audit, new SupplyChainService(access, evidence, evidenceAssets, sboms, provenance, audit, new ObjectMapper()));
  }
  private record Fixture(UUID projectId, Project project, ProjectAccessService access, EvidenceRepositoryService evidence, SbomAssetRepository sboms, ProvenanceRecordRepository provenance, AuditService audit, SupplyChainService service) {}
  private static void setId(Object target, UUID value) throws Exception { Field field = target.getClass().getDeclaredField("id"); field.setAccessible(true); field.set(target, value); }
}
