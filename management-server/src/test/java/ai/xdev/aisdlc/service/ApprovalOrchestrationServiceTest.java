package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.config.NotificationProperties;
import ai.xdev.aisdlc.domain.ApprovalRequest;
import ai.xdev.aisdlc.domain.DomainTypes.ApprovalDecisionType;
import ai.xdev.aisdlc.domain.DomainTypes.ApprovalStatus;
import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import ai.xdev.aisdlc.domain.Project;
import ai.xdev.aisdlc.repo.Repositories.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class ApprovalOrchestrationServiceTest {
  @Test
  void approvalBecomesApprovedOnlyWhenRequiredQuorumIsReached() throws Exception {
    Fixture fixture = fixture();
    ApprovalRequest request = request(fixture.projectId, 2, "assigned-reviewer", Instant.now().plusSeconds(3600));
    UUID approvalId = UUID.randomUUID(); setId(request, approvalId);
    when(fixture.approvals.lockById(approvalId)).thenReturn(Optional.of(request));
    when(fixture.decisions.existsByApprovalRequestIdAndActor(approvalId, "assigned-reviewer")).thenReturn(false);
    when(fixture.decisions.countByApprovalRequestIdAndDecision(approvalId, ApprovalDecisionType.APPROVE)).thenReturn(2L);

    fixture.service.decide(approvalId, "assigned-reviewer", ApprovalDecisionType.APPROVE, "Evidence is sufficient");

    assertEquals(ApprovalStatus.APPROVED, request.getApprovalStatus());
    verify(fixture.decisions).save(argThat(decision -> decision.getActor().equals("assigned-reviewer") && decision.getDecision() == ApprovalDecisionType.APPROVE));
    verify(fixture.notifications).queueProjectNotification(eq(fixture.projectId), eq("approval.decided"), contains("APPROVED"), contains(approvalId.toString()), eq("approval:" + approvalId + ":APPROVED"));
    verify(fixture.audit).append(eq(fixture.project.getOrganizationId()), eq(fixture.projectId), eq("assigned-reviewer"), eq("APPROVAL_DECISION_RECORDED"), eq("approval_request"), eq(approvalId.toString()), contains("APPROVE"));
  }

  @Test
  void assignedApprovalRejectsAReviewerWhoIsNotTheAssigneeOrDelegate() throws Exception {
    Fixture fixture = fixture();
    ApprovalRequest request = request(fixture.projectId, 1, "assigned-reviewer", Instant.now().plusSeconds(3600));
    UUID approvalId = UUID.randomUUID(); setId(request, approvalId);
    when(fixture.approvals.lockById(approvalId)).thenReturn(Optional.of(request));

    SecurityException error = assertThrows(SecurityException.class, () -> fixture.service.decide(approvalId, "other-reviewer", ApprovalDecisionType.APPROVE, "Not mine"));

    assertEquals("Approval is assigned to another subject", error.getMessage());
    verify(fixture.decisions, never()).save(any());
    verifyNoInteractions(fixture.notifications);
  }

  @Test
  void delegationCreatesAuditableNotificationForReplacementApprover() throws Exception {
    Fixture fixture = fixture();
    ApprovalRequest request = request(fixture.projectId, 1, "initial-reviewer", Instant.now().plusSeconds(3600));
    UUID approvalId = UUID.randomUUID(); setId(request, approvalId);
    when(fixture.approvals.lockById(approvalId)).thenReturn(Optional.of(request));

    fixture.service.delegate(approvalId, "owner-1", "delegate-reviewer");

    assertEquals("delegate-reviewer", request.getDelegatedApproverSubject());
    assertEquals("owner-1", request.getDelegatedBy());
    verify(fixture.notifications).queueProjectNotification(eq(fixture.projectId), eq("approval.delegated"), contains("Approval delegated"), contains("delegate-reviewer"), eq("approval:" + approvalId + ":delegated:delegate-reviewer"));
    verify(fixture.audit).append(eq(fixture.project.getOrganizationId()), eq(fixture.projectId), eq("owner-1"), eq("APPROVAL_DELEGATED"), eq("approval_request"), eq(approvalId.toString()), contains("delegate-reviewer"));
  }

  @Test
  void slaProcessorEscalatesOverdueApprovalAndQueuesOneIdempotentEventKey() throws Exception {
    Fixture fixture = fixture();
    ApprovalRequest overdue = request(fixture.projectId, 1, null, Instant.now().minusSeconds(60));
    UUID approvalId = UUID.randomUUID(); setId(overdue, approvalId);
    when(fixture.approvals.findDueByStatus(anySet(), any(Instant.class), any(Pageable.class))).thenReturn(List.of(overdue));
    when(fixture.exceptions.findExpiring(any(), any(Instant.class), any(Pageable.class))).thenReturn(List.of());

    fixture.service.processSla();

    assertEquals(ApprovalStatus.ESCALATED, overdue.getApprovalStatus());
    verify(fixture.notifications).queueProjectNotification(eq(fixture.projectId), eq("approval.escalated"), contains("overdue"), contains(approvalId.toString()), eq("approval:" + approvalId + ":escalated"));
  }

  private Fixture fixture() {
    ProjectAccessService access = mock(ProjectAccessService.class); ProjectRepository projects = mock(ProjectRepository.class); ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class); ApprovalDecisionRepository decisions = mock(ApprovalDecisionRepository.class); SecurityExceptionNoticeRepository exceptions = mock(SecurityExceptionNoticeRepository.class); NotificationService notifications = mock(NotificationService.class); AuditService audit = mock(AuditService.class);
    UUID projectId = UUID.randomUUID(); Project project = new Project(UUID.randomUUID(), "governed", "Governed", "");
    when(access.requireMembership(eq(projectId), anyString(), any(MembershipRole[].class))).thenReturn(project);
    NotificationProperties properties = new NotificationProperties();
    return new Fixture(projectId, project, approvals, decisions, exceptions, notifications, audit, new ApprovalOrchestrationService(access, projects, approvals, decisions, exceptions, notifications, properties, audit));
  }

  private ApprovalRequest request(UUID projectId, int quorum, String requestedApprover, Instant dueAt) { return new ApprovalRequest(projectId, "phase-gate", "gate-17", "Release gate", "Evidence review", quorum, requestedApprover, "owner-1", dueAt); }
  private static void setId(Object target, UUID value) throws Exception { Field field = target.getClass().getDeclaredField("id"); field.setAccessible(true); field.set(target, value); }
  private record Fixture(UUID projectId, Project project, ApprovalRequestRepository approvals, ApprovalDecisionRepository decisions, SecurityExceptionNoticeRepository exceptions, NotificationService notifications, AuditService audit, ApprovalOrchestrationService service) {}
}
