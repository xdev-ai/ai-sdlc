package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.EvidenceAsset;
import ai.xdev.aisdlc.domain.Project;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.evidence.EvidenceStorageProperties;
import ai.xdev.aisdlc.evidence.ObjectStoragePort;
import ai.xdev.aisdlc.repo.Repositories.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceRepositoryServiceTest {
  @Test
  void rejectsMismatchedDigestBeforeObjectStorageWrite() {
    Fixture fixture = fixture();
    assertThrows(IllegalArgumentException.class, () -> fixture.service.upload(fixture.projectId, "developer-1", "upload-valid-key", EvidenceAssetType.VALIDATION, EvidenceAccessLevel.PROJECT, null, "report.txt", "text/plain", "proof".getBytes(), "a".repeat(64)));
    verifyNoInteractions(fixture.storage, fixture.assets, fixture.audit);
  }

  @Test
  void writesObjectMetadataAndAuditForFreshIdempotencyKey() throws Exception {
    Fixture fixture = fixture();
    when(fixture.assets.findByProjectIdAndIdempotencyKey(fixture.projectId, "upload-valid-key")).thenReturn(Optional.empty());
    when(fixture.storage.store(any())).thenReturn(new ObjectStoragePort.StoredObject("aisdlc-evidence", "projects/key/report.txt", 5));
    when(fixture.assets.save(any(EvidenceAsset.class))).thenAnswer(invocation -> { EvidenceAsset asset = invocation.getArgument(0); setId(asset, UUID.randomUUID()); return asset; });

    EvidenceAsset saved = fixture.service.upload(fixture.projectId, "developer-1", "upload-valid-key", EvidenceAssetType.VALIDATION, EvidenceAccessLevel.PROJECT, null, "report.txt", "text/plain", "proof".getBytes(), null);

    assertEquals(5, saved.getSizeBytes());
    assertEquals("upload-valid-key", saved.getIdempotencyKey());
    assertEquals("c1cda26362828b69266512052b97cb3729e3b052e4ade47c0a1e3383defe73c7", saved.getSha256Digest());
    verify(fixture.storage).store(argThat(upload -> upload.sha256Digest().equals(saved.getSha256Digest()) && upload.metadata().get("project-id").equals(fixture.projectId.toString())));
    verify(fixture.audit).append(eq(fixture.project.getOrganizationId()), eq(fixture.projectId), eq("developer-1"), eq("evidence.asset.uploaded"), eq("evidence_asset"), eq(saved.getId().toString()), contains(saved.getSha256Digest()));
  }

  @Test
  void returnsExistingAssetForEquivalentIdempotentRetryWithoutSecondWrite() throws Exception {
    Fixture fixture = fixture();
    EvidenceAsset existing = new EvidenceAsset(fixture.projectId, null, EvidenceAssetType.VALIDATION, "report.txt", "text/plain", 5, "aisdlc-evidence", "projects/key/report.txt", "c1cda26362828b69266512052b97cb3729e3b052e4ade47c0a1e3383defe73c7", "upload-valid-key", "developer-1", EvidenceAccessLevel.PROJECT);
    setId(existing, UUID.randomUUID());
    when(fixture.assets.findByProjectIdAndIdempotencyKey(fixture.projectId, "upload-valid-key")).thenReturn(Optional.of(existing));

    EvidenceAsset response = fixture.service.upload(fixture.projectId, "developer-1", "upload-valid-key", EvidenceAssetType.VALIDATION, EvidenceAccessLevel.PROJECT, null, "report.txt", "text/plain", "proof".getBytes(), null);

    assertSame(existing, response);
    verifyNoInteractions(fixture.storage, fixture.audit);
    verify(fixture.assets, never()).save(any());
  }

  @Test
  void reviewerCanApplyComplianceRetentionAndAuditIt() throws Exception {
    Fixture fixture = fixture();
    UUID assetId = UUID.randomUUID();
    EvidenceAsset asset = new EvidenceAsset(fixture.projectId, null, EvidenceAssetType.REVIEW, "decision.txt", "text/plain", 7, "aisdlc-evidence", "projects/key/decision.txt", "b".repeat(64), "retention-test-key", "reviewer-1", EvidenceAccessLevel.PROJECT);
    setId(asset, assetId);
    when(fixture.assets.findByIdAndProjectIdAndDeletedAtIsNull(assetId, fixture.projectId)).thenReturn(Optional.of(asset));
    Instant expiry = Instant.now().plusSeconds(3600);

    EvidenceAsset locked = fixture.service.applyRetention(fixture.projectId, assetId, "reviewer-1", ObjectLockMode.COMPLIANCE, expiry);

    assertEquals(ObjectLockMode.COMPLIANCE, locked.getObjectLockMode());
    assertEquals(expiry, locked.getRetentionUntil());
    verify(fixture.access).requireMembership(eq(fixture.projectId), eq("reviewer-1"), eq(MembershipRole.OWNER), eq(MembershipRole.REVIEWER));
    verify(fixture.storage).applyRetentionLock("aisdlc-evidence", "projects/key/decision.txt", ObjectLockMode.COMPLIANCE, expiry);
    verify(fixture.audit).append(eq(fixture.project.getOrganizationId()), eq(fixture.projectId), eq("reviewer-1"), eq("evidence.asset.retention.locked"), eq("evidence_asset"), eq(assetId.toString()), anyString());
  }

  private Fixture fixture() {
    ProjectAccessService access = mock(ProjectAccessService.class); EvidenceAssetRepository assets = mock(EvidenceAssetRepository.class); ObjectStoragePort storage = mock(ObjectStoragePort.class); AuditService audit = mock(AuditService.class);
    UUID projectId = UUID.randomUUID(); Project project = new Project(UUID.randomUUID(), "demo", "Demo", "");
    when(access.requireMembership(eq(projectId), anyString(), any(MembershipRole[].class))).thenReturn(project);
    return new Fixture(projectId, project, access, assets, storage, audit, new EvidenceRepositoryService(access, assets, mock(ValidationEvidenceRepository.class), mock(ValidationRunRepository.class), storage, new EvidenceStorageProperties(), audit));
  }
  private record Fixture(UUID projectId, Project project, ProjectAccessService access, EvidenceAssetRepository assets, ObjectStoragePort storage, AuditService audit, EvidenceRepositoryService service) {}
  private static void setId(Object target, UUID value) throws Exception { Field field = target.getClass().getDeclaredField("id"); field.setAccessible(true); field.set(target, value); }
}
