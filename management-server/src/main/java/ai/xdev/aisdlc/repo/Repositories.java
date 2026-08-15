package ai.xdev.aisdlc.repo;

import ai.xdev.aisdlc.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

public final class Repositories {
  private Repositories() {}
  @Repository public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from Organization o where o.id = :id") Optional<Organization> lockById(UUID id);
  }
  @Repository public interface ProjectRepository extends JpaRepository<Project, UUID> { List<Project> findByOrganizationId(UUID organizationId); }
  @Repository public interface MembershipRepository extends JpaRepository<ProjectMembership, UUID> { Optional<ProjectMembership> findByProjectIdAndSubject(UUID projectId, String subject); }
  @Repository public interface ValidationRunRepository extends JpaRepository<ValidationRun, UUID> { Optional<ValidationRun> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey); List<ValidationRun> findTop25ByProjectIdOrderByCompletedAtDesc(UUID projectId); }
  @Repository public interface FindingRepository extends JpaRepository<Finding, UUID> { List<Finding> findByValidationRunId(UUID validationRunId); }
  @Repository public interface ValidationEvidenceRepository extends JpaRepository<ValidationEvidence, UUID> {}
  @Repository public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> { Optional<AuditEvent> findTopByOrganizationIdOrderBySequenceDesc(UUID organizationId); List<AuditEvent> findTop100ByOrganizationIdOrderBySequenceDesc(UUID organizationId); }
}
