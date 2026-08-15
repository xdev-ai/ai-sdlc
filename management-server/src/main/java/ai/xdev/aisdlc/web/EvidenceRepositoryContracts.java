package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAccessLevel;
import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAssetType;
import ai.xdev.aisdlc.domain.DomainTypes.ObjectLockMode;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class EvidenceRepositoryContracts {
  private EvidenceRepositoryContracts() {}
  public record EvidenceAssetListItem(UUID id, UUID validationEvidenceId, EvidenceAssetType assetType, String filename, String contentType, long sizeBytes, String sha256Digest, ObjectLockMode objectLockMode, Instant retentionUntil, String uploadedBy, Instant uploadedAt, EvidenceAccessLevel accessLevel) {}
  public record EvidenceAssetDetailView(UUID id, UUID projectId, UUID validationEvidenceId, EvidenceAssetType assetType, String filename, String contentType, long sizeBytes, String sha256Digest, ObjectLockMode objectLockMode, Instant retentionUntil, String uploadedBy, Instant uploadedAt, EvidenceAccessLevel accessLevel, String downloadUrl) {}
  public record RetentionRequest(@NotNull ObjectLockMode mode, @NotNull @Future Instant retentionUntil) {}
}
