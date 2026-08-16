package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_evidence", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_session_id", "generated_change_sha256"}))
public class AgentEvidence {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "agent_session_id", nullable = false) private UUID agentSessionId;
  @Column(name = "validation_run_id") private UUID validationRunId;
  @Column(name = "evidence_asset_id") private UUID evidenceAssetId;
  @Column(name = "approval_request_id", nullable = false) private UUID approvalRequestId;
  @Column(name = "change_reference", nullable = false, length = 2000) private String changeReference;
  @Column(name = "generated_change_sha256", nullable = false, length = 64) private String generatedChangeSha256;
  @Enumerated(EnumType.STRING) @Column(name = "policy_decision", nullable = false, length = 30) private DomainTypes.AgentPolicyDecision policyDecision;
  @Column(name = "policy_reference", length = 2000) private String policyReference;
  @Column(name = "declared_by", nullable = false, length = 200) private String declaredBy;
  @Column(name = "declared_at", nullable = false) private Instant declaredAt = Instant.now();
  protected AgentEvidence() {}
  public AgentEvidence(UUID projectId, UUID agentSessionId, UUID validationRunId, UUID evidenceAssetId, UUID approvalRequestId, String changeReference, String generatedChangeSha256, DomainTypes.AgentPolicyDecision policyDecision, String policyReference, String declaredBy) { this.projectId = projectId; this.agentSessionId = agentSessionId; this.validationRunId = validationRunId; this.evidenceAssetId = evidenceAssetId; this.approvalRequestId = approvalRequestId; this.changeReference = changeReference; this.generatedChangeSha256 = generatedChangeSha256; this.policyDecision = policyDecision; this.policyReference = policyReference; this.declaredBy = declaredBy; }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public UUID getAgentSessionId() { return agentSessionId; } public UUID getValidationRunId() { return validationRunId; } public UUID getEvidenceAssetId() { return evidenceAssetId; } public UUID getApprovalRequestId() { return approvalRequestId; } public String getChangeReference() { return changeReference; } public String getGeneratedChangeSha256() { return generatedChangeSha256; } public DomainTypes.AgentPolicyDecision getPolicyDecision() { return policyDecision; } public String getPolicyReference() { return policyReference; } public String getDeclaredBy() { return declaredBy; } public Instant getDeclaredAt() { return declaredAt; }
}
