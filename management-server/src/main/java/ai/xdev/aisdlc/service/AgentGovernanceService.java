package ai.xdev.aisdlc.service;

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
public class AgentGovernanceService {
  private static final String SHA256 = "^[a-f0-9]{64}$";
  private static final String TEMPLATE_KEY = "^[a-z0-9._-]{3,160}$";
  private static final String SEMVER = "^[0-9]+\\.[0-9]+\\.[0-9]+([-.+][0-9A-Za-z.-]+)?$";
  private final ProjectAccessService access; private final PromptTemplateRepository templates; private final AgentSessionRepository sessions; private final AgentEvidenceRepository evidence; private final ValidationRunRepository validations; private final EvidenceAssetRepository assets; private final ApprovalRequestRepository approvals; private final ApprovalDecisionRepository decisions; private final ApprovalOrchestrationService approvalOrchestration; private final AuditService audit;
  public AgentGovernanceService(ProjectAccessService access, PromptTemplateRepository templates, AgentSessionRepository sessions, AgentEvidenceRepository evidence, ValidationRunRepository validations, EvidenceAssetRepository assets, ApprovalRequestRepository approvals, ApprovalDecisionRepository decisions, ApprovalOrchestrationService approvalOrchestration, AuditService audit) { this.access = access; this.templates = templates; this.sessions = sessions; this.evidence = evidence; this.validations = validations; this.assets = assets; this.approvals = approvals; this.decisions = decisions; this.approvalOrchestration = approvalOrchestration; this.audit = audit; }

  @Transactional
  public PromptTemplate registerPromptTemplate(UUID projectId, String actor, String templateKey, String semanticVersion, String displayName, String sourceReference, String templateSha256, String classification) {
    Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    require(templateKey, TEMPLATE_KEY, "Template key must be 3-160 lowercase characters"); require(semanticVersion, SEMVER, "Semantic version is invalid"); require(templateSha256, SHA256, "Template SHA-256 is invalid"); requireText(displayName, 240, "Display name is required");
    PromptTemplate saved = templates.save(new PromptTemplate(projectId, templateKey, semanticVersion, displayName.trim(), trimToNull(sourceReference, 2000), templateSha256.toLowerCase(Locale.ROOT), optionalBounded(classification, 80, "INTERNAL"), actor));
    audit.append(project.getOrganizationId(), projectId, actor, "AGENT_PROMPT_TEMPLATE_REGISTERED", "prompt_template", saved.getId().toString(), "{\"templateKey\":\"" + templateKey + "\",\"version\":\"" + semanticVersion + "\",\"templateSha256\":\"" + templateSha256 + "\"}");
    return saved;
  }

  @Transactional
  public AgentSession declareSession(UUID projectId, String actor, UUID promptTemplateId, String agentIdentity, String provider, String modelName, String modelVersion, String sessionFingerprint, String contextSha256, int toolInvocationCount, String toolInvocationSha256, String purpose) {
    Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    if (promptTemplateId != null) templates.findByIdAndProjectId(promptTemplateId, projectId).orElseThrow(() -> new IllegalArgumentException("Prompt template not found in project"));
    requireText(agentIdentity, 240, "Agent identity is required"); requireText(provider, 160, "Provider is required"); requireText(modelName, 240, "Model name is required"); requireText(modelVersion, 240, "Model version is required"); require(sessionFingerprint, SHA256, "Session fingerprint is invalid");
    if (contextSha256 != null && !contextSha256.isBlank()) require(contextSha256, SHA256, "Context SHA-256 is invalid"); if (toolInvocationSha256 != null && !toolInvocationSha256.isBlank()) require(toolInvocationSha256, SHA256, "Tool invocation SHA-256 is invalid"); if (toolInvocationCount < 0 || toolInvocationCount > 100000) throw new IllegalArgumentException("Tool invocation count is out of range");
    Optional<AgentSession> existing = sessions.findByProjectIdAndSessionFingerprint(projectId, sessionFingerprint.toLowerCase(Locale.ROOT));
    if (existing.isPresent()) return existing.get();
    AgentSession saved = sessions.save(new AgentSession(projectId, promptTemplateId, agentIdentity.trim(), provider.trim(), modelName.trim(), modelVersion.trim(), sessionFingerprint.toLowerCase(Locale.ROOT), trimToNull(contextSha256, 64), toolInvocationCount, trimToNull(toolInvocationSha256, 64), trimToNull(purpose, 2000), actor));
    audit.append(project.getOrganizationId(), projectId, actor, "AGENT_SESSION_DECLARED", "agent_session", saved.getId().toString(), "{\"provider\":\"" + json(provider) + "\",\"model\":\"" + json(modelName) + "\",\"modelVersion\":\"" + json(modelVersion) + "\",\"toolInvocationCount\":" + toolInvocationCount + "}");
    return saved;
  }

