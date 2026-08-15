package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAccessLevel;
import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAssetType;
import ai.xdev.aisdlc.service.EvidenceRepositoryService;
import ai.xdev.aisdlc.web.EvidenceRepositoryContracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/evidence-assets")
public class EvidenceRepositoryController {
  private final EvidenceRepositoryService repository;
  public EvidenceRepositoryController(EvidenceRepositoryService repository) { this.repository = repository; }

  @PostMapping(consumes = "multipart/form-data") @ResponseStatus(HttpStatus.CREATED)
  EvidenceAssetListItem upload(@PathVariable UUID projectId, @RequestPart("file") MultipartFile file, @RequestParam EvidenceAssetType assetType, @RequestParam(defaultValue = "PROJECT") EvidenceAccessLevel accessLevel, @RequestParam(required = false) UUID validationEvidenceId, @RequestHeader(value = "X-Content-SHA256", required = false) @Pattern(regexp = "^$|^[a-fA-F0-9]{64}$") String digest, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @AuthenticationPrincipal Jwt jwt) throws IOException {
    return toList(repository.upload(projectId, jwt.getSubject(), idempotencyKey, assetType, accessLevel, validationEvidenceId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), digest));
  }

  @GetMapping
  PageResponse<EvidenceAssetListItem> list(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return repository.list(projectId, jwt.getSubject(), page, size); }

  @GetMapping("/{assetId}")
  EvidenceAssetDetailView detail(@PathVariable UUID projectId, @PathVariable UUID assetId, @AuthenticationPrincipal Jwt jwt) {
    var download = repository.download(projectId, assetId, jwt.getSubject());
    var asset = download.asset();
    return new EvidenceAssetDetailView(asset.getId(), asset.getProjectId(), asset.getValidationEvidenceId(), asset.getAssetType(), asset.getFilename(), asset.getContentType(), asset.getSizeBytes(), asset.getSha256Digest(), asset.getObjectLockMode(), asset.getRetentionUntil(), asset.getUploadedBy(), asset.getUploadedAt(), asset.getAccessLevel(), download.url().toString());
  }

  @PutMapping("/{assetId}/retention")
  EvidenceAssetListItem lockRetention(@PathVariable UUID projectId, @PathVariable UUID assetId, @RequestBody @Valid RetentionRequest request, @AuthenticationPrincipal Jwt jwt) { return toList(repository.applyRetention(projectId, assetId, jwt.getSubject(), request.mode(), request.retentionUntil())); }

  @DeleteMapping("/{assetId}") @ResponseStatus(HttpStatus.NO_CONTENT)
  void softDelete(@PathVariable UUID projectId, @PathVariable UUID assetId, @AuthenticationPrincipal Jwt jwt) { repository.softDelete(projectId, assetId, jwt.getSubject()); }

  private EvidenceAssetListItem toList(ai.xdev.aisdlc.domain.EvidenceAsset asset) { return new EvidenceAssetListItem(asset.getId(), asset.getValidationEvidenceId(), asset.getAssetType(), asset.getFilename(), asset.getContentType(), asset.getSizeBytes(), asset.getSha256Digest(), asset.getObjectLockMode(), asset.getRetentionUntil(), asset.getUploadedBy(), asset.getUploadedAt(), asset.getAccessLevel()); }
}
