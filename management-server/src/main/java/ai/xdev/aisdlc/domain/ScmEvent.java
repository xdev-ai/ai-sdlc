package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scm_events", uniqueConstraints = @UniqueConstraint(name = "scm_event_provider_delivery_uq", columnNames = {"provider", "delivery_id"}))
public class ScmEvent {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "repository_link_id", nullable = false) private UUID repositoryLinkId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private DomainTypes.ScmProvider provider;
  @Column(name = "delivery_id", nullable = false, length = 120) private String deliveryId;
  @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 40) private DomainTypes.ScmEventType eventType;
  @Column(length = 120) private String action;
  @Column(name = "repository_full_name", nullable = false, length = 300) private String repositoryFullName;
  @Column(name = "installation_id") private Long installationId;
  @Column(length = 500) private String ref;
  @Column(name = "commit_sha", length = 80) private String commitSha;
  @Column(name = "pull_request_number") private Integer pullRequestNumber;
  @Column(name = "workflow_run_id") private Long workflowRunId;
  @Column(name = "release_tag", length = 300) private String releaseTag;
  @Column(name = "validation_run_id") private UUID validationRunId;
  @Column(name = "policy_check_run_id") private Long policyCheckRunId;
  @Column(name = "payload_sha256", nullable = false, length = 64) private String payloadSha256;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) private String payload;
  @Enumerated(EnumType.STRING) @Column(name = "processing_status", nullable = false, length = 20) private DomainTypes.ScmProcessingStatus processingStatus = DomainTypes.ScmProcessingStatus.RECEIVED;
  @Column(name = "failure_reason", columnDefinition = "text") private String failureReason;
  @Column(name = "received_at", nullable = false) private Instant receivedAt = Instant.now();
  @Column(name = "processed_at") private Instant processedAt;

  protected ScmEvent() {}
  public ScmEvent(UUID projectId, UUID repositoryLinkId, DomainTypes.ScmProvider provider, String deliveryId, DomainTypes.ScmEventType eventType, String action, String repositoryFullName, Long installationId, String ref, String commitSha, Integer pullRequestNumber, Long workflowRunId, String releaseTag, String payloadSha256, String payload) {
    this.projectId = projectId;
    this.repositoryLinkId = repositoryLinkId;
    this.provider = provider;
    this.deliveryId = deliveryId;
    this.eventType = eventType;
    this.action = action;
    this.repositoryFullName = repositoryFullName;
    this.installationId = installationId;
    this.ref = ref;
    this.commitSha = commitSha;
    this.pullRequestNumber = pullRequestNumber;
    this.workflowRunId = workflowRunId;
    this.releaseTag = releaseTag;
    this.payloadSha256 = payloadSha256;
    this.payload = payload;
  }
  public void markProcessed() { processingStatus = DomainTypes.ScmProcessingStatus.PROCESSED; processedAt = Instant.now(); failureReason = null; }
  public void markIgnored(String reason) { processingStatus = DomainTypes.ScmProcessingStatus.IGNORED; processedAt = Instant.now(); failureReason = reason; }
  public void markFailed(String reason) { processingStatus = DomainTypes.ScmProcessingStatus.FAILED; processedAt = Instant.now(); failureReason = reason; }
  public void linkValidationRun(UUID validationRunId) { this.validationRunId = validationRunId; }
  public void recordPolicyCheckRun(long checkRunId) { this.policyCheckRunId = checkRunId; }
  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public UUID getRepositoryLinkId() { return repositoryLinkId; }
  public DomainTypes.ScmProvider getProvider() { return provider; }
  public String getDeliveryId() { return deliveryId; }
  public DomainTypes.ScmEventType getEventType() { return eventType; }
  public String getAction() { return action; }
  public String getRepositoryFullName() { return repositoryFullName; }
  public Long getInstallationId() { return installationId; }
  public String getRef() { return ref; }
  public String getCommitSha() { return commitSha; }
  public Integer getPullRequestNumber() { return pullRequestNumber; }
  public Long getWorkflowRunId() { return workflowRunId; }
  public String getReleaseTag() { return releaseTag; }
  public UUID getValidationRunId() { return validationRunId; }
  public Long getPolicyCheckRunId() { return policyCheckRunId; }
  public String getPayloadSha256() { return payloadSha256; }
  public DomainTypes.ScmProcessingStatus getProcessingStatus() { return processingStatus; }
  public String getFailureReason() { return failureReason; }
  public Instant getReceivedAt() { return receivedAt; }
  public Instant getProcessedAt() { return processedAt; }
}
