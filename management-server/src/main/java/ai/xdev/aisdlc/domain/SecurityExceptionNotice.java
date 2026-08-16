package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_exception_notices")
public class SecurityExceptionNotice {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "source_reference", nullable = false, length = 300) private String sourceReference;
  @Column(nullable = false, columnDefinition = "text") private String justification;
  @Enumerated(EnumType.STRING) @Column(name = "exception_status", nullable = false, length = 30) private DomainTypes.SecurityExceptionNoticeStatus exceptionStatus = DomainTypes.SecurityExceptionNoticeStatus.ACTIVE;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "last_reminder_at") private Instant lastReminderAt;
  @Column(name = "expired_at") private Instant expiredAt;
  @Column(name = "created_by", nullable = false, length = 200) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  protected SecurityExceptionNotice() {}
  public SecurityExceptionNotice(UUID projectId, String sourceReference, String justification, Instant expiresAt, String createdBy) { this.projectId = projectId; this.sourceReference = sourceReference; this.justification = justification; this.expiresAt = expiresAt; this.createdBy = createdBy; }
  public void remind(Instant now) { lastReminderAt = now; updatedAt = now; }
  public void expire(Instant now) { if (exceptionStatus == DomainTypes.SecurityExceptionNoticeStatus.ACTIVE) { exceptionStatus = DomainTypes.SecurityExceptionNoticeStatus.EXPIRED; expiredAt = now; updatedAt = now; } }
  public void resolve() { exceptionStatus = DomainTypes.SecurityExceptionNoticeStatus.RESOLVED; updatedAt = Instant.now(); }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public String getSourceReference() { return sourceReference; } public String getJustification() { return justification; } public DomainTypes.SecurityExceptionNoticeStatus getExceptionStatus() { return exceptionStatus; } public Instant getExpiresAt() { return expiresAt; } public Instant getLastReminderAt() { return lastReminderAt; } public Instant getExpiredAt() { return expiredAt; } public String getCreatedBy() { return createdBy; } public Instant getCreatedAt() { return createdAt; }
}