  @Transactional
  public AgentSession completeSession(UUID projectId, UUID sessionId, String actor) { AgentSession session = requireSession(projectId, sessionId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER); session.complete(Instant.now()); Project project = access.requireProject(projectId); audit.append(project.getOrganizationId(), projectId, actor, "AGENT_SESSION_COMPLETED", "agent_session", sessionId.toString(), "{}"); return session; }
  @Transactional
  public AgentSession blockSession(UUID projectId, UUID sessionId, String actor) { AgentSession session = requireSession(projectId, sessionId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER); session.block(Instant.now()); Project project = access.requireProject(projectId); audit.append(project.getOrganizationId(), projectId, actor, "AGENT_SESSION_BLOCKED", "agent_session", sessionId.toString(), "{}"); return session; }

  @Transactional
  public AgentEvidence declareGeneratedChange(UUID projectId, UUID sessionId, String actor, UUID validationRunId, UUID evidenceAssetId, String changeReference, String generatedChangeSha256, AgentPolicyDecision policyDecision, String policyReference, String approvalTitle, String approvalDetails, int requiredQuorum, String requestedApprover, Instant approvalDueAt) {
    AgentSession session = requireSession(projectId, sessionId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER);
    if (session.getStatus() != AgentSessionStatus.DECLARED) throw new IllegalStateException("Only active agent sessions can declare generated changes");
    if (policyDecision == null || policyDecision == AgentPolicyDecision.FAIL) throw new IllegalStateException("A policy-failed agent change cannot request human approval");
    requireText(changeReference, 2000, "Change reference is required"); require(generatedChangeSha256, SHA256, "Generated change SHA-256 is invalid");
    Optional<AgentEvidence> existing = evidence.findByAgentSessionIdAndGeneratedChangeSha256(sessionId, generatedChangeSha256.toLowerCase(Locale.ROOT));
    if (existing.isPresent()) return existing.get();
    if (validationRunId != null && validations.findById(validationRunId).filter(run -> run.getProjectId().equals(projectId)).isEmpty()) throw new IllegalArgumentException("Validation run not found in project");
    if (evidenceAssetId != null && assets.findByIdAndProjectIdAndDeletedAtIsNull(evidenceAssetId, projectId).isEmpty()) throw new IllegalArgumentException("Evidence asset not found in project");
    String stableSourceId = sessionId + ":" + generatedChangeSha256.toLowerCase(Locale.ROOT);
    UUID approvalId = approvalOrchestration.requestApproval(projectId, actor, "AGENT_GENERATED_CHANGE", stableSourceId, requireText(approvalTitle, 240, "Approval title is required"), trimToNull(approvalDetails, 2000), requiredQuorum, requestedApprover, approvalDueAt);
    AgentEvidence saved = evidence.save(new AgentEvidence(projectId, sessionId, validationRunId, evidenceAssetId, approvalId, changeReference.trim(), generatedChangeSha256.toLowerCase(Locale.ROOT), policyDecision, trimToNull(policyReference, 2000), actor));
    Project project = access.requireProject(projectId);
    audit.append(project.getOrganizationId(), projectId, actor, "AGENT_GENERATED_CHANGE_DECLARED", "agent_evidence", saved.getId().toString(), "{\"agentSessionId\":\"" + sessionId + "\",\"approvalRequestId\":\"" + approvalId + "\",\"changeSha256\":\"" + generatedChangeSha256 + "\",\"policyDecision\":\"" + policyDecision + "\"}");
    return saved;
  }

