package ai.xdev.aisdlc.domain;

public final class DomainTypes {
  private DomainTypes() {}
  public enum ProjectStatus { ACTIVE, ARCHIVED }
  public enum KitLayer { CORE, EXTENSION, PRESET, OVERRIDE }
  public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
  public enum FindingTriageStatus { OPEN, ACKNOWLEDGED, ACCEPTED_RISK, FALSE_POSITIVE, RESOLVED }
  public enum ValidationStatus { PASSED, FAILED, BLOCKED }
  public enum TraceNodeType { REQUIREMENT, SPEC, TASK, TEST, EVIDENCE }
  public enum ReviewType { MERGE_REQUEST, PHASE_GATE, EXCEPTION }
  public enum ReviewStatus { PENDING, APPROVED, REJECTED, CHANGES_REQUESTED }
  public enum MembershipRole { OWNER, DEVELOPER, REVIEWER, VIEWER }
  public enum EvidenceAssetType { VALIDATION, SPECIFICATION, REVIEW, GOVERNANCE, DELIVERY, SBOM, PROVENANCE, OTHER }
  public enum EvidenceAccessLevel { PROJECT, REVIEWERS, OWNERS }
  public enum ObjectLockMode { GOVERNANCE, COMPLIANCE }
  public enum ScmProvider { GITHUB, GITLAB, BITBUCKET, AZURE_DEVOPS, JIRA }
  public enum ScmEventType { PUSH, PULL_REQUEST, CHECK_RUN, WORKFLOW_RUN, RELEASE, WORK_ITEM }
  public enum ScmProcessingStatus { RECEIVED, PROCESSED, IGNORED, FAILED }
  public enum ScmPolicyConclusion { SUCCESS, FAILURE, ACTION_REQUIRED, NEUTRAL }
  /** Outcome of an outbound policy-feedback publish. {@code SKIPPED} means no publisher was applicable, not that it failed. */
  public enum ScmFeedbackState { PUBLISHED, FAILED, SKIPPED }
  public enum NotificationChannelType { EMAIL, SLACK_WEBHOOK, TEAMS_WEBHOOK, GENERIC_WEBHOOK }
  public enum NotificationDeliveryStatus { PENDING, SENDING, DELIVERED, RETRY_SCHEDULED, FAILED }
  public enum ApprovalStatus { PENDING, ESCALATED, APPROVED, REJECTED }
  public enum ApprovalDecisionType { APPROVE, REJECT }
  public enum SecurityExceptionNoticeStatus { ACTIVE, EXPIRED, RESOLVED }
  public enum SbomFormat { CYCLONEDX_JSON, SPDX_JSON }
  public enum ProvenanceSignatureMethod { GITHUB_ATTESTATION, SIGSTORE_KEYLESS, COSIGN_KEY, OTHER }
  public enum ProvenanceVerificationStatus { DECLARED, VERIFIED, REJECTED }
  public enum PolicyBundleLifecycle { DRAFT, ACTIVE, RETIRED }
  public enum PolicyEvaluationMode { DRY_RUN, ENFORCEMENT, FIXTURE }
  public enum PolicyEvaluationOutcome { PASS, FAIL, ERROR }
  public enum AgentSessionStatus { DECLARED, COMPLETED, BLOCKED }
  public enum AgentPolicyDecision { PASS, FAIL, NOT_EVALUATED }
  public enum TenantStatus { ACTIVE, SUSPENDED, ARCHIVED }
  public enum TenantRole { TENANT_ADMIN, COMPLIANCE_OFFICER, IDENTITY_ADMIN, AUDITOR, MEMBER }
  public enum FederationProtocol { OIDC, SAML }
  public enum EDiscoveryExportStatus { REQUESTED, READY, FAILED }
}
