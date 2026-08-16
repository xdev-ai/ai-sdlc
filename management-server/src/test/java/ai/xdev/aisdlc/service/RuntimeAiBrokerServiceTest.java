package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class RuntimeAiBrokerServiceTest {
  @Test
  void rejectsUnknownProviderBeforeCallingBudgetOrPolicy() throws Exception {
    var jdbc=mock(JdbcTemplate.class); var access=mock(ProjectAccessService.class); var governance=mock(RuntimeAiGovernanceService.class); var budgets=mock(BudgetEnforcementService.class); var audit=mock(AuditService.class); var service=new RuntimeAiBrokerService(jdbc,access,governance,budgets,audit); UUID project=UUID.randomUUID();
    when(jdbc.queryForObject(startsWith("select exists(select 1 from runtime_ai_workload_identities"),eq(Boolean.class),eq(project),eq("workload-1"))).thenReturn(true);
    when(jdbc.queryForObject(startsWith("select policy_bundle_id from runtime_ai_provider_profiles"),eq(UUID.class),eq(project),eq("provider-a"),eq("model-a"))).thenThrow(new EmptyResultDataAccessException(1));
    var result=service.preflight(project,"workload-1",UUID.randomUUID(),"provider-a","model-a","a".repeat(64),new ObjectMapper().readTree("{}"),false);
    assertEquals("DENY",result.decision()); assertEquals("MODEL_OR_PROVIDER_NOT_ALLOWLISTED",result.reasonCode()); verifyNoInteractions(governance,budgets,audit);
  }
}
