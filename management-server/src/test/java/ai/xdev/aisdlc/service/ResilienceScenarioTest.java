package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.xdev.aisdlc.config.SecurityConfig;
import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAccessLevel;
import ai.xdev.aisdlc.domain.DomainTypes.EvidenceAssetType;
import ai.xdev.aisdlc.domain.DomainTypes.NotificationChannelType;
import ai.xdev.aisdlc.domain.DomainTypes.NotificationDeliveryStatus;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.NotificationChannel;
import ai.xdev.aisdlc.domain.NotificationDelivery;
import ai.xdev.aisdlc.domain.Project;
import ai.xdev.aisdlc.evidence.EvidenceStorageProperties;
import ai.xdev.aisdlc.evidence.ObjectStoragePort;
import ai.xdev.aisdlc.repo.Repositories.EvidenceAssetRepository;
import ai.xdev.aisdlc.repo.Repositories.NotificationChannelRepository;
import ai.xdev.aisdlc.repo.Repositories.NotificationDeliveryReceiptRepository;
import ai.xdev.aisdlc.repo.Repositories.NotificationDeliveryRepository;
import ai.xdev.aisdlc.repo.Repositories.ScmEventRepository;
import ai.xdev.aisdlc.repo.Repositories.ScmRepositoryLinkRepository;
import ai.xdev.aisdlc.repo.Repositories.ValidationEvidenceRepository;
import ai.xdev.aisdlc.repo.Repositories.ValidationRunRepository;
import ai.xdev.aisdlc.telemetry.TelemetryProperties;
import ai.xdev.aisdlc.telemetry.TraceContextFilter;
import ai.xdev.aisdlc.telemetry.TraceContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Deterministic resilience scenarios for the P3.1 fault matrix.
 *
 * <p>Each case asserts one half of the platform's degradation model: telemetry fails open and never blocks a valid
 * request, while governance policy, approval delivery, evidence, identity, and runtime AI paths fail closed. The
 * scenarios map to the plan in {@code docs/p3-1-resilience-chaos-test-plan.md} and use the isolated
 * {@link ChaosFaultRegistry} seam, which has no bean outside the explicit {@code chaos} profile.
 */
class ResilienceScenarioTest {
  private final ChaosFaultRegistry registry = new ChaosFaultRegistry();
  private final ObjectProvider<ChaosFaultRegistry> faults = new ObjectProvider<>() {
    @Override public ChaosFaultRegistry getObject() { return registry; }
  };

  @AfterEach
  void removeFaults() {
    registry.clear();
    TraceContextHolder.clear();
  }

  private void inject(ChaosFaultRegistry.Component component) {
    registry.enable(component, ChaosFaultRegistry.Mode.UNAVAILABLE);
  }

  // RES-OTEL-01: telemetry loss must not interrupt a valid request.
  @Test
  void telemetryFailureDegradesObservabilityWithoutBlockingTheRequest() throws Exception {
    TelemetryProperties failing = mock(TelemetryProperties.class);
    when(failing.isAcceptRemoteTraceContext()).thenThrow(new IllegalStateException("telemetry subsystem unavailable"));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    assertDoesNotThrow(() -> new TraceContextFilter(failing).doFilter(request, response, chain));

    assertNotNull(chain.getRequest(), "the downstream chain must still run");
    assertNull(request.getAttribute(TraceContextFilter.REQUEST_ATTRIBUTE));
    assertTrue(TraceContextHolder.current().isEmpty());
    assertEquals(200, response.getStatus());
  }

  @Test
  void telemetryStaysAvailableForNormalRequestsAfterADegradedOne() throws Exception {
    TelemetryProperties failing = mock(TelemetryProperties.class);
    when(failing.isAcceptRemoteTraceContext()).thenThrow(new IllegalStateException("telemetry subsystem unavailable"));
    new TraceContextFilter(failing).doFilter(new MockHttpServletRequest("GET", "/api/v1/projects"), new MockHttpServletResponse(), new MockFilterChain());

    MockHttpServletRequest recovered = new MockHttpServletRequest("GET", "/api/v1/projects");
    new TraceContextFilter(new TelemetryProperties()).doFilter(recovered, new MockHttpServletResponse(), new MockFilterChain());
    assertNotNull(recovered.getAttribute(TraceContextFilter.REQUEST_ATTRIBUTE));
  }

