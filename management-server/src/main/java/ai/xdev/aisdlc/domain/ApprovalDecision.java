package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approval_decisions")
public class ApprovalDecision {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "approval_request_id", nullable = false) private UUID approvalRequestId;
  @Column(nullable = false, length = 200) private String actor;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DomainTypes.ApprovalDecisionType decision;
  @Column(columnDefinition = "text") private String comment;
  @Column(name = "decided_at", nullable = false) private Instant decidedAt = Instant.now();
  protected ApprovalDecision() {}
  public ApprovalDecision(UUID approvalRequestId, String actor, DomainTypes.ApprovalDecisionType decision, String comment) { this.approvalRequestId = approvalRequestId; this.actor = actor; this.decision = decision; this.comment = comment; }
  public UUID getId() { return id; } public UUID getApprovalRequestId() { return approvalRequestId; } public String getActor() { return actor; } public DomainTypes.ApprovalDecisionType getDecision() { return decision; } public String getComment() { return comment; } public Instant getDecidedAt() { return decidedAt; }
}
