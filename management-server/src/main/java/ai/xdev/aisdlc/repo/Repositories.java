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
  @Repository public interface TenantRepository extends JpaRepository<Tenant, UUID> { Optional<Tenant> findBySlug(String slug); }
  @Repository public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> { Optional<TenantMembership> findByTenantIdAndSubject(UUID tenantId, String subject); List<TenantMembership> findByTenantIdOrderByCreatedAtAsc(UUID tenantId); }
  @Repository public interface TenantPermissionSetRepository extends JpaRepository<TenantPermissionSet, UUID> { List<TenantPermissionSet> findByTenantIdOrderByPermissionKeyAsc(UUID tenantId); Optional<TenantPermissionSet> findByIdAndTenantId(UUID id, UUID tenantId); }
  @Repository public interface TenantFederationConfigRepository extends JpaRepository<TenantFederationConfig, UUID> { List<TenantFederationConfig> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); }
  @Repository public interface ScimServicePrincipalRepository extends JpaRepository<ScimServicePrincipal, UUID> { Optional<ScimServicePrincipal> findByTokenSha256AndActiveTrue(String tokenSha256); Optional<ScimServicePrincipal> findByIdAndTenantId(UUID id, UUID tenantId); }
  @Repository public interface ScimUserRepository extends JpaRepository<ScimUser, UUID> { Optional<ScimUser> findByIdAndTenantId(UUID id, UUID tenantId); Optional<ScimUser> findByTenantIdAndSubject(UUID tenantId, String subject); Page<ScimUser> findByTenantIdOrderByUserNameAsc(UUID tenantId, Pageable pageable); }
  @Repository public interface TenantLegalHoldRepository extends JpaRepository<TenantLegalHold, UUID> { List<TenantLegalHold> findByTenantIdAndActiveTrueOrderByCreatedAtDesc(UUID tenantId); Optional<TenantLegalHold> findByIdAndTenantId(UUID id, UUID tenantId); boolean existsByTenantIdAndActiveTrue(UUID tenantId); }
  @Repository public interface EDiscoveryExportRepository extends JpaRepository<EDiscoveryExport, UUID> { Optional<EDiscoveryExport> findByIdAndTenantId(UUID id, UUID tenantId); Page<EDiscoveryExport> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable); }
  @Repository public interface TenantAuditEventRepository extends JpaRepository<TenantAuditEvent, UUID> { List<TenantAuditEvent> findTop500ByTenantIdOrderByOccurredAtAsc(UUID tenantId); }
  @Repository public interface ProjectRepository extends JpaRepository<Project, UUID> { List<Project> findByOrganizationId(UUID organizationId); Page<Project> findByOrganizationId(UUID organizationId, Pageable pageable); }
  @Repository public interface MembershipRepository extends JpaRepository<ProjectMembership, UUID> { Optional<ProjectMembership> findByProjectIdAndSubject(UUID projectId, String subject); List<ProjectMembership> findByProjectIdOrderByCreatedAtAsc(UUID projectId); long countByProjectIdAndRole(UUID projectId, DomainTypes.MembershipRole role); }
  @Repository public interface ValidationRunRepository extends JpaRepository<ValidationRun, UUID> { Optional<ValidationRun> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey); Page<ValidationRun> findByProjectId(UUID projectId, Pageable pageable); Page<ValidationRun> findByProjectIdAndStatus(UUID projectId, DomainTypes.ValidationStatus status, Pageable pageable); List<ValidationRun> findTop25ByProjectIdOrderByCompletedAtDesc(UUID projectId); }
  @Repository public interface FindingRepository extends JpaRepository<Finding, UUID> { List<Finding> findByValidationRunId(UUID validationRunId); }
  @Repository public interface ValidationEvidenceRepository extends JpaRepository<ValidationEvidence, UUID> { List<ValidationEvidence> findByValidationRunId(UUID validationRunId); }
  @Repository public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, UUID> { Page<EvidenceAsset> findByProjectIdAndDeletedAtIsNull(UUID projectId, Pageable pageable); Optional<EvidenceAsset> findByIdAndProjectIdAndDeletedAtIsNull(UUID id, UUID projectId); Optional<EvidenceAsset> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey); }
  @Repository public interface SbomAssetRepository extends JpaRepository<SbomAsset, UUID> { Optional<SbomAsset> findByProjectIdAndDocumentSha256(UUID projectId, String documentSha256); Optional<SbomAsset> findByIdAndProjectId(UUID id, UUID projectId); Page<SbomAsset> findByProjectIdOrderByIngestedAtDesc(UUID projectId, Pageable pageable); }
  @Repository public interface ProvenanceRecordRepository extends JpaRepository<ProvenanceRecord, UUID> { Optional<ProvenanceRecord> findByIdAndProjectId(UUID id, UUID projectId); Page<ProvenanceRecord> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable); List<ProvenanceRecord> findBySbomAssetIdOrderByCreatedAtDesc(UUID sbomAssetId); }
  @Repository public interface ScmRepositoryLinkRepository extends JpaRepository<ScmRepositoryLink, UUID> { Optional<ScmRepositoryLink> findByProviderAndRepositoryFullName(DomainTypes.ScmProvider provider, String repositoryFullName); List<ScmRepositoryLink> findByProjectIdOrderByCreatedAtDesc(UUID projectId); }
  @Repository public interface ScmEventRepository extends JpaRepository<ScmEvent, UUID> { Optional<ScmEvent> findByProviderAndDeliveryId(DomainTypes.ScmProvider provider, String deliveryId); Optional<ScmEvent> findByIdAndProjectId(UUID id, UUID projectId); Page<ScmEvent> findByProjectId(UUID projectId, Pageable pageable); }
  @Repository public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> { List<NotificationChannel> findByProjectIdAndEnabledTrueOrderByCreatedAtAsc(UUID projectId); List<NotificationChannel> findByProjectIdOrderByCreatedAtDesc(UUID projectId); Optional<NotificationChannel> findByIdAndProjectId(UUID id, UUID projectId); }
  @Repository public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {
    Optional<NotificationDelivery> findByChannelIdAndIdempotencyKey(UUID channelId, String idempotencyKey);
    Page<NotificationDelivery> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from NotificationDelivery d where d.id = :id") Optional<NotificationDelivery> lockById(UUID id);
    @Query("select d.id from NotificationDelivery d where (d.deliveryStatus in :states and d.nextAttemptAt <= :now) or (d.deliveryStatus = ai.xdev.aisdlc.domain.DomainTypes$NotificationDeliveryStatus.SENDING and d.lastAttemptAt <= :staleBefore) order by d.nextAttemptAt asc") List<UUID> findEligibleIds(Set<DomainTypes.NotificationDeliveryStatus> states, java.time.Instant now, java.time.Instant staleBefore, Pageable pageable);
  }
  @Repository public interface NotificationDeliveryReceiptRepository extends JpaRepository<NotificationDeliveryReceipt, UUID> { List<NotificationDeliveryReceipt> findByDeliveryIdOrderByDeliveryTimestampDesc(UUID deliveryId); }
  @Repository public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    Page<ApprovalRequest> findByProjectIdOrderByDueAtAsc(UUID projectId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from ApprovalRequest a where a.id = :id") Optional<ApprovalRequest> lockById(UUID id);
    @Query("select a from ApprovalRequest a where a.approvalStatus in :states and a.dueAt <= :cutoff order by a.dueAt asc") List<ApprovalRequest> findDueByStatus(Set<DomainTypes.ApprovalStatus> states, java.time.Instant cutoff, Pageable pageable);
  }
  @Repository public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> { boolean existsByApprovalRequestIdAndActor(UUID approvalRequestId, String actor); long countByApprovalRequestIdAndDecision(UUID approvalRequestId, DomainTypes.ApprovalDecisionType decision); List<ApprovalDecision> findByApprovalRequestIdOrderByDecidedAtAsc(UUID approvalRequestId); }
  @Repository public interface SecurityExceptionNoticeRepository extends JpaRepository<SecurityExceptionNotice, UUID> {
    Page<SecurityExceptionNotice> findByProjectIdOrderByExpiresAtAsc(UUID projectId, Pageable pageable);
    Optional<SecurityExceptionNotice> findByIdAndProjectId(UUID id, UUID projectId);
    @Query("select e from SecurityExceptionNotice e where e.exceptionStatus = :status and e.expiresAt <= :cutoff order by e.expiresAt asc") List<SecurityExceptionNotice> findExpiring(DomainTypes.SecurityExceptionNoticeStatus status, java.time.Instant cutoff, Pageable pageable);
  }
  @Repository public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {
    Optional<PromptTemplate> findByIdAndProjectId(UUID id, UUID projectId);
    Page<PromptTemplate> findByProjectIdOrderByRegisteredAtDesc(UUID projectId, Pageable pageable);
  }
  @Repository public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {
    Optional<AgentSession> findByIdAndProjectId(UUID id, UUID projectId);
    Optional<AgentSession> findByProjectIdAndSessionFingerprint(UUID projectId, String sessionFingerprint);
    Page<AgentSession> findByProjectIdOrderByDeclaredAtDesc(UUID projectId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from AgentSession s where s.id = :id") Optional<AgentSession> lockById(UUID id);
  }
  @Repository public interface AgentEvidenceRepository extends JpaRepository<AgentEvidence, UUID> {
    Optional<AgentEvidence> findByIdAndProjectId(UUID id, UUID projectId);
    Optional<AgentEvidence> findByAgentSessionIdAndGeneratedChangeSha256(UUID agentSessionId, String generatedChangeSha256);
    Page<AgentEvidence> findByProjectIdOrderByDeclaredAtDesc(UUID projectId, Pageable pageable);
  }
  @Repository public interface RiskScoreRepository extends JpaRepository<RiskScore, UUID> {
    Optional<RiskScore> findTopByProjectIdOrderByCalculatedAtDesc(UUID projectId);
    Page<RiskScore> findByProjectIdOrderByCalculatedAtDesc(UUID projectId, Pageable pageable);
  }
  @Repository public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> { Optional<AuditEvent> findTopByOrganizationIdOrderBySequenceDesc(UUID organizationId); Page<AuditEvent> findByOrganizationId(UUID organizationId, Pageable pageable); Page<AuditEvent> findByOrganizationIdAndActionContainingIgnoreCase(UUID organizationId, String action, Pageable pageable); List<AuditEvent> findByOrganizationIdOrderBySequenceAsc(UUID organizationId); List<AuditEvent> findTop100ByOrganizationIdOrderBySequenceDesc(UUID organizationId); List<AuditEvent> findTop500ByTenantIdOrderByOccurredAtAsc(UUID tenantId); }
}