  // RES-POL-05: a policy-engine outage denies the governed action; it never becomes an implicit pass.
  @Test
  void policyEngineOutageBlocksTheGovernedActionInsteadOfPassing() {
    PolicyExpressionEngine engine = new PolicyExpressionEngine(faults);
    assertDoesNotThrow(() -> engine.evaluate("true", java.util.Map.of()));
    inject(ChaosFaultRegistry.Component.POLICY_ENGINE);
    var failure = assertThrows(ChaosFaultRegistry.ChaosFaultException.class, () -> engine.evaluate("true", java.util.Map.of()));
    assertTrue(failure.getMessage().contains("POLICY_ENGINE"));
    registry.clear();
    assertEquals(Boolean.TRUE, assertDoesNotThrow(() -> engine.evaluate("true", java.util.Map.of())));
  }

  // RES-EVID-04: an evidence write that cannot be verified must not produce a metadata record or an audit claim.
  @Test
  void evidenceStorageOutageFailsClosedBeforeAnyMetadataOrAuditClaim() {
    var access = mock(ProjectAccessService.class);
    var assets = mock(EvidenceAssetRepository.class);
    var storage = mock(ObjectStoragePort.class);
    var audit = mock(AuditService.class);
    UUID projectId = UUID.randomUUID();
    Project project = mock(Project.class);
    when(project.getOrganizationId()).thenReturn(UUID.randomUUID());
    when(access.requireMembership(eq(projectId), anyString(), any(), any(), any())).thenReturn(project);
    when(assets.findByProjectIdAndIdempotencyKey(eq(projectId), anyString())).thenReturn(Optional.empty());
    var service = new EvidenceRepositoryService(access, assets, mock(ValidationEvidenceRepository.class),
        mock(ValidationRunRepository.class), storage, new EvidenceStorageProperties(), audit, faults);

    inject(ChaosFaultRegistry.Component.EVIDENCE_STORAGE);
    assertThrows(ChaosFaultRegistry.ChaosFaultException.class, () -> service.upload(projectId, "developer-1", "evidence-idem-0001",
        EvidenceAssetType.VALIDATION, EvidenceAccessLevel.PROJECT, null, "report.json", "application/json",
        "{}".getBytes(StandardCharsets.UTF_8), null));

    verifyNoInteractions(storage);
    verify(assets, never()).save(any());
    verifyNoInteractions(audit);
  }

  // RES-IDP-06: losing the identity dependency rejects new authorization and never falls back to another principal.
  @Test
  void identityDependencyOutageRejectsNewAuthorizationWithoutFallback() {
    JwtDecoder delegate = mock(JwtDecoder.class);
    Jwt token = Jwt.withTokenValue("token").header("alg", "none").subject("subject-1")
        .issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(60)).build();
    when(delegate.decode("token")).thenReturn(token);
    JwtDecoder decoder = SecurityConfig.chaosAwareDecoder(delegate, faults);
    assertEquals(token, decoder.decode("token"));

    inject(ChaosFaultRegistry.Component.AUTHENTICATION);
    var failure = assertThrows(JwtException.class, () -> decoder.decode("token"));
    assertFalse(failure.getMessage().contains("subject-1"));
    verify(delegate).decode("token");

