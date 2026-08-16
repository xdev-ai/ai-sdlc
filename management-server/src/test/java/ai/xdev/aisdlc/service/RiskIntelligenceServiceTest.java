package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import ai.xdev.aisdlc.domain.Project;
import ai.xdev.aisdlc.repo.Repositories.RiskScoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class RiskIntelligenceServiceTest {
  @Test
  void computesExplainableBoundedScoreAndAppendsAuditEvidence() {
    ProjectAccessService access = mock(ProjectAccessService.class);
    AuditService audit = mock(AuditService.class);
    RiskScoreRepository scores = mock(RiskScoreRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID organizationId = UUID.randomUUID(), projectId = UUID.randomUUID(), scoreId = UUID.randomUUID();
    when(access.requireMembership(eq(projectId), eq("reviewer"), eq(MembershipRole.OWNER), eq(MembershipRole.REVIEWER))).thenReturn(new Project(organizationId, "risk", "Risk", null));
    doReturn(0L).when(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    doReturn(List.of()).when(jdbc).queryForList(anyString(), any(Object[].class));
    when(scores.save(any())).thenAnswer(call -> {
      var score = call.getArgument(0, ai.xdev.aisdlc.domain.RiskScore.class);
      ReflectionTestUtils.setField(score, "id", scoreId);
      return score;
    });

    RiskIntelligenceService service = new RiskIntelligenceService(access, audit, scores, jdbc, new ObjectMapper());
    var result = service.recompute(projectId, "reviewer");

    assertEquals(0, result.score());
    assertEquals("LOW", result.riskBand());
    assertEquals(RiskIntelligenceService.FORMULA_VERSION, result.formulaVersion());
    assertEquals(0, result.components().get("findingRisk"));
    assertEquals(0, result.sourceSummary().get("criticalFindings90d"));
    verify(audit).append(eq(organizationId), eq(projectId), eq("reviewer"), eq("risk_score.computed"), eq("risk_score"), eq(scoreId.toString()), contains("risk.v1"));
  }
}
