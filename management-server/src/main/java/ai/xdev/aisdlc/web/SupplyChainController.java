package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.service.SupplyChainService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/supply-chain")
public class SupplyChainController {
  record Created(UUID id) {}
  record ProvenanceInput(UUID sbomAssetId, UUID attestationEvidenceAssetId, @NotBlank @Size(max = 300) String artifactName, @NotBlank @Pattern(regexp = "^sha256:[a-fA-F0-9]{64}$") String artifactDigest, @NotBlank @Size(max = 500) String sourceRepository, @NotBlank @Size(min = 7, max = 128) String sourceRevision, @NotBlank @Size(max = 120) String buildSystem, @Size(max = 2000) String buildUrl, @NotBlank @Size(max = 500) String signerIdentity, @NotNull ProvenanceSignatureMethod signatureMethod, @Size(max = 2000) String attestationReference) {}
  record VerificationInput(@NotNull ProvenanceVerificationStatus status, @NotBlank @Size(max = 4000) String note) {}
  private final SupplyChainService service;
  public SupplyChainController(SupplyChainService service) { this.service = service; }

  @PostMapping(value = "/sboms", consumes = "multipart/form-data") @ResponseStatus(HttpStatus.CREATED)
  Created ingest(@PathVariable UUID projectId, @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "PROJECT") EvidenceAccessLevel accessLevel, @RequestParam(required = false) @Size(max = 200) String releaseReference, @RequestHeader(value = "X-Content-SHA256", required = false) @Pattern(regexp = "^$|^[a-fA-F0-9]{64}$") String digest, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @AuthenticationPrincipal Jwt jwt) throws IOException {
    return new Created(service.ingestSbom(projectId, jwt.getSubject(), idempotencyKey, file.getOriginalFilename(), file.getContentType(), file.getBytes(), digest, accessLevel, releaseReference).getId());
  }
  @GetMapping("/sboms") PageResponse<SupplyChainService.SbomView> sboms(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return service.listSboms(projectId, jwt.getSubject(), page, size); }
  @PostMapping("/provenance") @ResponseStatus(HttpStatus.CREATED) Created provenance(@PathVariable UUID projectId, @RequestBody @Valid ProvenanceInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(service.recordProvenance(projectId, jwt.getSubject(), input.sbomAssetId(), input.attestationEvidenceAssetId(), input.artifactName(), input.artifactDigest(), input.sourceRepository(), input.sourceRevision(), input.buildSystem(), input.buildUrl(), input.signerIdentity(), input.signatureMethod(), input.attestationReference()).getId()); }
  @GetMapping("/provenance") PageResponse<SupplyChainService.ProvenanceView> provenance(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return service.listProvenance(projectId, jwt.getSubject(), page, size); }
  @PostMapping("/provenance/{recordId}/verification") SupplyChainService.ProvenanceView verify(@PathVariable UUID projectId, @PathVariable UUID recordId, @RequestBody @Valid VerificationInput input, @AuthenticationPrincipal Jwt jwt) { return service.verifyProvenance(projectId, recordId, jwt.getSubject(), input.status(), input.note()); }
}
