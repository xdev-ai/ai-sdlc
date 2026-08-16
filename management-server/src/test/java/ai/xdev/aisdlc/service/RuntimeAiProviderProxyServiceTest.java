package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RuntimeAiProviderProxyServiceTest {
  private final ObjectMapper json=new ObjectMapper();

  @Test
  @SuppressWarnings({"unchecked","rawtypes"})
  void neverCallsTransportWhenPreflightDenies() throws Exception {
    JdbcTemplate jdbc=mock(JdbcTemplate.class); RuntimeAiBrokerService broker=mock(RuntimeAiBrokerService.class); ProviderHttpTransport transport=mock(ProviderHttpTransport.class);
    when(broker.preflight(any(),anyString(),any(),anyString(),anyString(),anyString(),any(),eq(false))).thenReturn(new RuntimeAiBrokerService.AuthorizationView("DENY","POLICY_DENIED",null,null));
    var service=service(jdbc,broker,transport); var result=service.invoke(UUID.randomUUID(),"workload",request());
    assertEquals("BLOCKED",result.outcome()); assertEquals("POLICY_DENIED",result.reasonCode()); verifyNoInteractions(transport);
  }

  @Test
  @SuppressWarnings({"unchecked","rawtypes"})
  void forwardsOnlyRegisteredEndpointAndIdempotencyKeyAfterAuthorization() throws Exception {
    UUID projectId=UUID.randomUUID(), profileId=UUID.randomUUID(), decisionId=UUID.randomUUID(); JdbcTemplate jdbc=mock(JdbcTemplate.class); RuntimeAiBrokerService broker=mock(RuntimeAiBrokerService.class); ProviderHttpTransport transport=mock(ProviderHttpTransport.class); ProjectAccessService access=mock(ProjectAccessService.class); Project project=mock(Project.class); when(project.getOrganizationId()).thenReturn(UUID.randomUUID()); when(access.requireProject(projectId)).thenReturn(project);
    when(broker.preflight(eq(projectId),eq("workload"),any(),eq("provider-a"),eq("model-a"),anyString(),any(),eq(false))).thenReturn(new RuntimeAiBrokerService.AuthorizationView("ALLOW","POLICY_PASS",decisionId,UUID.randomUUID()));
    when(jdbc.queryForObject(startsWith("select id,endpoint_uri"),any(RowMapper.class),eq(projectId),eq("provider-a"),eq("model-a"))).thenReturn(proxyProfile(profileId));
    when(jdbc.update(startsWith("insert into runtime_ai_provider_dispatches"),any(Object[].class))).thenReturn(1);
    when(transport.execute(any())).thenReturn(new ProviderHttpTransport.Response(200,"{\"ok\":true}"));
    var resolver=mock(ProviderCredentialResolver.class); when(resolver.resolve("secret/provider-a",null,false)).thenReturn(new ProviderCredentialResolver.CredentialMaterial("Bearer transient",null));
    var service=new RuntimeAiProviderProxyService(jdbc,broker,access,mock(AuditService.class),resolver,transport); var invocation=request(); var result=service.invoke(projectId,"workload",invocation);
    assertEquals("COMPLETE",result.outcome()); assertEquals(200,result.httpStatus()); assertEquals("{\"ok\":true}",result.responseBody());
    verify(transport).execute(argThat(r -> r.endpoint().toString().equals("https://provider.example/v1/infer") && r.idempotencyKey().equals(invocation.idempotencyKey().toString()) && r.authorizationHeader().equals("Bearer transient")));
  }

  @Test
  @SuppressWarnings({"unchecked","rawtypes"})
  void boundsRetriesAndreturnsNoRawPayloadWhenCredentialResolutionFails() throws Exception {
    UUID projectId=UUID.randomUUID(), profileId=UUID.randomUUID(); JdbcTemplate jdbc=mock(JdbcTemplate.class); RuntimeAiBrokerService broker=mock(RuntimeAiBrokerService.class); ProviderHttpTransport transport=mock(ProviderHttpTransport.class); ProjectAccessService access=mock(ProjectAccessService.class); Project project=mock(Project.class); when(project.getOrganizationId()).thenReturn(UUID.randomUUID()); when(access.requireProject(projectId)).thenReturn(project);
    when(broker.preflight(eq(projectId),eq("workload"),any(),anyString(),anyString(),anyString(),any(),eq(false))).thenReturn(new RuntimeAiBrokerService.AuthorizationView("ALLOW","POLICY_PASS",UUID.randomUUID(),UUID.randomUUID()));
    when(jdbc.queryForObject(startsWith("select id,endpoint_uri"),any(RowMapper.class),eq(projectId),anyString(),anyString())).thenReturn(proxyProfile(profileId));
    when(jdbc.update(startsWith("insert into runtime_ai_provider_dispatches"),any(Object[].class))).thenReturn(1);
    var resolver=mock(ProviderCredentialResolver.class); when(resolver.resolve(anyString(),isNull(),eq(false))).thenThrow(new IllegalStateException("unavailable"));
    var service=new RuntimeAiProviderProxyService(jdbc,broker,access,mock(AuditService.class),resolver,transport); var result=service.invoke(projectId,"workload",request());
    assertEquals("FAILED",result.outcome()); assertEquals("PROVIDER_CREDENTIAL_UNAVAILABLE",result.reasonCode()); assertNull(result.responseBody()); verifyNoInteractions(transport);
  }

  @Test
  @SuppressWarnings({"unchecked","rawtypes"})
  void retriesOnlyRetryableProviderFailureAndAuditsDigestsNotPayload() throws Exception {
    UUID projectId=UUID.randomUUID(), profileId=UUID.randomUUID(); JdbcTemplate jdbc=mock(JdbcTemplate.class); RuntimeAiBrokerService broker=mock(RuntimeAiBrokerService.class); ProviderHttpTransport transport=mock(ProviderHttpTransport.class); ProjectAccessService access=mock(ProjectAccessService.class); Project project=mock(Project.class); AuditService audit=mock(AuditService.class); when(project.getOrganizationId()).thenReturn(UUID.randomUUID()); when(access.requireProject(projectId)).thenReturn(project);
    when(broker.preflight(eq(projectId),eq("workload"),any(),anyString(),anyString(),anyString(),any(),eq(false))).thenReturn(new RuntimeAiBrokerService.AuthorizationView("ALLOW","POLICY_PASS",UUID.randomUUID(),UUID.randomUUID()));
    when(jdbc.queryForObject(startsWith("select id,endpoint_uri"),any(RowMapper.class),eq(projectId),anyString(),anyString())).thenReturn(proxyProfile(profileId));
    when(jdbc.update(startsWith("insert into runtime_ai_provider_dispatches"),any(Object[].class))).thenReturn(1);
    when(transport.execute(any())).thenReturn(new ProviderHttpTransport.Response(503,"temporary"),new ProviderHttpTransport.Response(200,"{\"ok\":true}"));
    var resolver=mock(ProviderCredentialResolver.class); when(resolver.resolve("secret/provider-a",null,false)).thenReturn(new ProviderCredentialResolver.CredentialMaterial("Bearer transient",null));
    var service=new RuntimeAiProviderProxyService(jdbc,broker,access,audit,resolver,transport); var result=service.invoke(projectId,"workload",request());
    assertEquals("COMPLETE",result.outcome()); assertEquals(2,result.attempts()); verify(transport,times(2)).execute(any());
    verify(audit).append(any(),eq(projectId),eq("workload"),eq("runtime_ai.provider_dispatched"),eq("runtime_ai_provider_dispatch"),anyString(),argThat(payload -> !payload.contains("sensitive but transient") && !payload.contains("Bearer transient") && payload.contains("requestSha256")));
  }

  private RuntimeAiProviderProxyService service(JdbcTemplate jdbc,RuntimeAiBrokerService broker,ProviderHttpTransport transport){return new RuntimeAiProviderProxyService(jdbc,broker,mock(ProjectAccessService.class),mock(AuditService.class),mock(ProviderCredentialResolver.class),transport);}
  private RuntimeAiProviderProxyService.InvocationRequest request() throws Exception{return new RuntimeAiProviderProxyService.InvocationRequest(UUID.randomUUID(),"provider-a","model-a","a".repeat(64),json.readTree("{\"approved\":true}"),UUID.randomUUID(),json.readTree("{\"input\":\"sensitive but transient\"}"));}
  private RuntimeAiProviderProxyService.Profile proxyProfile(UUID id) { return new RuntimeAiProviderProxyService.Profile(id, URI.create("https://provider.example/v1/infer"), "secret/provider-a", false, null, 1_000, 2); }
}
