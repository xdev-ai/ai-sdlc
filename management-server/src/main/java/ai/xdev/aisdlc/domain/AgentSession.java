package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_sessions", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "session_fingerprint"}))
public class AgentSession {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "prompt_template_id") private UUID promptTemplateId;
  @Column(name = "agent_identity", nullable = false, length = 240) private String agentIdentity;
  @Column(nullable = false, length = 160) private String provider;
  @Column(name = "model_name", nullable = false, length = 240) private String modelName;
  @Column(name = "model_version", nullable = false, length = 240) private String modelVersion;
  @Column(name = "session_fingerprint", nullable = false, length = 64) private String sessionFingerprint;
  @Column(name = "context_sha256", length = 64) private String contextSha256;
  @Column(name = "tool_invocation_count", nullable = false) private int toolInvocationCount;
  @Column(name = "tool_invocation_sha256", length = 64) private String toolInvocationSha256;
  @Column(length = 2000) private String purpose;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DomainTypes.AgentSessionStatus status = DomainTypes.AgentSessionStatus.DECLARED;
  @Column(name = "declared_by", nullable = false, length = 200) private String declaredBy;
  @Column(name = "declared_at", nullable = false) private Instant declaredAt = Instant.now();
  @Column(name = "completed_at") private Instant completedAt;
  protected AgentSession() {}
  public AgentSession(UUID projectId, UUID promptTemplateId, String agentIdentity, String provider, String modelName, String modelVersion, String sessionFingerprint, String contextSha256, int toolInvocationCount, String toolInvocationSha256, String purpose, String declaredBy) { this.projectId = projectId; this.promptTemplateId = promptTemplateId; this.agentIdentity = agentIdentity; this.provider = provider; this.modelName = modelName; this.modelVersion = modelVersion; this.sessionFingerprint = sessionFingerprint; this.contextSha256 = contextSha256; this.toolInvocationCount = toolInvocationCount; this.toolInvocationSha256 = toolInvocationSha256; this.purpose = purpose; this.declaredBy = declaredBy; }
  public void complete(Instant at) { if (status != DomainTypes.AgentSessionStatus.DECLARED) throw new IllegalStateException("Agent session is not active"); status = DomainTypes.AgentSessionStatus.COMPLETED; completedAt = at; }
  public void block(Instant at) { if (status == DomainTypes.AgentSessionStatus.COMPLETED) throw new IllegalStateException("Completed agent session cannot be blocked"); status = DomainTypes.AgentSessionStatus.BLOCKED; completedAt = at; }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public UUID getPromptTemplateId() { return promptTemplateId; } public String getAgentIdentity() { return agentIdentity; } public String getProvider() { return provider; } public String getModelName() { return modelName; } public String getModelVersion() { return modelVersion; } public String getSessionFingerprint() { return sessionFingerprint; } public String getContextSha256() { return contextSha256; } public int getToolInvocationCount() { return toolInvocationCount; } public String getToolInvocationSha256() { return toolInvocationSha256; } public String getPurpose() { return purpose; } public DomainTypes.AgentSessionStatus getStatus() { return status; } public String getDeclaredBy() { return declaredBy; } public Instant getDeclaredAt() { return declaredAt; } public Instant getCompletedAt() { return completedAt; }
}
