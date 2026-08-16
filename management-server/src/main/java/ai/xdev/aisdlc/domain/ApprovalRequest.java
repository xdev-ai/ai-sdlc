package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "source_type", nullable = false, length = 80) private String sourceType;
  @Column(name = "source_id", length = 200) private String sourceId;
  @Column(nullable = false, length = 300) private String title;
  @Column(columnDefinition = "text") private String details;
  @Enumerated(EnumType.STRING) @Column(name = "approval_status", nullable = false, length = 30) private DomainTypes.ApprovalStatus approvalStatus = DomainTypes.ApprovalStatus.PENDING;
  @Column(name = "required_quorum", nullable = false) private int requiredQuorum;
  @Column(name = "requested_approver_subject", length = 200) private String requestedApproverSubject;
  @Column(name = "delegated_approver_subject", length = 200) private String delegatedApproverSubject;
  @Column(name = "delegated_by", length = 200) private String delegatedBy;
  @Column(name = "created_by", nullable = false, length = 200) private String createdBy;
  @Column(name = "due_at", nullable = false) private Instant dueAt;
  @Column(name = "last_reminder_at") private Instant lastReminderAt;
  @Column(name = "escalated_at") private Instant escalatedAt;
  @Column(name = "decided_at") private Instant decidedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  protected ApprovalRequest() {}
  public ApprovalRequest(UUID projectId, String sourceType, String sourceId, String title, String details, int requiredQuorum, String requestedApproverSubject, String createdBy, Instant dueAt) { this.projectId = projectId; this.sourceType = sourceType; this.sourceId = sourceId; this.title = title; this.details = details; this.requiredQuorum = requiredQuorum; this.requestedApproverSubject = requestedApproverSubject; this.createdBy = createdBy; this.dueAt = dueAt; }
  public void delegate(String subject, String actor) { delegatedApproverSubject = subject; delegatedBy = actor; updatedAt = Instant.now(); }
  public void remind(Instant now) { lastReminderAt = now; updatedAt = now; }
  public void escalate(Instant now) { if (approvalStatus == DomainTypes.ApprovalStatus.PENDING) { approvalStatus = DomainTypes.ApprovalStatus.ESCALATED; escalatedAt = now; updatedAt = now; } }
  public void approve(Instant now) { approvalStatus = DomainTypes.ApprovalStatus.APPROVED; decidedAt = now; updatedAt = now; }
  public void reject(Instant now) { approvalStatus = DomainTypes.ApprovalStatus.REJECTED; decidedAt = now; updatedAt = now; }
  public boolean isDecidable() { return approvalStatus == DomainTypes.ApprovalStatus.PENDING || approvalStatus == DomainTypes.ApprovalStatus.ESCALATED; }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public String getSourceType() { return sourceType; } public String getSourceId() { return sourceId; } public String getTitle() { return title; } public String getDetails() { return details; } public DomainTypes.ApprovalStatus getApprovalStatus() { return approvalStatus; } public int getRequiredQuorum() { return requiredQuorum; } public String getRequestedApproverSubject() { return requestedApproverSubject; } public String getDelegatedApproverSubject() { return delegatedApproverSubject; } public String getDelegatedBy() { return delegatedBy; } public String getCreatedBy() { return createdBy; } public Instant getDueAt() { return dueAt; } public Instant getLastReminderAt() { return lastReminderAt; } public Instant getEscalatedAt() { return escalatedAt; } public Instant getDecidedAt() { return decidedAt; } public Instant getCreatedAt() { return createdAt; }
}
