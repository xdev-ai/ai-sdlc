package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.ValidationService;
import ai.xdev.aisdlc.web.ValidationContracts.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ValidationController {
  private final ValidationService validation;
  public ValidationController(ValidationService validation) { this.validation = validation; }
  @PostMapping("/cli/projects/{projectId}/validation-runs") @ResponseStatus(HttpStatus.CREATED)
  ValidationRunView ingest(@PathVariable UUID projectId, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody @Valid ValidationRunRequest request, @AuthenticationPrincipal Jwt jwt) { return validation.ingest(projectId, jwt.getSubject(), idempotencyKey, request); }
  @GetMapping("/projects/{projectId}/validation-runs")
  List<ValidationRunView> list(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return validation.list(projectId, jwt.getSubject()); }
}

