package ai.xdev.aisdlc.repo;

import ai.xdev.aisdlc.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

public final class Repositories {
  private Repositories() {}
  @Repository public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from Organization o where o.id = :id") Optional<Organization> lockById(UUID id);
  }
  @Repository public interface ProjectRepository extends JpaRepository<Project, UUID> { List<Project> findByOrganizationId(UUID organizationId); Page<Project> findByOrganizationId(UUID organizationId, Pageable pageable); }
  @Repository public interface MembershipRepository extends JpaRepository<ProjectMembership, UUID> { Optional<ProjectMembership> findByProjectIdAndSubject(UUID projectId, String subject); List<ProjectMembership> findByProjectIdOrderByCreatedAtAsc(UUID projectId); long countByProjectIdAndRole(UUID projectId, DomainTypes.MembershipRole role); }
  @Repository public interface ValidationRunRepository extends JpaRepository<ValidationRun, UUID> { Optional<ValidationRun> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey); Page<ValidationRun> findByProjectId(UUID projectId, Pageable pageable); Page<ValidationRun> findByProjectIdAndStatus(UUID projectId, DomainTypes.ValidationStatus status, Pageable pageable); List<ValidationRun> findTop25ByProjectIdOrderByCompletedAtDesc(UUID projectId); }
  @Repository public interface FindingRepository extends JpaRepository<Finding, UUID> { List<Finding> findByValidationRunId(UUID validationRunId); }
  @Repository public interface ValidationEvidenceRepository extends JpaRepository<ValidationEvidence, UUID> { List<ValidationEvidence> findByValidationRunId(UUID validationRunId); }
  @Repository public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, UUID> { Page<EvidenceAsset> findByProjectIdAndDeletedAtIsNull(UUID projectId, Pageable pageable); Optional<EvidenceAsset> findByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId); Optional<EvidenceAsset> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey); }
  @Repository public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> { Optional<AuditEvent> findTopByOrganizationIdOrderBySequenceDesc(UUID organizationId); Page<AuditEvent> findByOrganizationId(UUID organizationId, Pageable pageable); Page<AuditEvent> findByOrganizationIdAndActionContainingIgnoreCase(UUID organizationId, String action, Pageable pageable); List<AuditEvent> findByOrganizationIdOrderBySequenceAsc(UUID organizationId); List<AuditEvent> findTop100ByOrganizationIdOrderBySequenceDesc(UUID organizationId); }
}