    registry.clear();
    assertEquals(token, decoder.decode("token"));
  }

  // RES-SCM-07: an interrupted ingest commits nothing, and the retry is de-duplicated by the delivery identifier.
  @Test
  void scmIngressOutageCommitsNothingAndTheRetryIsIdempotent() throws Exception {
    var events = mock(ScmEventRepository.class);
    var links = mock(ScmRepositoryLinkRepository.class);
    var link = mock(ai.xdev.aisdlc.domain.ScmRepositoryLink.class);
    when(link.getProjectId()).thenReturn(UUID.randomUUID());
    when(link.getId()).thenReturn(UUID.randomUUID());
    when(links.findByProviderAndRepositoryFullName(eq(ScmProvider.GITHUB), eq("xdev-ai/ai-sdlc"))).thenReturn(Optional.of(link));
    when(events.findByProviderAndDeliveryId(eq(ScmProvider.GITHUB), eq("delivery-1"))).thenReturn(Optional.empty());
    var service = new ScmIntegrationService(mock(ProjectAccessService.class), links, events, mock(ValidationRunRepository.class),
        mock(AuditService.class), mock(GitHubPolicyGateService.class), new ObjectMapper(), faults);
    byte[] payload = "{\"repository\":{\"full_name\":\"xdev-ai/ai-sdlc\"},\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    inject(ChaosFaultRegistry.Component.SCM_INGRESS);
    assertThrows(ChaosFaultRegistry.ChaosFaultException.class, () -> service.ingestGitHub("delivery-1", "pull_request", payload));
    verify(events, never()).save(any());

    registry.clear();
    var prior = mock(ai.xdev.aisdlc.domain.ScmEvent.class);
    UUID priorId = UUID.randomUUID();
    when(prior.getId()).thenReturn(priorId);
    when(events.findByProviderAndDeliveryId(eq(ScmProvider.GITHUB), eq("delivery-1"))).thenReturn(Optional.of(prior));
    var replay = service.ingestGitHub("delivery-1", "pull_request", payload);
    assertTrue(replay.duplicate());
    assertEquals(priorId, replay.eventId());
    verify(events, never()).save(any());
  }

  // RES-NOTIFY-08: a provider outage keeps the delivery retryable and does not change the approval outcome.
  @Test
  void notificationProviderOutageSchedulesARetryRatherThanFailingTheDelivery() {
    var deliveries = mock(NotificationDeliveryRepository.class);
    var channels = mock(NotificationChannelRepository.class);
    var receipts = mock(NotificationDeliveryReceiptRepository.class);
    var cipher = mock(NotificationSecretCipher.class);
    var transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

    UUID projectId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    var channel = new NotificationChannel(projectId, NotificationChannelType.GENERIC_WEBHOOK, "ops", "cipher-destination", "cipher-secret", "owner-1");
    // The identifier is assigned on persist, so the spy supplies it while the real state transitions still run.
    var delivery = org.mockito.Mockito.spy(new NotificationDelivery(projectId, UUID.randomUUID(), "approval.requested", "subject", "body", "idem-0001", "e".repeat(64), "f".repeat(64)));
    when(delivery.getId()).thenReturn(deliveryId);
    when(deliveries.findEligibleIds(any(), any(), any(), any())).thenReturn(java.util.List.of(deliveryId));
    when(deliveries.lockById(deliveryId)).thenReturn(Optional.of(delivery));
    when(channels.findById(delivery.getChannelId())).thenReturn(Optional.of(channel));
    when(cipher.decrypt("cipher-destination")).thenReturn("https://hooks.example/ops");
    when(cipher.decrypt("cipher-secret")).thenReturn("shared-secret");
    var service = new NotificationService(mock(ProjectAccessService.class), channels, deliveries, receipts,
        mock(AuditService.class), cipher, new ai.xdev.aisdlc.config.NotificationProperties(), new ObjectMapper(),
        mock(ObjectProvider.class), transactionManager, faults);

    inject(ChaosFaultRegistry.Component.NOTIFICATION_PROVIDER);
    assertEquals(1, service.dispatchEligible());

    var receipt = org.mockito.ArgumentCaptor.forClass(ai.xdev.aisdlc.domain.NotificationDeliveryReceipt.class);
    verify(receipts).save(receipt.capture());
    assertEquals("RETRY_SCHEDULED", receipt.getValue().getOutcome());
    assertEquals("NETWORK_ERROR", receipt.getValue().getErrorCode());
    assertEquals(NotificationDeliveryStatus.RETRY_SCHEDULED, delivery.getDeliveryStatus());
  }

  // RES-AI-12: a provider timeout is a failed dispatch, never an authorization success or a silent reroute.
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void runtimeProviderOutageFailsClosedWithoutCallingTheTransport() throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var broker = mock(RuntimeAiBrokerService.class);
    var transport = mock(ProviderHttpTransport.class);
    var access = mock(ProjectAccessService.class);
    Project project = mock(Project.class);
    when(project.getOrganizationId()).thenReturn(UUID.randomUUID());
    when(access.requireProject(projectId)).thenReturn(project);
    when(broker.preflight(eq(projectId), eq("workload-1"), any(), eq("provider-a"), eq("model-a"), anyString(), any(), eq(false)))
        .thenReturn(new RuntimeAiBrokerService.AuthorizationView("ALLOW", "POLICY_PASS", decisionId, UUID.randomUUID()));
    when(jdbc.queryForObject(startsWith("select id,endpoint_uri"), any(org.springframework.jdbc.core.RowMapper.class), eq(projectId), eq("provider-a"), eq("model-a")))
        .thenAnswer(invocation -> {
          var mapper = (org.springframework.jdbc.core.RowMapper<Object>) invocation.getArgument(1);
          var resultSet = mock(java.sql.ResultSet.class);
          when(resultSet.getObject(1, UUID.class)).thenReturn(profileId);
          when(resultSet.getString(2)).thenReturn("https://provider.example/v1/infer");
          when(resultSet.getString(3)).thenReturn("mount:provider-a");
          when(resultSet.getBoolean(4)).thenReturn(false);
          when(resultSet.getString(5)).thenReturn(null);
          when(resultSet.getInt(6)).thenReturn(2_000);
          when(resultSet.getInt(7)).thenReturn(2);
          return mapper.mapRow(resultSet, 1);
        });
    when(jdbc.update(startsWith("insert into runtime_ai_provider_dispatches"), any(Object[].class))).thenReturn(1);
    var credentials = mock(ProviderCredentialResolver.class);
    when(credentials.resolve("mount:provider-a", null, false)).thenReturn(new ProviderCredentialResolver.CredentialMaterial("Bearer transient", null));
    var service = new RuntimeAiProviderProxyService(jdbc, broker, access, mock(AuditService.class), credentials, transport, faults);

    inject(ChaosFaultRegistry.Component.RUNTIME_AI_PROVIDER);
    var result = service.invoke(projectId, "workload-1", new RuntimeAiProviderProxyService.InvocationRequest(
        UUID.randomUUID(), "provider-a", "model-a", "d".repeat(64), new ObjectMapper().readTree("{}"),
        UUID.randomUUID(), new ObjectMapper().readTree("{\"input\":\"opaque\"}")));

    assertEquals("FAILED", result.outcome());
    assertEquals("PROVIDER_TIMEOUT", result.reasonCode());
    assertNull(result.responseBody());
    verifyNoInteractions(transport);
  }

  @Test
  void aFaultTargetsOnlyItsDeclaredComponentAndClearingItRestoresService() {
    inject(ChaosFaultRegistry.Component.POLICY_ENGINE);
    for (ChaosFaultRegistry.Component component : ChaosFaultRegistry.Component.values()) {
      if (component == ChaosFaultRegistry.Component.POLICY_ENGINE) {
        assertThrows(ChaosFaultRegistry.ChaosFaultException.class, () -> registry.check(component));
      } else {
        assertDoesNotThrow(() -> registry.check(component));
      }
    }
    registry.clear();
    for (ChaosFaultRegistry.Component component : ChaosFaultRegistry.Component.values()) {
      assertDoesNotThrow(() -> registry.check(component));
    }
  }
}