  @Transactional(readOnly = true)
  public PageResponse<PromptTemplateView> listPromptTemplates(UUID projectId, String actor, int page, int size) { access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); return PageResponse.from(templates.findByProjectIdOrderByRegisteredAtDesc(projectId, pageable(page, size)).map(this::templateView)); }
  @Transactional(readOnly = true)
  public PageResponse<AgentSessionView> listSessions(UUID projectId, String actor, int page, int size) { access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); return PageResponse.from(sessions.findByProjectIdOrderByDeclaredAtDesc(projectId, pageable(page, size)).map(this::sessionView)); }
  @Transactional(readOnly = true)
  public PageResponse<AgentEvidenceView> listEvidence(UUID projectId, String actor, int page, int size) { access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER); return PageResponse.from(evidence.findByProjectIdOrderByDeclaredAtDesc(projectId, pageable(page, size)).map(this::evidenceView)); }

  private AgentSession requireSession(UUID projectId, UUID sessionId, String actor, MembershipRole... roles) { access.requireMembership(projectId, actor, roles); return sessions.lockById(sessionId).filter(s -> s.getProjectId().equals(projectId)).orElseThrow(() -> new IllegalArgumentException("Agent session not found in project")); }
  private PromptTemplateView templateView(PromptTemplate t) { return new PromptTemplateView(t.getId(), t.getTemplateKey(), t.getSemanticVersion(), t.getDisplayName(), t.getSourceReference(), t.getTemplateSha256(), t.getClassification(), t.getRegisteredBy(), t.getRegisteredAt()); }
  private AgentSessionView sessionView(AgentSession s) { return new AgentSessionView(s.getId(), s.getPromptTemplateId(), s.getAgentIdentity(), s.getProvider(), s.getModelName(), s.getModelVersion(), s.getSessionFingerprint(), s.getContextSha256(), s.getToolInvocationCount(), s.getToolInvocationSha256(), s.getPurpose(), s.getStatus(), s.getDeclaredBy(), s.getDeclaredAt(), s.getCompletedAt()); }
  private AgentEvidenceView evidenceView(AgentEvidence e) { ApprovalRequest approval = approvals.findById(e.getApprovalRequestId()).orElseThrow(() -> new IllegalStateException("Agent approval request not found")); List<String> approvers = decisions.findByApprovalRequestIdOrderByDecidedAtAsc(approval.getId()).stream().filter(d -> d.getDecision() == ApprovalDecisionType.APPROVE).map(d -> d.getActor()).toList(); return new AgentEvidenceView(e.getId(), e.getAgentSessionId(), e.getValidationRunId(), e.getEvidenceAssetId(), e.getApprovalRequestId(), e.getChangeReference(), e.getGeneratedChangeSha256(), e.getPolicyDecision(), e.getPolicyReference(), e.getDeclaredBy(), e.getDeclaredAt(), approval.getApprovalStatus(), approvers); }
  private PageRequest pageable(int page, int size) { return PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))); }
  private String requireText(String value, int max, String message) { if (value == null || value.isBlank() || value.trim().length() > max) throw new IllegalArgumentException(message); return value.trim(); }
  private void require(String value, String expression, String message) { if (value == null || !value.matches(expression)) throw new IllegalArgumentException(message); }
  private String trimToNull(String value, int max) { if (value == null || value.isBlank()) return null; if (value.trim().length() > max) throw new IllegalArgumentException("Value exceeds maximum length"); return value.trim(); }
  private String optionalBounded(String value, int max, String fallback) { return trimToNull(value, max) == null ? fallback : trimToNull(value, max); }
  private String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
  public record PromptTemplateView(UUID id, String templateKey, String semanticVersion, String displayName, String sourceReference, String templateSha256, String classification, String registeredBy, Instant registeredAt) {}
  public record AgentSessionView(UUID id, UUID promptTemplateId, String agentIdentity, String provider, String modelName, String modelVersion, String sessionFingerprint, String contextSha256, int toolInvocationCount, String toolInvocationSha256, String purpose, AgentSessionStatus status, String declaredBy, Instant declaredAt, Instant completedAt) {}
  public record AgentEvidenceView(UUID id, UUID agentSessionId, UUID validationRunId, UUID evidenceAssetId, UUID approvalRequestId, String changeReference, String generatedChangeSha256, AgentPolicyDecision policyDecision, String policyReference, String declaredBy, Instant declaredAt, ApprovalStatus humanApprovalStatus, List<String> humanApprovers) {}
}
