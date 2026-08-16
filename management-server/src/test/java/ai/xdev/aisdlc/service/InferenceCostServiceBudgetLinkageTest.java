package ai.xdev.aisdlc.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.Project;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class InferenceCostServiceBudgetLinkageTest {
  @Test
  void evaluatesBudgetOnlyAfterNewUsageEventIsPersisted() {
    UUID projectId=UUID.randomUUID(); Project project=mock(Project.class); when(project.getOrganizationId()).thenReturn(UUID.randomUUID());
    JdbcTemplate jdbc=mock(JdbcTemplate.class); ProjectAccessService access=mock(ProjectAccessService.class); AuditService audit=mock(AuditService.class); BudgetEnforcementService budgets=mock(BudgetEnforcementService.class);
    when(access.requireMembership(eq(projectId),eq("developer"),any(ai.xdev.aisdlc.domain.DomainTypes.MembershipRole[].class))).thenReturn(project);
    when(jdbc.update(startsWith("insert into inference_usage_events"),any(Object[].class))).thenReturn(1);
    InferenceCostService service=new InferenceCostService(jdbc,access,audit,budgets);

    service.ingest(projectId,"developer","provider-event-1","provider-a","model-a","v1",Instant.parse("2026-08-16T00:00:00Z"),10,20,"USD",75,"a".repeat(64));

    verify(budgets).evaluateAfterUsage(eq(projectId),any(UUID.class),eq("USD"),eq("developer"));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void replayedUsageEventDoesNotReevaluateOrRenotifyBudget() {
    UUID projectId=UUID.randomUUID(); Project project=mock(Project.class); when(project.getOrganizationId()).thenReturn(UUID.randomUUID());
    JdbcTemplate jdbc=mock(JdbcTemplate.class); ProjectAccessService access=mock(ProjectAccessService.class); AuditService audit=mock(AuditService.class); BudgetEnforcementService budgets=mock(BudgetEnforcementService.class);
    when(access.requireMembership(eq(projectId),eq("developer"),any(ai.xdev.aisdlc.domain.DomainTypes.MembershipRole[].class))).thenReturn(project);
    when(jdbc.update(startsWith("insert into inference_usage_events"),any(Object[].class))).thenReturn(0);
    when(jdbc.queryForObject(startsWith("select id,source_event_key"),any(RowMapper.class),eq(projectId),eq("provider-event-1"))).thenReturn(new InferenceCostService.UsageView(UUID.randomUUID(),"provider-event-1","provider-a","model-a",Instant.parse("2026-08-16T00:00:00Z"),10,20,"USD",75));
    InferenceCostService service=new InferenceCostService(jdbc,access,audit,budgets);

    service.ingest(projectId,"developer","provider-event-1","provider-a","model-a","v1",Instant.parse("2026-08-16T00:00:00Z"),10,20,"USD",75,"a".repeat(64));

    verifyNoInteractions(budgets);
  }
}
