package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.web.ValidationContracts.*;
import java.util.List;
import java.util.UUID;
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
    if (existing.isPresent()) return toView(existing.get());
    ValidationRun run = runs.save(new ValidationRun(projectId, idempotencyKey, request.status(), request.cliVersion(), request.kitVersion(), request.modelPin(), subject));
    request.findings().forEach(input -> findings.save(new Finding(run.getId(), input.severity(), input.code(), input.message(), input.path(), input.line(), input.evidenceUri())));
    request.evidence().forEach(input -> evidences.save(new ValidationEvidence(run.getId(), input.type(), input.digestSha256(), input.uri())));
    audit.append(project.getOrganizationId(), projectId, subject, "validation.run.ingested", "validation_run", run.getId().toString(), "{\"status\":\"" + run.getStatus() + "\"}");
    return toView(run);
  }

  public List<ValidationRunView> list(UUID projectId, String subject) {
    access.requireMembership(projectId, subject, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return runs.findTop25ByProjectIdOrderByCompletedAtDesc(projectId).stream().map(this::toView).toList();
  }

  private ValidationRunView toView(ValidationRun run) {
    List<FindingView> runFindings = findings.findByValidationRunId(run.getId()).stream().map(f -> new FindingView(f.getSeverity(), f.getCode(), f.getMessage())).toList();
    return new ValidationRunView(run.getId(), run.getProjectId(), run.getStatus(), run.getIdempotencyKey(), runFindings);
  }
}

