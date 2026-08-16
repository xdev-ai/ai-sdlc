package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentGovernanceServiceTest {
  private ProjectAccessService access; private PromptTemplateRepository templates; private AgentSessionRepository sessions; private AgentEvidenceRepository evidence; private ValidationRunRepository validations; private EvidenceAssetRepository assets; private ApprovalRequestRepository approvals; private ApprovalDecisionRepository decisions; private ApprovalOrchestrationService approvalOrchestration; private AuditService audit; private AgentGovernanceService service;
  private UUID projectId; private String actor; private Project project;

  @BeforeEach void setup() {
    access = mock(ProjectAccessService.class); templates = mock(PromptTemplateRepository.class); sessions = mock(AgentSessionRepository.class); evidence = mock(AgentEvidenceRepository.class); validations = mock(ValidationRunRepository.class); assets = mock(EvidenceAssetRepository.class); approvals = mock(ApprovalRequestRepository.class); decisions = mock(ApprovalDecisionRepository.class); approvalOrchestration = mock(ApprovalOrchestrationService.class); audit = mock(AuditService.class);
    service = new AgentGovernanceService(access, templates, sessions, evidence, validations, assets, approvals, decisions, approvalOrchestration, audit);
    projectId = UUID.randomUUID(); actor = "developer-1"; project = new Project(UUID.randomUUID(), "agent-governance", "Agent governance", "test");
    when(access.requireMembership(eq(projectId), eq(actor), any(MembershipRole[].class))).thenReturn(project);
    when(access.requireProject(projectId)).thenReturn(project);
  }

  @Test void declarationIsIdempotentForStableSessionFingerprint() {
    String fingerprint = "a".repeat(64);
    AgentSession existing = new AgentSession(projectId, null, "coding-agent", "provider", "model", "version", fingerprint, null, 0, null, null, actor);
    setId(existing, UUID.randomUUID());
    when(sessions.findByProjectIdAndSessionFingerprint(projectId, fingerprint)).thenReturn(Optional.of(existing));

    AgentSession result = service.declareSession(projectId, actor, null, "coding-agent", "provider", "model", "version", fingerprint, null, 0, null, null);

    assertSame(existing, result);
    verify(sessions, never()).save(any());
    verify(audit, never()).append(any(), any(), any(), any(), any(), any(), any());
  }

  @Test void policyFailedGeneratedChangeCannotCreateHumanApproval() {
    AgentSession session = activeSession();
    when(sessions.lockById(session.getId())).thenReturn(Optional.of(session));

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.declareGeneratedChange(projectId, session.getId(), actor, null, null, "refs/pull/7", "b".repeat(64), AgentPolicyDecision.FAIL, "policy/security", "Review AI change", null, 1, null, Instant.now().plusSeconds(3600)));

    assertTrue(error.getMessage().contains("policy-failed"));
    verify(approvalOrchestration, never()).requestApproval(any(), any(), any(), any(), any(), any(), anyInt(), any(), any());
    verify(evidence, never()).save(any());
  }

  @Test void generatedChangeCreatesLinkedHumanApprovalBeforePersistingEvidence() {
    AgentSession session = activeSession(); String digest = "c".repeat(64); UUID approvalId = UUID.randomUUID(); UUID evidenceId = UUID.randomUUID();
    when(sessions.lockById(session.getId())).thenReturn(Optional.of(session));
    when(evidence.findByAgentSessionIdAndGeneratedChangeSha256(session.getId(), digest)).thenReturn(Optional.empty());
    when(approvalOrchestration.requestApproval(eq(projectId), eq(actor), eq("AGENT_GENERATED_CHANGE"), contains(session.getId().toString()), eq("Human review required"), any(), eq(1), isNull(), any())).thenReturn(approvalId);
    when(evidence.save(any(AgentEvidence.class))).thenAnswer(invocation -> { AgentEvidence saved = invocation.getArgument(0); setId(saved, evidenceId); return saved; });

    AgentEvidence result = service.declareGeneratedChange(projectId, session.getId(), actor, null, null, "refs/pull/8", digest, AgentPolicyDecision.PASS, "policy/review", "Human review required", "Human review remains mandatory", 1, null, Instant.now().plusSeconds(3600));

    assertEquals(evidenceId, result.getId()); assertEquals(approvalId, result.getApprovalRequestId()); assertEquals(AgentPolicyDecision.PASS, result.getPolicyDecision());
    ArgumentCaptor<AgentEvidence> evidenceCaptor = ArgumentCaptor.forClass(AgentEvidence.class); verify(evidence).save(evidenceCaptor.capture()); assertEquals(approvalId, evidenceCaptor.getValue().getApprovalRequestId());
    verify(audit).append(eq(project.getOrganizationId()), eq(projectId), eq(actor), eq("AGENT_GENERATED_CHANGE_DECLARED"), eq("agent_evidence"), eq(evidenceId.toString()), contains("\"approvalRequestId\":\"" + approvalId));
  }

  private AgentSession activeSession() { AgentSession session = new AgentSession(projectId, null, "coding-agent", "provider", "model", "version", "d".repeat(64), null, 1, "e".repeat(64), "governed change", actor); setId(session, UUID.randomUUID()); return session; }
  private static void setId(Object target, UUID id) { try { Field field = target.getClass().getDeclaredField("id"); field.setAccessible(true); field.set(target, id); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
}
