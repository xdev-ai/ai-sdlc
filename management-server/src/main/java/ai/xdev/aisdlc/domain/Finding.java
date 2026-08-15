package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "findings")
public class Finding {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "validation_run_id", nullable = false) private UUID validationRunId;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private DomainTypes.Severity severity;
  @Column(nullable = false, length = 100) private String code;
  @Column(nullable = false, columnDefinition = "text") private String message;
  @Column(length = 400) private String path;
  private Integer line;
  @Column(name = "evidence_uri", length = 1000) private String evidenceUri;
  @Enumerated(EnumType.STRING) @Column(name = "triage_status", nullable = false) private DomainTypes.FindingTriageStatus triageStatus = DomainTypes.FindingTriageStatus.OPEN;
  @Column(name = "triaged_by", length = 120) private String triagedBy;
  @Column(name = "triaged_at") private Instant triagedAt;
  @Column(name = "triage_note", columnDefinition = "text") private String triageNote;
  protected Finding() {}
  public Finding(UUID runId, DomainTypes.Severity severity, String code, String message, String path, Integer line, String evidenceUri) { this.validationRunId = runId; this.severity = severity; this.code = code; this.message = message; this.path = path; this.line = line; this.evidenceUri = evidenceUri; }
  public DomainTypes.Severity getSeverity() { return severity; }
  public String getCode() { return code; }
  public String getMessage() { return message; }
  public UUID getId() { return id; }
  public String getPath() { return path; }
  public Integer getLine() { return line; }
  public String getEvidenceUri() { return evidenceUri; }
  public UUID getValidationRunId() { return validationRunId; }
  public DomainTypes.FindingTriageStatus getTriageStatus() { return triageStatus; }
  public String getTriagedBy() { return triagedBy; }
  public Instant getTriagedAt() { return triagedAt; }
  public String getTriageNote() { return triageNote; }
  public void triage(DomainTypes.FindingTriageStatus status, String subject, Instant at, String note) { this.triageStatus = status; this.triagedBy = subject; this.triagedAt = at; this.triageNote = note; }
}
