package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.evidence.EvidenceStorageProperties;
import ai.xdev.aisdlc.evidence.ObjectStoragePort;
import ai.xdev.aisdlc.repo.Repositories.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnterpriseTenantService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final TenantRepository tenants; private final TenantMembershipRepository memberships; private final TenantPermissionSetRepository permissionSets;
  private final TenantFederationConfigRepository federations; private final ScimServicePrincipalRepository principals; private final ScimUserRepository scimUsers;
  private final TenantLegalHoldRepository legalHolds; private final EDiscoveryExportRepository exports; private final TenantAuditEventRepository tenantAudit;
  private final AuditEventRepository auditEvents; private final ObjectStoragePort storage; private final EvidenceStorageProperties storageProperties;
  private final NotificationSecretCipher cipher; private final ObjectMapper mapper;
  public EnterpriseTenantService(TenantRepository tenants, TenantMembershipRepository memberships, TenantPermissionSetRepository permissionSets, TenantFederationConfigRepository federations, ScimServicePrincipalRepository principals, ScimUserRepository scimUsers, TenantLegalHoldRepository legalHolds, EDiscoveryExportRepository exports, TenantAuditEventRepository tenantAudit, AuditEventRepository auditEvents, ObjectStoragePort storage, EvidenceStorageProperties storageProperties, NotificationSecretCipher cipher, ObjectMapper mapper) { this.tenants = tenants; this.memberships = memberships; this.permissionSets = permissionSets; this.federations = federations; this.principals = principals; this.scimUsers = scimUsers; this.legalHolds = legalHolds; this.exports = exports; this.tenantAudit = tenantAudit; this.auditEvents = auditEvents; this.storage = storage; this.storageProperties = storageProperties; this.cipher = cipher; this.mapper = mapper; }

  public record TenantView(UUID id, String slug, String displayName, TenantStatus status, String dataResidency, String encryptionKeyReference, boolean legalHoldEnabled) {}
  public record MembershipView(UUID id, String subject, TenantRole role) {}
  public record PermissionSetView(UUID id, String permissionKey, String displayName, List<String> permissions) {}
  public record FederationView(UUID id, FederationProtocol protocol, String issuerUri, String clientId, String metadataUri, Map<String, Object> claimMapping, boolean enabled) {}
  public record ScimCredentialView(UUID id, String displayName, String bearerToken) {}
  public record ScimUserView(UUID id, String externalId, String subject, String userName, String displayName, boolean active, Map<String, Object> attributes) {}
  public record LegalHoldView(UUID id, String holdKey, String reason, boolean active, Instant createdAt) {}
  public record EDiscoveryExportView(UUID id, EDiscoveryExportStatus status, String manifestSha256, Long sizeBytes, Instant createdAt, Instant readyAt, String presignedDownloadUrl) {}

  @Transactional public TenantView create(String slug, String displayName, String dataResidency, String encryptionKeyReference, String actor) {
    if (tenants.findBySlug(slug).isPresent()) throw new IllegalStateException("Tenant slug already exists");
    Tenant tenant = tenants.save(new Tenant(slug, displayName, dataResidency, blankToNull(encryptionKeyReference)));
    memberships.save(new TenantMembership(tenant.getId(), actor, TenantRole.TENANT_ADMIN));
    append(tenant.getId(), actor, "tenant.created", "tenant", tenant.getId().toString(), Map.of("slug", slug, "dataResidency", dataResidency));
    return view(tenant);
  }
  public TenantView get(UUID tenantId, String actor) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.COMPLIANCE_OFFICER, TenantRole.IDENTITY_ADMIN, TenantRole.AUDITOR, TenantRole.MEMBER); return view(requireTenant(tenantId)); }
  @Transactional public MembershipView assignMembership(UUID tenantId, String subject, TenantRole role, String actor) {
    requireRole(tenantId, actor, TenantRole.TENANT_ADMIN);
    if (memberships.findByTenantIdAndSubject(tenantId, subject).isPresent()) throw new IllegalStateException("Subject already belongs to tenant");
    TenantMembership saved = memberships.save(new TenantMembership(tenantId, subject, role)); append(tenantId, actor, "tenant.membership.assigned", "tenant_membership", saved.getId().toString(), Map.of("subject", subject, "role", role.name())); return membershipView(saved);
  }
  public List<MembershipView> memberships(UUID tenantId, String actor) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.IDENTITY_ADMIN, TenantRole.COMPLIANCE_OFFICER, TenantRole.AUDITOR); return memberships.findByTenantIdOrderByCreatedAtAsc(tenantId).stream().map(this::membershipView).toList(); }
  @Transactional public PermissionSetView createPermissionSet(UUID tenantId, String key, String displayName, List<String> permissions, String actor) {
    requireRole(tenantId, actor, TenantRole.TENANT_ADMIN); if (permissions == null || permissions.isEmpty()) throw new IllegalArgumentException("At least one permission is required");
    try { String json = mapper.writeValueAsString(permissions.stream().filter(p -> p.matches("[a-z][a-z0-9_.:-]{2,100}")).distinct().sorted().toList()); TenantPermissionSet saved = permissionSets.save(new TenantPermissionSet(tenantId, key, displayName, json)); append(tenantId, actor, "tenant.permission_set.created", "tenant_permission_set", saved.getId().toString(), Map.of("key", key)); return permissionView(saved); } catch (Exception error) { throw new IllegalArgumentException("Invalid permission set", error); }
  }
  public List<PermissionSetView> permissionSets(UUID tenantId, String actor) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.AUDITOR); return permissionSets.findByTenantIdOrderByPermissionKeyAsc(tenantId).stream().map(this::permissionView).toList(); }
  @Transactional public FederationView configureFederation(UUID tenantId, FederationProtocol protocol, String issuerUri, String clientId, String clientSecret, String metadataUri, Map<String, Object> claimMapping, boolean enabled, String actor) {
    requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.IDENTITY_ADMIN); requireHttps(issuerUri, "issuerUri"); if (metadataUri != null && !metadataUri.isBlank()) requireHttps(metadataUri, "metadataUri");
    try { String mappingJson = mapper.writeValueAsString(claimMapping == null ? Map.of() : claimMapping); String secret = blankToNull(clientSecret) == null ? null : cipher.encrypt(clientSecret); TenantFederationConfig saved = federations.save(new TenantFederationConfig(tenantId, protocol, issuerUri, blankToNull(clientId), secret, blankToNull(metadataUri), mappingJson, enabled, actor)); append(tenantId, actor, "tenant.federation.configured", "tenant_federation", saved.getId().toString(), Map.of("protocol", protocol.name(), "issuer", issuerUri, "enabled", enabled)); return federationView(saved); } catch (Exception error) { throw new IllegalArgumentException("Invalid federation mapping", error); }
  }
  public List<FederationView> federations(UUID tenantId, String actor) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.IDENTITY_ADMIN, TenantRole.AUDITOR); return federations.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(this::federationView).toList(); }
  @Transactional public ScimCredentialView createScimPrincipal(UUID tenantId, String displayName, String actor) {
    requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.IDENTITY_ADMIN); byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); ScimServicePrincipal saved = principals.save(new ScimServicePrincipal(tenantId, displayName, sha256(raw), actor)); append(tenantId, actor, "tenant.scim_principal.created", "scim_service_principal", saved.getId().toString(), Map.of("displayName", displayName)); return new ScimCredentialView(saved.getId(), saved.getDisplayName(), raw);
  }
  public boolean authorizeScim(UUID tenantId, String bearerToken) { if (bearerToken == null || bearerToken.isBlank()) return false; return principals.findByTokenSha256AndActiveTrue(sha256(bearerToken)).filter(p -> p.getTenantId().equals(tenantId)).isPresent(); }
  @Transactional public ScimUserView provisionScimUser(UUID tenantId, String externalId, String subject, String userName, String displayName, boolean active, Map<String, Object> attributes) {
    try { String attrs = mapper.writeValueAsString(attributes == null ? Map.of() : attributes); ScimUser user = scimUsers.findByTenantIdAndSubject(tenantId, subject).orElseGet(() -> new ScimUser(tenantId, blankToNull(externalId), subject, userName, displayName, active, attrs)); if (user.getId() != null) user.update(userName, displayName, active, attrs); ScimUser saved = scimUsers.save(user); append(tenantId, "scim", "tenant.scim_user.upserted", "scim_user", saved.getId().toString(), Map.of("subject", subject, "active", active)); return scimUserView(saved); } catch (Exception error) { throw new IllegalArgumentException("Invalid SCIM attributes", error); }
  }
  public List<ScimUserView> scimUsers(UUID tenantId, int page, int size) { return scimUsers.findByTenantIdOrderByUserNameAsc(tenantId, Pageable.ofSize(Math.min(Math.max(size, 1), 100)).withPage(Math.max(page, 0))).stream().map(this::scimUserView).toList(); }
  @Transactional public LegalHoldView createLegalHold(UUID tenantId, String holdKey, String reason, String actor) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.COMPLIANCE_OFFICER); TenantLegalHold saved = legalHolds.save(new TenantLegalHold(tenantId, holdKey, reason, actor)); Tenant tenant = requireTenant(tenantId); tenant.setLegalHoldEnabled(true); tenants.save(tenant); append(tenantId, actor, "tenant.legal_hold.created", "tenant_legal_hold", saved.getId().toString(), Map.of("holdKey", holdKey)); return legalHoldView(saved); }
  @Transactional public LegalHoldView releaseLegalHold(UUID tenantId, UUID holdId, String actor) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.COMPLIANCE_OFFICER); TenantLegalHold hold = legalHolds.findByIdAndTenantId(holdId, tenantId).orElseThrow(() -> new IllegalArgumentException("Legal hold not found")); if (!hold.isActive()) throw new IllegalStateException("Legal hold already released"); hold.release(actor); legalHolds.save(hold); Tenant tenant = requireTenant(tenantId); tenant.setLegalHoldEnabled(legalHolds.existsByTenantIdAndActiveTrue(tenantId)); tenants.save(tenant); append(tenantId, actor, "tenant.legal_hold.released", "tenant_legal_hold", holdId.toString(), Map.of()); return legalHoldView(hold); }
  public List<LegalHoldView> legalHolds(UUID tenantId, String actor) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.COMPLIANCE_OFFICER, TenantRole.AUDITOR); return legalHolds.findByTenantIdAndActiveTrueOrderByCreatedAtDesc(tenantId).stream().map(this::legalHoldView).toList(); }
  @Transactional public EDiscoveryExportView createExport(UUID tenantId, Map<String, Object> scope, String actor) {
    requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.COMPLIANCE_OFFICER, TenantRole.AUDITOR); try { EDiscoveryExport export = exports.save(new EDiscoveryExport(tenantId, actor, mapper.writeValueAsString(scope == null ? Map.of() : scope))); List<AuditEvent> organizationEvents = auditEvents.findByTenantIdOrderByOccurredAtAsc(tenantId, Pageable.ofSize(500)); List<TenantAuditEvent> events = tenantAudit.findTop500ByTenantIdOrderByOccurredAtAsc(tenantId); Map<String, Object> manifest = new LinkedHashMap<>(); manifest.put("schemaVersion", "1.0"); manifest.put("tenantId", tenantId); manifest.put("createdAt", Instant.now().toString()); manifest.put("scope", scope == null ? Map.of() : scope); manifest.put("organizationAuditEvents", organizationEvents.stream().map(e -> Map.of("id", e.getId(), "organizationId", e.getOrganizationId(), "sequence", e.getSequence(), "hash", e.getEventHash(), "action", e.getAction(), "occurredAt", e.getOccurredAt().toString())).toList()); manifest.put("tenantAuditEvents", events.stream().map(e -> Map.of("id", e.getId(), "action", e.getAction(), "entityType", e.getEntityType(), "entityId", Objects.toString(e.getEntityId(), ""), "occurredAt", e.getOccurredAt().toString())).toList()); byte[] bytes = mapper.writeValueAsBytes(manifest); String digest = sha256(bytes); String key = "tenants/" + tenantId + "/e-discovery/" + export.getId() + ".json"; ObjectStoragePort.StoredObject stored = storage.store(new ObjectStoragePort.Upload(key, "application/json", bytes, digest, Map.of("tenantId", tenantId.toString(), "exportId", export.getId().toString(), "sha256", digest))); Instant retentionUntil = Instant.now().plus(Duration.ofDays(365)); storage.applyRetentionLock(stored.bucket(), stored.key(), ObjectLockMode.COMPLIANCE, retentionUntil); export.markReady(stored.bucket(), stored.key(), digest, stored.sizeBytes(), retentionUntil); exports.save(export); append(tenantId, actor, "tenant.e_discovery_export.ready", "e_discovery_export", export.getId().toString(), Map.of("sha256", digest)); return exportView(export, storage.generatePresignedGetUrl(stored.bucket(), stored.key(), storageProperties.getPresignTtl()).toString()); } catch (Exception error) { throw new IllegalStateException("Unable to generate e-discovery export", error); }
  }
  public List<EDiscoveryExportView> exports(UUID tenantId, String actor, int page, int size) { requireRole(tenantId, actor, TenantRole.TENANT_ADMIN, TenantRole.COMPLIANCE_OFFICER, TenantRole.AUDITOR); return exports.findByTenantIdOrderByCreatedAtDesc(tenantId, Pageable.ofSize(Math.min(Math.max(size, 1), 100)).withPage(Math.max(page, 0))).stream().map(e -> exportView(e, e.getExportStatus() == EDiscoveryExportStatus.READY ? storage.generatePresignedGetUrl(e.getObjectBucket(), e.getObjectKey(), storageProperties.getPresignTtl()).toString() : null)).toList(); }
  private Tenant requireTenant(UUID id) { return tenants.findById(id).orElseThrow(() -> new IllegalArgumentException("Tenant not found")); }
  private void requireRole(UUID tenantId, String actor, TenantRole... roles) { requireTenant(tenantId); TenantMembership membership = memberships.findByTenantIdAndSubject(tenantId, actor).orElseThrow(() -> new SecurityException("Tenant membership is required")); if (Arrays.stream(roles).noneMatch(role -> role == membership.getTenantRole())) throw new SecurityException("Tenant role is insufficient"); }
  private void append(UUID tenantId, String actor, String action, String type, String entityId, Map<String, ?> payload) { try { tenantAudit.save(new TenantAuditEvent(tenantId, actor, action, type, entityId, mapper.writeValueAsString(payload))); } catch (Exception error) { throw new IllegalStateException("Unable to persist tenant audit event", error); } }
  private TenantView view(Tenant t) { return new TenantView(t.getId(), t.getSlug(), t.getDisplayName(), t.getTenantStatus(), t.getDataResidency(), t.getEncryptionKeyReference(), t.isLegalHoldEnabled()); }
  private MembershipView membershipView(TenantMembership m) { return new MembershipView(m.getId(), m.getSubject(), m.getTenantRole()); }
  private PermissionSetView permissionView(TenantPermissionSet p) { try { return new PermissionSetView(p.getId(), p.getPermissionKey(), p.getDisplayName(), mapper.readValue(p.getPermissionsJson(), new TypeReference<List<String>>() {})); } catch (Exception error) { throw new IllegalStateException("Corrupt permission set", error); } }
  private FederationView federationView(TenantFederationConfig f) { try { return new FederationView(f.getId(), f.getProtocol(), f.getIssuerUri(), f.getClientId(), f.getMetadataUri(), mapper.readValue(f.getClaimMappingJson(), new TypeReference<Map<String, Object>>() {}), f.isEnabled()); } catch (Exception error) { throw new IllegalStateException("Corrupt federation configuration", error); } }
  private ScimUserView scimUserView(ScimUser u) { try { return new ScimUserView(u.getId(), u.getExternalId(), u.getSubject(), u.getUserName(), u.getDisplayName(), u.isActive(), mapper.readValue(u.getAttributesJson(), new TypeReference<Map<String, Object>>() {})); } catch (Exception error) { throw new IllegalStateException("Corrupt SCIM user", error); } }
  private LegalHoldView legalHoldView(TenantLegalHold h) { return new LegalHoldView(h.getId(), h.getHoldKey(), h.getReason(), h.isActive(), h.getCreatedAt()); }
  private EDiscoveryExportView exportView(EDiscoveryExport e, String url) { return new EDiscoveryExportView(e.getId(), e.getExportStatus(), e.getManifestSha256(), e.getSizeBytes(), e.getCreatedAt(), e.getReadyAt(), url); }
  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
  private static void requireHttps(String value, String field) { if (value == null || !value.startsWith("https://")) throw new IllegalArgumentException(field + " must use HTTPS"); }
  private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
  private static String sha256(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception error) { throw new IllegalStateException(error); } }
}
