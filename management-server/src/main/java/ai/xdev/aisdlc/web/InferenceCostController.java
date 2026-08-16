package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.InferenceCostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/inference-costs")
public class InferenceCostController {
  record Created(UUID id) {}
  record UsageInput(@NotBlank @Size(max=240) String sourceEventKey,@NotBlank @Size(max=160) String provider,@NotBlank @Size(max=240) String modelName,@Size(max=240) String modelVersion,Instant occurredAt,@Min(0) long inputTokens,@Min(0) long outputTokens,@NotBlank @Pattern(regexp="^[A-Za-z]{3}$") String currencyCode,@Min(0) long sourceCostMinor,@NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$") String sourceClaimSha256) {}
  record ForecastInput(@NotBlank @Pattern(regexp="^[A-Za-z]{3}$") String currencyCode,@Min(1) @Max(90) int horizonDays) {}
  private final InferenceCostService service; public InferenceCostController(InferenceCostService service){this.service=service;}
  @PostMapping("/usage") @ResponseStatus(HttpStatus.CREATED) Created ingest(@PathVariable UUID projectId,@RequestBody @Valid UsageInput input,@AuthenticationPrincipal Jwt jwt){return new Created(service.ingest(projectId,jwt.getSubject(),input.sourceEventKey(),input.provider(),input.modelName(),input.modelVersion(),input.occurredAt(),input.inputTokens(),input.outputTokens(),input.currencyCode(),input.sourceCostMinor(),input.sourceClaimSha256()).id());}
  @PostMapping("/forecasts") @ResponseStatus(HttpStatus.CREATED) InferenceCostService.ForecastView forecast(@PathVariable UUID projectId,@RequestBody @Valid ForecastInput input,@AuthenticationPrincipal Jwt jwt){return service.forecast(projectId,jwt.getSubject(),input.currencyCode(),input.horizonDays());}
}
