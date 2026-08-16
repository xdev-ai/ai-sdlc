package ai.xdev.aisdlc.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GovernanceAutomationScheduler {
  private final NotificationService notifications; private final ApprovalOrchestrationService approvals;
  public GovernanceAutomationScheduler(NotificationService notifications, ApprovalOrchestrationService approvals) { this.notifications = notifications; this.approvals = approvals; }
  @Scheduled(cron = "${aisdlc.notifications.dispatch-cron}") public void dispatchNotifications() { notifications.dispatchEligible(); }
  @Scheduled(cron = "${aisdlc.notifications.approval-sla-cron}") public void processApprovalSla() { approvals.processSla(); }
}
