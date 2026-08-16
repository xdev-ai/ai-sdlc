package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.xdev.aisdlc.domain.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RuntimeAiToolBrokerServiceTest {
  private static final String TOOL = "issue-tracker-read";

  private final ObjectMapper json = new ObjectMapper();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final RuntimeAiBrokerService broker = mock(RuntimeAiBrokerService.class);
  private final ProjectAccessService access = mock(ProjectAccessService.class);
  private final AuditService audit = mock(AuditService.class);
  private final RuntimeAiToolBrokerService service = new RuntimeAiToolBrokerService(jdbc, broker, access, audit);
  private final UUID projectId = UUID.randomUUID();

  private JsonNode arguments(String body) throws Exception { return json.readTree(body); }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void capabilityExists(String impactLevel) {
    when(jdbc.queryForObject(startsWith("select id,impact_level"), any(RowMapper.class), eq(projectId), eq(TOOL)))
        .thenAnswer(invocation -> {
          var mapper = (RowMapper<Object>) invocation.getArgument(1);
          var resultSet = mock(java.sql.ResultSet.class);
          when(resultSet.getObject(1, UUID.class)).thenReturn(UUID.randomUUID());
          when(resultSet.getString(2)).thenReturn(impactLevel);
          when(resultSet.getBoolean(3)).thenReturn("HIGH_IMPACT".equals(impactLevel));
          return mapper.mapRow(resultSet, 1);
        });
  }

  private void projectExists() {
    Project project = mock(Project.class);
    when(project.getTenantId()).thenReturn(UUID.randomUUID());
    when(project.getOrganizationId()).thenReturn(UUID.randomUUID());
    when(access.requireProject(projectId)).thenReturn(project);
  }

  private void brokerAllows() {
    when(broker.authorizeTool(eq(projectId), anyString(), any(), eq(TOOL), anyString(), any(), any(), anyBoolean()))
        .thenReturn(new RuntimeAiBrokerService.AuthorizationView("ALLOW", "POLICY_PASS", UUID.randomUUID(), null));
  }

  @Test
  void issuesASingleUseGrantBoundToTheCanonicalArgumentFingerprint() throws Exception {
    capabilityExists("READ_ONLY");
    projectExists();
    brokerAllows();

    var issued = service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{\"b\":2,\"a\":1}"), null, Duration.ofSeconds(30));

    assertTrue(issued.allowed());
    assertNotNull(issued.grantSecret());
    assertTrue(issued.argumentFingerprint().matches("[0-9a-f]{64}"));
    ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(startsWith("insert into runtime_ai_tool_grants"), parameters.capture());
    // The grant secret is returned once; only its digest may reach storage.
    assertTrue(Arrays.stream(parameters.getValue()).noneMatch(value -> issued.grantSecret().equals(value)));
    assertTrue(Arrays.stream(parameters.getValue()).anyMatch(value -> issued.argumentFingerprint().equals(value)));
  }

  @Test
  void fingerprintIgnoresMemberOrderButNotValuesOrArrayOrder() throws Exception {
    assertEquals(RuntimeAiToolBrokerService.canonical(arguments("{\"b\":2,\"a\":1}")),
        RuntimeAiToolBrokerService.canonical(arguments("{\"a\":1,\"b\":2}")));
    assertNotEquals(RuntimeAiToolBrokerService.canonical(arguments("{\"a\":1}")),
        RuntimeAiToolBrokerService.canonical(arguments("{\"a\":2}")));
    assertNotEquals(RuntimeAiToolBrokerService.canonical(arguments("{\"a\":[1,2]}")),
        RuntimeAiToolBrokerService.canonical(arguments("{\"a\":[2,1]}")));
    assertEquals(RuntimeAiToolBrokerService.canonical(arguments("{\"a\":{\"y\":1,\"x\":2}}")),
        RuntimeAiToolBrokerService.canonical(arguments("{\"a\":{\"x\":2,\"y\":1}}")));
  }

  @Test
  void deniesAnUnregisteredToolWithoutConsultingPolicyOrWritingAGrant() throws Exception {
    when(jdbc.queryForObject(startsWith("select id,impact_level"), any(RowMapper.class), eq(projectId), eq(TOOL)))
        .thenThrow(new EmptyResultDataAccessException(1));

    var issued = service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{}"), null, null);

    assertFalse(issued.allowed());
    assertEquals("TOOL_NOT_ALLOWLISTED", issued.reasonCode());
    assertNull(issued.grantSecret());
    verifyNoInteractions(broker, audit);
    verify(jdbc, never()).update(startsWith("insert into runtime_ai_tool_grants"), any(Object[].class));
  }

  @Test
  void deniesWhenPolicyOrApprovalAuthorizationFails() throws Exception {
    capabilityExists("MUTATING");
    when(broker.authorizeTool(eq(projectId), anyString(), any(), eq(TOOL), anyString(), any(), any(), anyBoolean()))
        .thenReturn(new RuntimeAiBrokerService.AuthorizationView("DENY", "HUMAN_APPROVAL_REQUIRED", UUID.randomUUID(), null));

    var issued = service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{}"), null, null);

    assertFalse(issued.allowed());
    assertEquals("HUMAN_APPROVAL_REQUIRED", issued.reasonCode());
    assertNull(issued.grantSecret());
    verify(jdbc, never()).update(startsWith("insert into runtime_ai_tool_grants"), any(Object[].class));
  }

  @Test
  void refusesAHighImpactGrantWithoutAnApprovalLinkEvenIfAuthorizationPassed() throws Exception {
    capabilityExists("HIGH_IMPACT");
    brokerAllows();

    var issued = service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{}"), null, null);

    assertFalse(issued.allowed());
    assertEquals("HUMAN_APPROVAL_REQUIRED", issued.reasonCode());
    verify(jdbc, never()).update(startsWith("insert into runtime_ai_tool_grants"), any(Object[].class));
  }

  @Test
  void storesTheApprovalLinkForAnApprovedHighImpactGrant() throws Exception {
    capabilityExists("HIGH_IMPACT");
    projectExists();
    brokerAllows();
    UUID approvalRequestId = UUID.randomUUID();

    var issued = service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{}"), approvalRequestId, null);

    assertTrue(issued.allowed());
    ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(startsWith("insert into runtime_ai_tool_grants"), parameters.capture());
    assertTrue(Arrays.asList(parameters.getValue()).contains(approvalRequestId));
    assertTrue(Arrays.asList(parameters.getValue()).contains("HIGH_IMPACT"));
  }

  @Test
  void neverPersistsRawArgumentValues() throws Exception {
    capabilityExists("READ_ONLY");
    projectExists();
    brokerAllows();

    service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{\"query\":\"confidential-argument-value\"}"), null, null);

    ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(startsWith("insert into runtime_ai_tool_grants"), parameters.capture());
    assertTrue(Arrays.stream(parameters.getValue())
        .noneMatch(value -> String.valueOf(value).contains("confidential-argument-value")));
    ArgumentCaptor<String> auditPayload = ArgumentCaptor.forClass(String.class);
    verify(audit).append(any(), eq(projectId), eq("workload-1"), eq("runtime_ai.tool_grant_issued"), anyString(), anyString(), auditPayload.capture());
    assertFalse(auditPayload.getValue().contains("confidential-argument-value"));
  }

  @Test
  void rejectsAnUnboundedOrNonObjectGrantRequest() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> service.issue(projectId, "workload-1", null, TOOL, arguments("{}"), null, null));
    assertThrows(IllegalArgumentException.class, () -> service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("[1,2]"), null, null));
    capabilityExists("READ_ONLY");
    assertThrows(IllegalArgumentException.class, () -> service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{}"), null, Duration.ofHours(1)));
    assertThrows(IllegalArgumentException.class, () -> service.issue(projectId, "workload-1", UUID.randomUUID(), TOOL, arguments("{}"), null, Duration.ZERO));
  }

  @Test
  void redeemsAGrantExactlyOnceThroughOneConditionalUpdate() throws Exception {
    projectExists();
    UUID grantId = UUID.randomUUID();
    when(jdbc.update(startsWith("update runtime_ai_tool_grants set grant_status='REDEEMED'"), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(startsWith("select id from runtime_ai_tool_grants"), eq(UUID.class), anyString())).thenReturn(grantId);

    var redemption = service.redeem(projectId, "workload-1", "grant-secret", arguments("{\"a\":1}"));

    assertTrue(redemption.allowed());
    assertEquals(grantId, redemption.grantId());
    assertTrue(redemption.receiptSha256().matches("[0-9a-f]{64}"));
    verify(audit).append(any(), eq(projectId), eq("workload-1"), eq("runtime_ai.tool_grant_redeemed"), anyString(), eq(grantId.toString()), anyString());
  }

  @Test
  void reportsReplayExpiryArgumentAndSubjectMismatchWithoutRedeeming() throws Exception {
    when(jdbc.update(startsWith("update runtime_ai_tool_grants set grant_status='REDEEMED'"), any(Object[].class))).thenReturn(0);
    UUID grantId = UUID.randomUUID();
    String fingerprint = sha256Of(arguments("{\"a\":1}"));

    when(jdbc.queryForMap(startsWith("select id,workload_subject"), anyString(), eq(projectId)))
        .thenReturn(java.util.Map.of("id", grantId, "workload_subject", "workload-1", "argument_fingerprint", fingerprint, "grant_status", "REDEEMED", "expired", false));
    assertEquals("GRANT_ALREADY_REDEEMED", service.redeem(projectId, "workload-1", "secret", arguments("{\"a\":1}")).reasonCode());

    when(jdbc.queryForMap(startsWith("select id,workload_subject"), anyString(), eq(projectId)))
        .thenReturn(java.util.Map.of("id", grantId, "workload_subject", "workload-1", "argument_fingerprint", fingerprint, "grant_status", "ISSUED", "expired", true));
    assertEquals("GRANT_EXPIRED", service.redeem(projectId, "workload-1", "secret", arguments("{\"a\":1}")).reasonCode());

    when(jdbc.queryForMap(startsWith("select id,workload_subject"), anyString(), eq(projectId)))
        .thenReturn(java.util.Map.of("id", grantId, "workload_subject", "workload-2", "argument_fingerprint", fingerprint, "grant_status", "ISSUED", "expired", false));
    assertEquals("GRANT_SUBJECT_MISMATCH", service.redeem(projectId, "workload-1", "secret", arguments("{\"a\":1}")).reasonCode());

    when(jdbc.queryForMap(startsWith("select id,workload_subject"), anyString(), eq(projectId)))
        .thenReturn(java.util.Map.of("id", grantId, "workload_subject", "workload-1", "argument_fingerprint", "0".repeat(64), "grant_status", "ISSUED", "expired", false));
    assertEquals("GRANT_ARGUMENT_MISMATCH", service.redeem(projectId, "workload-1", "secret", arguments("{\"a\":1}")).reasonCode());

    when(jdbc.queryForMap(startsWith("select id,workload_subject"), anyString(), eq(projectId))).thenThrow(new EmptyResultDataAccessException(1));
    assertEquals("GRANT_UNKNOWN", service.redeem(projectId, "workload-1", "secret", arguments("{\"a\":1}")).reasonCode());

    verifyNoInteractions(audit);
  }

  @Test
  void redemptionRequiresASecretAndAnObjectOfArguments() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> service.redeem(projectId, "workload-1", null, arguments("{}")));
    assertThrows(IllegalArgumentException.class, () -> service.redeem(projectId, "workload-1", "  ", arguments("{}")));
    assertThrows(IllegalArgumentException.class, () -> service.redeem(projectId, "workload-1", "secret", arguments("\"text\"")));
  }

  @Test
  void revokesOnlyAnUnredeemedGrantForAProjectOwner() {
    Project project = mock(Project.class);
    when(project.getOrganizationId()).thenReturn(UUID.randomUUID());
    when(access.requireMembership(eq(projectId), eq("owner-1"), any())).thenReturn(project);
    UUID grantId = UUID.randomUUID();

    when(jdbc.update(startsWith("update runtime_ai_tool_grants set grant_status='REVOKED'"), eq(grantId), eq(projectId))).thenReturn(1);
    assertTrue(service.revoke(projectId, "owner-1", grantId));
    verify(audit).append(any(), eq(projectId), eq("owner-1"), eq("runtime_ai.tool_grant_revoked"), anyString(), eq(grantId.toString()), anyString());

    when(jdbc.update(startsWith("update runtime_ai_tool_grants set grant_status='REVOKED'"), eq(grantId), eq(projectId))).thenReturn(0);
    assertFalse(service.revoke(projectId, "owner-1", grantId));
  }

  private static String sha256Of(JsonNode arguments) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    return java.util.HexFormat.of().formatHex(digest.digest(
        RuntimeAiToolBrokerService.canonical(arguments).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
}
