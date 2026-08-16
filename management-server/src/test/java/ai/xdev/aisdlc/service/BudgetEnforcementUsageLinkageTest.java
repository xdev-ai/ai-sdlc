package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class BudgetEnforcementUsageLinkageTest {
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void queuesOneThresholdNotificationForFreshMatchingUsage() {
    UUID project=UUID.randomUUID(), policy=UUID.randomUUID(), usage=UUID.randomUUID(); JdbcTemplate jdbc=mock(JdbcTemplate.class); NotificationService notifications=mock(NotificationService.class);
    when(jdbc.queryForObject(startsWith("select id,currency_code"),any(RowMapper.class),eq(project))).thenReturn(new BudgetEnforcementService.BudgetPolicyView(policy,"USD",1_000,80,"HOLD",true));
    when(jdbc.queryForObject(startsWith("select coalesce(sum"),eq(Long.class),eq(project),eq("USD"),any(Instant.class))).thenReturn(800L);
    when(jdbc.queryForObject(startsWith("select exists"),eq(Boolean.class),eq(project),eq(policy))).thenReturn(false);
    when(jdbc.update(startsWith("insert into inference_budget_decisions"),any(Object[].class))).thenReturn(1);
    BudgetEnforcementService service=new BudgetEnforcementService(jdbc,mock(ProjectAccessService.class),mock(AuditService.class),notifications);

    var result=service.evaluateAfterUsage(project,usage,"usd","workload:build");

    assertNotNull(result); assertEquals("WARN",result.decision()); assertEquals("BUDGET_WARNING_THRESHOLD",result.reasonCode());
    verify(notifications).queueProjectNotification(eq(project),eq("inference_budget.threshold"),contains("warning"),contains("800 USD"),contains("inference-budget:"));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void skipsCrossCurrencyUsageWithoutCreatingAFalseBudgetDecision() {
    UUID project=UUID.randomUUID(), policy=UUID.randomUUID(); JdbcTemplate jdbc=mock(JdbcTemplate.class); NotificationService notifications=mock(NotificationService.class);
    when(jdbc.queryForObject(startsWith("select id,currency_code"),any(RowMapper.class),eq(project))).thenReturn(new BudgetEnforcementService.BudgetPolicyView(policy,"USD",1_000,80,"HOLD",true));
    BudgetEnforcementService service=new BudgetEnforcementService(jdbc,mock(ProjectAccessService.class),mock(AuditService.class),notifications);

    assertNull(service.evaluateAfterUsage(project,UUID.randomUUID(),"EUR","workload:build"));
    verifyNoInteractions(notifications);
    verify(jdbc,never()).update(startsWith("insert into inference_budget_decisions"),any(Object[].class));
  }
}
