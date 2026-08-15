package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import ai.xdev.aisdlc.web.ValidationContracts.*;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValidationServiceTest {
  @Test
  void rejectsBareExecutionBeforeWritingEvidence() {
    ProjectAccessService access = mock(ProjectAccessService.class);
    ValidationRunRepository runs = mock(ValidationRunRepository.class);
    ValidationService service = new ValidationService(access, runs, mock(FindingRepository.class), mock(ValidationEvidenceRepository.class), mock(AuditService.class));
    UUID projectId = UUID.randomUUID();
    Project project = new Project(UUID.randomUUID(), "demo", "Demo", "");
    when(access.requireMembership(eq(projectId), anyString(), any(MembershipRole[].class))).thenReturn(project);
    ValidationRunRequest request = new ValidationRunRequest(ValidationStatus.PASSED, "0.1", "kit@1", "provider/model@rev", true, List.of(), List.of());
    assertThrows(IllegalArgumentException.class, () -> service.ingest(projectId, "developer-1", "run-1", request));
    verifyNoInteractions(runs);
  }
  @Test
  void storesFindingsEvidenceAndAuditForFreshIdempotencyKey() throws Exception {
    ProjectAccessService access = mock(ProjectAccessService.class); ValidationRunRepository runs = mock(ValidationRunRepository.class); FindingRepository findings = mock(FindingRepository.class); ValidationEvidenceRepository evidence = mock(ValidationEvidenceRepository.class); AuditService audit = mock(AuditService.class);
    ValidationService service = new ValidationService(access, runs, findings, evidence, audit);
    UUID projectId = UUID.randomUUID(); Project project = new Project(UUID.randomUUID(), "demo", "Demo", "");
    when(access.requireMembership(eq(projectId), anyString(), any(MembershipRole[].class))).thenReturn(project); when(runs.findByProjectIdAndIdempotencyKey(projectId, "run-1")).thenReturn(Optional.empty());
    ValidationRun stored = new ValidationRun(projectId, "run-1", ValidationStatus.FAILED, "0.1", "kit@1", "provider/model@rev", "developer-1"); setId(stored, UUID.randomUUID()); when(runs.save(any(ValidationRun.class))).thenReturn(stored);
    when(findings.findByValidationRunId(stored.getId())).thenReturn(List.of(new Finding(stored.getId(), Severity.HIGH, "AISDLC-001", "Missing task", "spec.md", 1, "file://evidence")));
    ValidationRunRequest request = new ValidationRunRequest(ValidationStatus.FAILED, "0.1", "kit@1", "provider/model@rev", false, List.of(new FindingInput(Severity.HIGH, "AISDLC-001", "Missing task", "spec.md", 1, "file://evidence")), List.of(new EvidenceInput("spec-kit-tree", "a".repeat(64), "file://spec")));
    ValidationRunView response = service.ingest(projectId, "developer-1", "run-1", request);
    assertEquals(ValidationStatus.FAILED, response.status()); assertEquals(1, response.findings().size());
    verify(findings).save(any(Finding.class)); verify(evidence).save(any(ValidationEvidence.class)); verify(audit).append(eq(project.getOrganizationId()), eq(projectId), eq("developer-1"), eq("validation.run.ingested"), anyString(), anyString(), anyString());
  }
  @Test
  void reviewerTriageRequiresHumanRationaleForAcceptedRisk() throws Exception {
    ProjectAccessService access = mock(ProjectAccessService.class); ValidationRunRepository runs = mock(ValidationRunRepository.class); FindingRepository findings = mock(FindingRepository.class); AuditService audit = mock(AuditService.class);
    ValidationService service = new ValidationService(access, runs, findings, mock(ValidationEvidenceRepository.class), audit);
    UUID projectId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); UUID findingId = UUID.randomUUID(); Project project = new Project(UUID.randomUUID(), "demo", "Demo", "");
    ValidationRun run = new ValidationRun(projectId, "run-1", ValidationStatus.FAILED, "0.1", "kit@1", "provider/model@rev", "developer-1"); setId(run, runId);
    Finding finding = new Finding(runId, Severity.HIGH, "AISDLC-001", "Missing task", "spec.md", 1, "file://evidence"); setId(finding, findingId);
    when(access.requireMembership(eq(projectId), eq("reviewer-1"), any(MembershipRole[].class))).thenReturn(project); when(runs.findById(runId)).thenReturn(Optional.of(run)); when(findings.findById(findingId)).thenReturn(Optional.of(finding));
    assertThrows(IllegalArgumentException.class, () -> service.triageFinding(projectId, runId, findingId, "reviewer-1", new FindingTriageRequest(FindingTriageStatus.ACCEPTED_RISK, "")));
    FindingDetailView triaged = service.triageFinding(projectId, runId, findingId, "reviewer-1", new FindingTriageRequest(FindingTriageStatus.ACCEPTED_RISK, "approved temporary exception"));
    assertEquals(FindingTriageStatus.ACCEPTED_RISK, triaged.triageStatus()); assertEquals("reviewer-1", triaged.triagedBy()); verify(audit).append(eq(project.getOrganizationId()), eq(projectId), eq("reviewer-1"), eq("validation.finding.triaged"), anyString(), anyString(), anyString());
  }
  @Test
  void ownerCanSetRetentionOnlyAfterEvidenceCreation() throws Exception {
    ProjectAccessService access = mock(ProjectAccessService.class); ValidationRunRepository runs = mock(ValidationRunRepository.class); ValidationEvidenceRepository evidence = mock(ValidationEvidenceRepository.class); AuditService audit = mock(AuditService.class);
    ValidationService service = new ValidationService(access, runs, mock(FindingRepository.class), evidence, audit);
    UUID projectId = UUID.randomUUID(); UUID runId = UUID.randomUUID(); UUID evidenceId = UUID.randomUUID(); Project project = new Project(UUID.randomUUID(), "demo", "Demo", "");
    ValidationRun run = new ValidationRun(projectId, "run-1", ValidationStatus.PASSED, "0.1", "kit@1", "provider/model@rev", "developer-1"); setId(run, runId);
    ValidationEvidence stored = new ValidationEvidence(runId, "spec-kit-tree", "a".repeat(64), "file://spec"); setId(stored, evidenceId);
    when(access.requireMembership(eq(projectId), eq("owner-1"), any(MembershipRole[].class))).thenReturn(project); when(runs.findById(runId)).thenReturn(Optional.of(run)); when(evidence.findById(evidenceId)).thenReturn(Optional.of(stored));
    assertThrows(IllegalArgumentException.class, () -> service.setEvidenceRetention(projectId, runId, evidenceId, "owner-1", new EvidenceRetentionRequest(Instant.EPOCH)));
    EvidenceView response = service.setEvidenceRetention(projectId, runId, evidenceId, "owner-1", new EvidenceRetentionRequest(Instant.now().plusSeconds(3600)));
    assertNotNull(response.retentionUntil()); verify(audit).append(eq(project.getOrganizationId()), eq(projectId), eq("owner-1"), eq("validation.evidence.retention.updated"), anyString(), anyString(), anyString());
  }
  private static void setId(Object target, UUID value) throws Exception { Field field = target.getClass().getDeclaredField("id"); field.setAccessible(true); field.set(target, value); }
}
