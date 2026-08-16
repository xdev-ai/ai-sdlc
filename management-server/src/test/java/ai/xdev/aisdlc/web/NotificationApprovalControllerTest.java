package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.DomainTypes.ApprovalDecisionType;
import ai.xdev.aisdlc.domain.DomainTypes.NotificationChannelType;
import ai.xdev.aisdlc.service.ApprovalOrchestrationService;
import ai.xdev.aisdlc.service.NotificationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class NotificationApprovalControllerTest {
  @Test
  void createsEncryptedNotificationChannelForAuthenticatedSubject() {
    NotificationService notifications = mock(NotificationService.class); ApprovalOrchestrationService approvals = mock(ApprovalOrchestrationService.class); NotificationApprovalController controller = new NotificationApprovalController(notifications, approvals);
    UUID projectId = UUID.randomUUID(); UUID channelId = UUID.randomUUID(); when(notifications.createChannel(projectId, "owner-1", NotificationChannelType.GENERIC_WEBHOOK, "Release sink", "https://events.example.test/governance", "shared-secret")).thenReturn(channelId);

    NotificationApprovalController.Created created = controller.createChannel(projectId, new NotificationApprovalController.NotificationChannelInput(NotificationChannelType.GENERIC_WEBHOOK, "Release sink", "https://events.example.test/governance", "shared-secret"), jwt("owner-1"));

    assertEquals(channelId, created.id());
    verify(notifications).createChannel(projectId, "owner-1", NotificationChannelType.GENERIC_WEBHOOK, "Release sink", "https://events.example.test/governance", "shared-secret");
  }

  @Test
  void forwardsDecisionAndDelegationToApprovalOrchestrator() {
    NotificationService notifications = mock(NotificationService.class); ApprovalOrchestrationService approvals = mock(ApprovalOrchestrationService.class); NotificationApprovalController controller = new NotificationApprovalController(notifications, approvals);
    UUID approvalId = UUID.randomUUID();

    controller.decide(approvalId, new NotificationApprovalController.DecisionInput(ApprovalDecisionType.APPROVE, "Evidence reviewed"), jwt("reviewer-1"));
    controller.delegate(approvalId, new NotificationApprovalController.DelegateInput("delegate-1"), jwt("owner-1"));

    verify(approvals).decide(approvalId, "reviewer-1", ApprovalDecisionType.APPROVE, "Evidence reviewed");
    verify(approvals).delegate(approvalId, "owner-1", "delegate-1");
  }

  @Test
  void createsFutureDatedApprovalUsingRequestPayloadWithoutReplacingHumanActor() {
    NotificationService notifications = mock(NotificationService.class); ApprovalOrchestrationService approvals = mock(ApprovalOrchestrationService.class); NotificationApprovalController controller = new NotificationApprovalController(notifications, approvals);
    UUID projectId = UUID.randomUUID(); UUID approvalId = UUID.randomUUID(); Instant dueAt = Instant.now().plusSeconds(3600);
    when(approvals.requestApproval(eq(projectId), eq("developer-1"), eq("release"), eq("release-72"), eq("Release approval"), eq("Evidence linked"), eq(2), eq("reviewer-1"), eq(dueAt))).thenReturn(approvalId);

    NotificationApprovalController.Created created = controller.createApproval(projectId, new NotificationApprovalController.ApprovalInput("release", "release-72", "Release approval", "Evidence linked", 2, "reviewer-1", dueAt), jwt("developer-1"));

    assertEquals(approvalId, created.id());
    verify(approvals).requestApproval(projectId, "developer-1", "release", "release-72", "Release approval", "Evidence linked", 2, "reviewer-1", dueAt);
    verifyNoInteractions(notifications);
  }

  private Jwt jwt(String subject) { return Jwt.withTokenValue("token").header("alg", "none").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build(); }
}
