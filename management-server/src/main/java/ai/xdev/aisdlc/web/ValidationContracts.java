package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ValidationContracts {
  private ValidationContracts() {}
  public record FindingInput(@NotNull Severity severity, @NotBlank @Size(max = 100) String code, @NotBlank @Size(max = 5000) String message, @Size(max = 400) String path, @Positive Integer line, @Size(max = 1000) String evidenceUri) {}
  public record EvidenceInput(@NotBlank @Size(max = 80) String type, @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String digestSha256, @Size(max = 1000) String uri) {}
  public record ValidationRunRequest(@NotNull ValidationStatus status, @NotBlank @Size(max = 120) String cliVersion, @NotBlank @Size(max = 160) String kitVersion, @NotBlank @Size(max = 160) String modelPin, boolean bare, @NotNull @Size(max = 1000) List<@Valid FindingInput> findings, @NotNull @Size(max = 1000) List<@Valid EvidenceInput> evidence) {}
  public record FindingView(Severity severity, String code, String message) {}
  public record ValidationRunView(UUID id, UUID projectId, ValidationStatus status, String idempotencyKey, List<FindingView> findings) {}
  /**
   * {@code idempotencyKey} is the only field that distinguishes one run from another at a glance, and it was missing
   * here while the detail view carried it. The portal renders it twice — in the run list and in the run picker — so
   * every row showed a blank key and every option in the picker read "PASSED · " with nothing after the separator,
   * making the runs indistinguishable in the one control built for choosing between them.
   */
  public record ValidationRunListItem(UUID id, ValidationStatus status, String idempotencyKey, String cliVersion, String kitVersion, String modelPin, String actorSubject, Instant completedAt, int findingCount) {}
  public record FindingTriageRequest(@NotNull FindingTriageStatus status, @Size(max = 2000) String note) {}
  public record EvidenceRetentionRequest(@NotNull @FutureOrPresent Instant retentionUntil) {}
  public record FindingDetailView(UUID id, Severity severity, String code, String message, String path, Integer line, String evidenceUri, FindingTriageStatus triageStatus, String triagedBy, Instant triagedAt, String triageNote) {}
  public record EvidenceView(UUID id, String type, String digestSha256, String uri, String metadata, Instant createdAt, Instant retentionUntil) {}
  public record ValidationRunDetailView(UUID id, UUID projectId, ValidationStatus status, String idempotencyKey, String cliVersion, String kitVersion, String modelPin, String actorSubject, Instant completedAt, List<FindingDetailView> findings, List<EvidenceView> evidence) {}
}
