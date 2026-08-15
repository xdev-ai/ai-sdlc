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
}
