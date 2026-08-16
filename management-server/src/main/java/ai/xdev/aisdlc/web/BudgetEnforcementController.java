package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.BudgetEnforcementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/inference-costs/budget")
public class BudgetEnforcementController {
  record BudgetInput(@NotBlank @Pattern(regexp="^[A-Za-z]{3}$") String currencyCode, @Min(1) long monthlyLimitMinor, @Min(1) @Max(99) int warningPercent, @Pattern(regexp="ADVISORY|HOLD") String enforcementMode) {}
  record ExceptionInput(@NotNull UUID approvalRequestId, @NotNull LocalDate expiresAt, @NotBlank @Pattern(regexp="^[a-fA-F0-9]{64}$") String rationaleSha256) {}
  private final BudgetEnforcementService budgets;
  public BudgetEnforcementController(BudgetEnforcementService budgets) { this.budgets=budgets; }
  @PutMapping public BudgetEnforcementService.BudgetPolicyView configure(@PathVariable UUID projectId,@RequestBody @Valid BudgetInput input,@AuthenticationPrincipal Jwt jwt){return budgets.configure(projectId,jwt.getSubject(),input.currencyCode(),input.monthlyLimitMinor(),input.warningPercent(),input.enforcementMode());}
  @PostMapping("/evaluate") public BudgetEnforcementService.BudgetDecisionView evaluate(@PathVariable UUID projectId,@AuthenticationPrincipal Jwt jwt){return budgets.evaluate(projectId,jwt.getSubject());}
  @PostMapping("/exceptions") @ResponseStatus(HttpStatus.ACCEPTED) public void requestException(@PathVariable UUID projectId,@RequestBody @Valid ExceptionInput input,@AuthenticationPrincipal Jwt jwt){budgets.requestException(projectId,jwt.getSubject(),input.approvalRequestId(),input.expiresAt(),input.rationaleSha256());}
}
