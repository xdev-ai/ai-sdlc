package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class ValidationContracts {
  private ValidationContracts() {}
  public record FindingInput(@NotNull Severity severity, @NotBlank @Size(max = 100) String code, @NotBlank @Size(max = 5000) String message, @Size(max = 400) String path, @Positive Integer line, @Size(max = 1000) String evidenceUri) {}
  public record EvidenceInput(@NotBlank @Size(max = 80) String type, @Pattern(regexp = "^[a-fA-F0-9]{64}$") String digestSha256, @Size(max = 1000) String uri) {}
  public record ValidationRunRequest(@NotNull ValidationStatus status, @NotBlank @Size(max = 120) String cliVersion, @NotBlank @Size(max = 160) String kitVersion, @NotBlank @Size(max = 160) String modelPin, boolean bare, @NotNull List<@Valid FindingInput> findings, @NotNull List<@Valid EvidenceInput> evidence) {}
  public record FindingView(Severity severity, String code, String message) {}
  public record ValidationRunView(UUID id, UUID projectId, ValidationStatus status, String idempotencyKey, List<FindingView> findings) {}
}

