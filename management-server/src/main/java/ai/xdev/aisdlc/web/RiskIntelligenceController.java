package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.RiskIntelligenceService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/risk-intelligence")
public class RiskIntelligenceController {
  private final RiskIntelligenceService risk;
  public RiskIntelligenceController(RiskIntelligenceService risk) { this.risk = risk; }
  @PostMapping("/recompute") @PreAuthorize("hasAnyRole('admin','reviewer')")
  RiskIntelligenceService.RiskScoreView recompute(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return risk.recompute(projectId, jwt.getSubject()); }
  @GetMapping("/latest")
  RiskIntelligenceService.RiskScoreView latest(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return risk.latest(projectId, jwt.getSubject()); }
  @GetMapping("/trend")
  PageResponse<RiskIntelligenceService.RiskScoreView> trend(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size, @AuthenticationPrincipal Jwt jwt) { return risk.trend(projectId, jwt.getSubject(), page, size); }
}
