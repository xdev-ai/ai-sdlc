package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.config.NotificationProperties;
import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.web.PageResponse;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalOrchestrationService {
  private final ProjectAccessService access; private final ProjectRepository projects; private final ApprovalRequestRepository approvals; private final ApprovalDecisionRepository decisions; private final SecurityExceptionNoticeRepository exceptions; private final NotificationService notifications; private final NotificationProperties properties; private final AuditService audit;
  public ApprovalOrchestrationService(ProjectAccessService access, ProjectRepository projects, ApprovalRequestRepository approvals, ApprovalDecisionRepository decisions, SecurityExceptionNoticeRepository exceptions, NotificationService notifications, NotificationProperties properties, AuditService audit) { this.access = access; this.projects = projects; this.approvals = approvals; this.decisions = decisions; this.exceptions = exceptions; this.notifications = notifications; this.properties = properties; this.audit = audit; }
  @Transactional
  public UUID requestApproval(UUID projectId, String actor, String sourceType, String sourceId, String title, String details, int quorum, String requestedApprover, Instant dueAt) {
    Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    if (dueAt == null || !dueAt.isAfter(Instant.now()) || quorum < 1 || quorum > 50) throw new IllegalArgumentException("Approval due date must be future and quorum must be between 1 and 50");
    ApprovalRequest request = approvals.save(new ApprovalRequest(projectId, sourceType, sourceId, title, details, quorum, blankToNull(requestedApprover), actor, dueAt));
    notifications.queueProjectNotification(projectId, "approval.requested", "Approval required: " + title, "A governed approval is pending until " + dueAt + ". Request: " + request.getId(), "approval:" + request.getId() + ":requested");
    audit.append(project.getOrganizationId(), projectId, actor, "APPROVAL_REQUEST_CREATED", "approval_request", request.getId().toString(), "{\"sourceType\":\"" + sourceType + "\",\"requiredQuorum\":" + quorum + "}");
    return request.getId();
  }
  @Transactional
  public void decide(UUID approvalId, String actor, ApprovalDecisionType decision, String comment) {
    ApprovalRequest request = approvals.lockById(approvalId).orElseThrow(() -> new IllegalArgumentException("Approval request not found"));
    Project project = access.requireMembership(request.getProjectId(), actor, MembershipRole.OWNER, MembershipRole.REVIEWER);
    if (!request.isDecidable()) throw new IllegalStateException("Approval request is already decided");
    if (request.getRequestedApproverSubject() != null && !actor.equals(request.getRequestedApproverSubject()) && !actor.equals(request.getDelegatedApproverSubject()) && !actor.equals(project.getOrganizationId().toString())) throw new SecurityException("Approval is assigned to another subject");
    if (decisions.existsByApprovalRequestIdAndActor(approvalId, actor)) throw new IllegalStateException("Actor already recorded a decision");
    decisions.save(new ApprovalDecision(approvalId, actor, decision, blankToNull(comment)));
    if (decision == ApprovalDecisionType.REJECT) request.reject(Instant.now());
    else if (decisions.countByApprovalRequestIdAndDecision(approvalId, ApprovalDecisionType.APPROVE) >= request.getRequiredQuorum()) request.approve(Instant.now());
    audit.append(project.getOrganizationId(), request.getProjectId(), actor, "APPROVAL_DECISION_RECORDED", "approval_request", approvalId.toString(), "{\"decision\":\"" + decision + "\"}");
    if (request.getApprovalStatus() == ApprovalStatus.APPROVED || request.getApprovalStatus() == ApprovalStatus.REJECTED) notifications.queueProjectNotification(request.getProjectId(), "approval.decided", "Approval " + request.getApprovalStatus() + ": " + request.getTitle(), "Governed approval " + approvalId + " was " + request.getApprovalStatus() + ".", "approval:" + approvalId + ":" + request.getApprovalStatus());
  }
  @Transactional
  public void delegate(UUID approvalId, String actor, String delegateSubject) {
    ApprovalRequest request = approvals.lockById(approvalId).orElseThrow(() -> new IllegalArgumentException("Approval request not found"));
    Project project = access.requireMembership(request.getProjectId(), actor, MembershipRole.OWNER, MembershipRole.REVIEWER);
    if (!request.isDecidable()) throw new IllegalStateException("Approval request is already decided");
    request.delegate(delegateSubject, actor);
    notifications.queueProjectNotification(request.getProjectId(), "approval.delegated", "Approval delegated: " + request.getTitle(), "Approval " + approvalId + " was delegated to " + delegateSubject + ".", "approval:" + approvalId + ":delegated:" + delegateSubject);
    audit.append(project.getOrganizationId(), request.getProjectId(), actor, "APPROVAL_DELEGATED", "approval_request", approvalId.toString(), "{\"delegate\":\"" + delegateSubject + "\"}");
  }
  @Transactional(readOnly = true)
  public PageResponse<Map<String, Object>> list(UUID projectId, String actor, int page, int size) { access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); return PageResponse.from(approvals.findByProjectIdOrderByDueAtAsc(projectId, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)))).map(this::approvalView)); }
  @Transactional
  public UUID createSecurityException(UUID projectId, String actor, String sourceReference, String justification, Instant expiresAt) { Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER); if (expiresAt == null || !expiresAt.isAfter(Instant.now())) throw new IllegalArgumentException("Security exception expiry must be in the future"); SecurityExceptionNotice exception = exceptions.save(new SecurityExceptionNotice(projectId, sourceReference, justification, expiresAt, actor)); audit.append(project.getOrganizationId(), projectId, actor, "SECURITY_EXCEPTION_RECORDED", "security_exception", exception.getId().toString(), "{\"sourceReference\":\"" + sourceReference + "\"}"); return exception.getId(); }
  @Transactional(readOnly = true)
  public PageResponse<Map<String, Object>> listSecurityExceptions(UUID projectId, String actor, int page, int size) { access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER); return PageResponse.from(exceptions.findByProjectIdOrderByExpiresAtAsc(projectId, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)))).map(this::exceptionView)); }
  @Transactional
  public void processSla() {
    Instant now = Instant.now(); Set<ApprovalStatus> open = Set.of(ApprovalStatus.PENDING, ApprovalStatus.ESCALATED);
    for (ApprovalRequest request : approvals.findDueByStatus(open, now.plus(properties.getReminderLeadTime()), PageRequest.of(0, 100))) {
      if (request.getDueAt().isBefore(now)) { request.escalate(now); notifications.queueProjectNotification(request.getProjectId(), "approval.escalated", "Approval overdue: " + request.getTitle(), "Approval " + request.getId() + " exceeded its SLA at " + request.getDueAt() + ".", "approval:" + request.getId() + ":escalated"); }
      else if (request.getLastReminderAt() == null || request.getLastReminderAt().plus(properties.getReminderInterval()).isBefore(now)) { request.remind(now); notifications.queueProjectNotification(request.getProjectId(), "approval.reminder", "Approval reminder: " + request.getTitle(), "Approval " + request.getId() + " is due at " + request.getDueAt() + ".", "approval:" + request.getId() + ":reminder:" + now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)); }
    }
    for (SecurityExceptionNotice exception : exceptions.findExpiring(SecurityExceptionNoticeStatus.ACTIVE, now.plus(properties.getReminderLeadTime()), PageRequest.of(0, 100))) { if (exception.getExpiresAt().isBefore(now)) { exception.expire(now); notifications.queueProjectNotification(exception.getProjectId(), "security.exception.expired", "Security exception expired", exception.getSourceReference() + " expired at " + exception.getExpiresAt() + ".", "security-exception:" + exception.getId() + ":expired"); } else if (exception.getLastReminderAt() == null || exception.getLastReminderAt().plus(properties.getReminderInterval()).isBefore(now)) { exception.remind(now); notifications.queueProjectNotification(exception.getProjectId(), "security.exception.expiring", "Security exception expiry reminder", exception.getSourceReference() + " expires at " + exception.getExpiresAt() + ".", "security-exception:" + exception.getId() + ":reminder:" + now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)); } }
  }
  private Map<String, Object> approvalView(ApprovalRequest r) { Map<String, Object> view = new LinkedHashMap<>(); view.put("id", r.getId()); view.put("sourceType", r.getSourceType()); view.put("sourceId", r.getSourceId()); view.put("title", r.getTitle()); view.put("details", r.getDetails()); view.put("status", r.getApprovalStatus()); view.put("requiredQuorum", r.getRequiredQuorum()); view.put("requestedApprover", r.getRequestedApproverSubject()); view.put("delegatedApprover", r.getDelegatedApproverSubject()); view.put("dueAt", r.getDueAt()); view.put("lastReminderAt", r.getLastReminderAt()); view.put("escalatedAt", r.getEscalatedAt()); view.put("decisions", decisions.findByApprovalRequestIdOrderByDecidedAtAsc(r.getId()).stream().map(d -> Map.of("actor", d.getActor(), "decision", d.getDecision(), "comment", Optional.ofNullable(d.getComment()).orElse(""), "decidedAt", d.getDecidedAt())).toList()); return view; }
  private Map<String, Object> exceptionView(SecurityExceptionNotice e) { Map<String, Object> view = new LinkedHashMap<>(); view.put("id", e.getId()); view.put("sourceReference", e.getSourceReference()); view.put("status", e.getExceptionStatus()); view.put("expiresAt", e.getExpiresAt()); view.put("lastReminderAt", e.getLastReminderAt()); view.put("createdAt", e.getCreatedAt()); return view; }
  private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
