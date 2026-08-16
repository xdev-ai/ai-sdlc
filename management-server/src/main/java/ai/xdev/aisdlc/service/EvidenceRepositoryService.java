package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.evidence.EvidenceStorageProperties;
import ai.xdev.aisdlc.evidence.ObjectStoragePort;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.web.PageRequests;
import ai.xdev.aisdlc.web.PageResponse;
import ai.xdev.aisdlc.web.EvidenceRepositoryContracts.EvidenceAssetListItem;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EvidenceRepositoryService {
  public record Download(EvidenceAsset asset, URI url) {}
  private final ProjectAccessService access;
  private final EvidenceAssetRepository assets;
  private final ValidationEvidenceRepository validationEvidences;
  private final ValidationRunRepository validationRuns;
  private final ObjectStoragePort storage;
  private final EvidenceStorageProperties properties;
  private final AuditService audit;
  private final org.springframework.beans.factory.ObjectProvider<ChaosFaultRegistry> chaosFaults;
  private ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry = ai.xdev.aisdlc.telemetry.GovernanceTelemetry.inert();
  @org.springframework.beans.factory.annotation.Autowired public void setTelemetry(ai.xdev.aisdlc.telemetry.GovernanceTelemetry telemetry) { this.telemetry = telemetry; }

  public EvidenceRepositoryService(ProjectAccessService access, EvidenceAssetRepository assets, ValidationEvidenceRepository validationEvidences, ValidationRunRepository validationRuns, ObjectStoragePort storage, EvidenceStorageProperties properties, AuditService audit) {
    this(access, assets, validationEvidences, validationRuns, storage, properties, audit, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public EvidenceRepositoryService(ProjectAccessService access, EvidenceAssetRepository assets, ValidationEvidenceRepository validationEvidences, ValidationRunRepository validationRuns, ObjectStoragePort storage, EvidenceStorageProperties properties, AuditService audit, org.springframework.beans.factory.ObjectProvider<ChaosFaultRegistry> chaosFaults) {
    this.access = access; this.assets = assets; this.validationEvidences = validationEvidences; this.validationRuns = validationRuns; this.storage = storage; this.properties = properties; this.audit = audit; this.chaosFaults = chaosFaults;
  }

  @Transactional
  public EvidenceAsset upload(UUID projectId, String subject, String idempotencyKey, EvidenceAssetType assetType, EvidenceAccessLevel accessLevel, UUID validationEvidenceId, String filename, String contentType, byte[] bytes, String expectedSha256) {
    return telemetry.recordUnchecked("aisdlc.evidence.write", "evidence-durability", () -> storeEvidence(projectId, subject, idempotencyKey, assetType, accessLevel, validationEvidenceId, filename, contentType, bytes, expectedSha256));
  }

  private EvidenceAsset storeEvidence(UUID projectId, String subject, String idempotencyKey, EvidenceAssetType assetType, EvidenceAccessLevel accessLevel, UUID validationEvidenceId, String filename, String contentType, byte[] bytes, String expectedSha256) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Evidence file must not be empty");
    if (bytes.length > properties.getMaxUploadBytes()) throw new IllegalArgumentException("Evidence file exceeds the configured upload limit");
    if (contentType == null || contentType.isBlank() || contentType.length() > 255) throw new IllegalArgumentException("A valid content type is required");
    if (validationEvidenceId != null) requireValidationEvidenceInProject(projectId, validationEvidenceId);
    String digest = sha256(bytes);
    if (expectedSha256 != null && !expectedSha256.isBlank() && !digest.equalsIgnoreCase(expectedSha256.trim())) throw new IllegalArgumentException("Evidence digest does not match X-Content-SHA256");
    String requestKey = normalizeIdempotencyKey(idempotencyKey, projectId, assetType, accessLevel, validationEvidenceId, digest);
    var existing = assets.findByProjectIdAndIdempotencyKey(projectId, requestKey);
    if (existing.isPresent()) {
      EvidenceAsset value = existing.get();
      if (value.getSha256Digest().equals(digest) && value.getAssetType() == assetType && value.getAccessLevel() == (accessLevel == null ? EvidenceAccessLevel.PROJECT : accessLevel) && java.util.Objects.equals(value.getValidationEvidenceId(), validationEvidenceId)) return value;
      throw new IllegalStateException("Idempotency key was already used for a different evidence asset");
    }
    String safeFilename = safeFilename(filename);
    String key = "projects/" + projectId + "/evidence-assets/" + UUID.randomUUID() + "/" + safeFilename;
    // Fail closed before the object write: an evidence-dependent action must not proceed on unverified storage.
    if (chaosFaults != null) chaosFaults.ifAvailable(registry -> registry.check(ChaosFaultRegistry.Component.EVIDENCE_STORAGE));
    ObjectStoragePort.StoredObject stored = storage.store(new ObjectStoragePort.Upload(key, contentType, bytes, digest, Map.of("sha256", digest, "project-id", projectId.toString(), "uploaded-by", subject)));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) { if (status == STATUS_ROLLED_BACK) safelyDelete(stored); }
      });
    }
    EvidenceAsset asset = assets.save(new EvidenceAsset(projectId, validationEvidenceId, assetType, safeFilename, contentType, stored.sizeBytes(), stored.bucket(), stored.key(), digest, requestKey, subject, accessLevel == null ? EvidenceAccessLevel.PROJECT : accessLevel));
    audit.append(project.getOrganizationId(), projectId, subject, "evidence.asset.uploaded", "evidence_asset", asset.getId().toString(), "{\"sha256\":\"" + digest + "\",\"sizeBytes\":" + stored.sizeBytes() + "}");
    return asset;
  }

  public PageResponse<EvidenceAssetListItem> list(UUID projectId, String subject, int page, int size) {
    access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    Page<EvidenceAsset> result = assets.findByProjectIdAndDeletedAtIsNull(projectId, PageRequests.of(page, size, "uploadedAt,desc", "uploadedAt", "filename", "assetType", "sizeBytes"));
    return PageResponse.from(result.map(this::toListItem));
  }

  public Download download(UUID projectId, UUID assetId, String subject) {
    EvidenceAsset asset = requireReadableAsset(projectId, assetId, subject);
    return new Download(asset, storage.generatePresignedGetUrl(asset.getS3Bucket(), asset.getS3Key(), properties.getPresignTtl()));
  }

  @Transactional
  public EvidenceAsset applyRetention(UUID projectId, UUID assetId, String subject, ObjectLockMode mode, Instant retentionUntil) {
    if (mode == null || retentionUntil == null) throw new IllegalArgumentException("Retention mode and expiry are required");
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.REVIEWER);
    EvidenceAsset asset = requireActiveAsset(projectId, assetId);
    if (!retentionUntil.isAfter(Instant.now())) throw new IllegalArgumentException("Retention expiry must be in the future");
    if (asset.getRetentionUntil() != null && !retentionUntil.isAfter(asset.getRetentionUntil())) throw new IllegalArgumentException("Retention may only be extended");
    if (asset.getObjectLockMode() == ObjectLockMode.COMPLIANCE && mode != ObjectLockMode.COMPLIANCE) throw new IllegalStateException("Compliance retention cannot be downgraded");
    storage.applyRetentionLock(asset.getS3Bucket(), asset.getS3Key(), mode, retentionUntil);
    asset.applyRetention(mode, retentionUntil);
    audit.append(project.getOrganizationId(), projectId, subject, "evidence.asset.retention.locked", "evidence_asset", assetId.toString(), "{\"mode\":\"" + mode.name() + "\",\"retentionUntil\":\"" + retentionUntil + "\"}");
    return asset;
  }

  @Transactional
  public void softDelete(UUID projectId, UUID assetId, String subject) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.REVIEWER);
    EvidenceAsset asset = requireActiveAsset(projectId, assetId);
    asset.softDelete(Instant.now());
    audit.append(project.getOrganizationId(), projectId, subject, "evidence.asset.soft_deleted", "evidence_asset", assetId.toString(), "{\"sha256\":\"" + asset.getSha256Digest() + "\"}");
  }

  private EvidenceAsset requireReadableAsset(UUID projectId, UUID assetId, String subject) {
    EvidenceAsset asset = requireActiveAsset(projectId, assetId);
    switch (asset.getAccessLevel()) {
      case PROJECT -> access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
      case REVIEWERS -> access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.REVIEWER);
      case OWNERS -> access.requireMembership(projectId, subject, MembershipRole.OWNER);
    }
    return asset;
  }
  private EvidenceAsset requireActiveAsset(UUID projectId, UUID assetId) { return assets.findByIdAndProjectIdAndDeletedAtIsNull(assetId, projectId).orElseThrow(() -> new IllegalArgumentException("Evidence asset not found")); }
  private void requireValidationEvidenceInProject(UUID projectId, UUID validationEvidenceId) {
    ValidationEvidence evidence = validationEvidences.findById(validationEvidenceId).orElseThrow(() -> new IllegalArgumentException("Validation evidence not found"));
    boolean belongs = validationRuns.findById(evidence.getValidationRunId()).map(run -> run.getProjectId().equals(projectId)).orElse(false);
    if (!belongs) throw new IllegalArgumentException("Validation evidence does not belong to this project");
  }
  private void safelyDelete(ObjectStoragePort.StoredObject stored) { try { storage.delete(stored.bucket(), stored.key()); } catch (RuntimeException ignored) { } }
  private String normalizeIdempotencyKey(String key, UUID projectId, EvidenceAssetType assetType, EvidenceAccessLevel accessLevel, UUID validationEvidenceId, String digest) {
    String derived = "evidence-" + sha256((projectId + "|" + assetType + "|" + (accessLevel == null ? EvidenceAccessLevel.PROJECT : accessLevel) + "|" + String.valueOf(validationEvidenceId) + "|" + digest).getBytes(StandardCharsets.UTF_8));
    String value = key == null || key.isBlank() ? derived : key.trim();
    if (!value.matches("[-A-Za-z0-9._:]{8,120}")) throw new IllegalArgumentException("Idempotency-Key must contain 8-120 URL-safe characters");
    return value;
  }
  private EvidenceAssetListItem toListItem(EvidenceAsset asset) { return new EvidenceAssetListItem(asset.getId(), asset.getValidationEvidenceId(), asset.getAssetType(), asset.getFilename(), asset.getContentType(), asset.getSizeBytes(), asset.getSha256Digest(), asset.getObjectLockMode(), asset.getRetentionUntil(), asset.getUploadedBy(), asset.getUploadedAt(), asset.getAccessLevel()); }
  private String safeFilename(String value) {
    String source = value == null ? "evidence.bin" : value.replace('\\', '/');
    String name = source.substring(source.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
    if (name.isBlank() || name.equals(".") || name.equals("..")) name = "evidence.bin";
    if (name.length() > 180) name = name.substring(0, 180);
    return name;
  }
  private String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); } }
}
