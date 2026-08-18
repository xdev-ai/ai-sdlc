package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.web.PageRequests;
import ai.xdev.aisdlc.web.PageResponse;
import ai.xdev.aisdlc.web.ValidationContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationService {
  private final ProjectAccessService access;
  private final ValidationRunRepository runs;
  private final FindingRepository findings;
  private final ValidationEvidenceRepository evidences;
  private final AuditService audit;

  public ValidationService(ProjectAccessService access, ValidationRunRepository runs, FindingRepository findings, ValidationEvidenceRepository evidences, AuditService audit) {
    this.access = access; this.runs = runs; this.findings = findings; this.evidences = evidences; this.audit = audit;
  }

  @Transactional
  public ValidationRunView ingest(UUID projectId, String subject, String idempotencyKey, ValidationRunRequest request) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER);
    if (request.bare()) throw new IllegalArgumentException("Bare execution is prohibited");
    if (request.modelPin().isBlank()) throw new IllegalArgumentException("A pinned model is required");
    var existing = runs.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
    if (existing.isPresent()) return toIngestView(existing.get());
    ValidationRun run = runs.save(new ValidationRun(projectId, idempotencyKey, request.status(), request.cliVersion(), request.kitVersion(), request.modelPin(), subject));
    request.findings().forEach(input -> findings.save(new Finding(run.getId(), input.severity(), input.code(), input.message(), input.path(), input.line(), input.evidenceUri())));
    request.evidence().forEach(input -> evidences.save(new ValidationEvidence(run.getId(), input.type(), input.digestSha256().toLowerCase(), input.uri())));
    audit.append(project.getOrganizationId(), projectId, subject, "validation.run.ingested", "validation_run", run.getId().toString(), "{\"status\":\"" + run.getStatus().name() + "\"}");
    return toIngestView(run);
  }

  public PageResponse<ValidationRunListItem> list(UUID projectId, String subject, ValidationStatus status, int page, int size) {
    access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    Page<ValidationRun> result = status == null
        ? runs.findByProjectId(projectId, PageRequests.of(page, size, "completedAt,desc", "completedAt", "status", "cliVersion", "kitVersion"))
        : runs.findByProjectIdAndStatus(projectId, status, PageRequests.of(page, size, "completedAt,desc", "completedAt", "status", "cliVersion", "kitVersion"));
    return PageResponse.from(result.map(this::toListItem));
  }

  public ValidationRunDetailView detail(UUID projectId, UUID runId, String subject) {
    access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    ValidationRun run = runs.findById(runId).filter(candidate -> candidate.getProjectId().equals(projectId)).orElseThrow(() -> new IllegalArgumentException("Validation run not found"));
    return new ValidationRunDetailView(run.getId(), run.getProjectId(), run.getStatus(), run.getIdempotencyKey(), run.getCliVersion(), run.getKitVersion(), run.getModelPin(), run.getActorSubject(), run.getCompletedAt(), findings.findByValidationRunId(run.getId()).stream().map(this::toFindingDetail).toList(), evidences.findByValidationRunId(run.getId()).stream().map(this::toEvidence).toList());
  }

  @Transactional
  public FindingDetailView triageFinding(UUID projectId, UUID runId, UUID findingId, String subject, FindingTriageRequest request) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.REVIEWER);
    requireRunInProject(projectId, runId);
    Finding finding = findings.findById(findingId).filter(candidate -> candidate.getValidationRunId().equals(runId)).orElseThrow(() -> new IllegalArgumentException("Finding not found"));
    if (request.status() == FindingTriageStatus.OPEN) throw new IllegalArgumentException("Triage must select a final human classification");
    String note = request.note() == null ? "" : request.note().trim();
    if ((request.status() == FindingTriageStatus.ACCEPTED_RISK || request.status() == FindingTriageStatus.FALSE_POSITIVE) && note.isBlank()) throw new IllegalArgumentException("A rationale is required for accepted risk or false positive triage");
    finding.triage(request.status(), subject, Instant.now(), note);
    audit.append(project.getOrganizationId(), projectId, subject, "validation.finding.triaged", "finding", findingId.toString(), "{\"status\":\"" + request.status().name() + "\"}");
    return toFindingDetail(finding);
  }

  @Transactional
  public EvidenceView setEvidenceRetention(UUID projectId, UUID runId, UUID evidenceId, String subject, EvidenceRetentionRequest request) {
    Project project = access.requireMembership(projectId, subject, MembershipRole.OWNER);
    requireRunInProject(projectId, runId);
    ValidationEvidence evidence = evidences.findById(evidenceId).filter(candidate -> candidate.getValidationRunId().equals(runId)).orElseThrow(() -> new IllegalArgumentException("Validation evidence not found"));
    if (request.retentionUntil().isBefore(evidence.getCreatedAt())) throw new IllegalArgumentException("Evidence retention must not precede evidence creation");
    evidence.setRetentionUntil(request.retentionUntil());
    audit.append(project.getOrganizationId(), projectId, subject, "validation.evidence.retention.updated", "validation_evidence", evidenceId.toString(), "{\"retentionUntil\":\"" + request.retentionUntil() + "\"}");
    return toEvidence(evidence);
  }

  private ValidationRun requireRunInProject(UUID projectId, UUID runId) { return runs.findById(runId).filter(candidate -> candidate.getProjectId().equals(projectId)).orElseThrow(() -> new IllegalArgumentException("Validation run not found")); }

  private ValidationRunView toIngestView(ValidationRun run) {
    List<FindingView> runFindings = findings.findByValidationRunId(run.getId()).stream().map(f -> new FindingView(f.getSeverity(), f.getCode(), f.getMessage())).toList();
    return new ValidationRunView(run.getId(), run.getProjectId(), run.getStatus(), run.getIdempotencyKey(), runFindings);
  }

  private ValidationRunListItem toListItem(ValidationRun run) {
    return new ValidationRunListItem(run.getId(), run.getStatus(), run.getIdempotencyKey(), run.getCliVersion(), run.getKitVersion(), run.getModelPin(), run.getActorSubject(), run.getCompletedAt(), findings.findByValidationRunId(run.getId()).size());
  }

  private FindingDetailView toFindingDetail(Finding finding) { return new FindingDetailView(finding.getId(), finding.getSeverity(), finding.getCode(), finding.getMessage(), finding.getPath(), finding.getLine(), finding.getEvidenceUri(), finding.getTriageStatus(), finding.getTriagedBy(), finding.getTriagedAt(), finding.getTriageNote()); }
  private EvidenceView toEvidence(ValidationEvidence evidence) { return new EvidenceView(evidence.getId(), evidence.getEvidenceType(), evidence.getDigestSha256(), evidence.getUri(), evidence.getMetadata(), evidence.getCreatedAt(), evidence.getRetentionUntil()); }
}
