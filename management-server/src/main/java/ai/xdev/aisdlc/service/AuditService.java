package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.AuditEvent;
import ai.xdev.aisdlc.repo.Repositories.AuditEventRepository;
import ai.xdev.aisdlc.repo.Repositories.OrganizationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
  private static final String GENESIS_HASH = "0".repeat(64);
  private final OrganizationRepository organizations;
  private final AuditEventRepository events;

  public AuditService(OrganizationRepository organizations, AuditEventRepository events) {
    this.organizations = organizations;
    this.events = events;
  }

  @Transactional
  public AuditEvent append(UUID organizationId, UUID projectId, String actor, String action, String entityType, String entityId, String payload) {
    organizations.lockById(organizationId).orElseThrow(() -> new IllegalArgumentException("Organization not found"));
    AuditEvent previous = events.findTopByOrganizationIdOrderBySequenceDesc(organizationId).orElse(null);
    long sequence = previous == null ? 1 : previous.getSequence() + 1;
    String previousHash = previous == null ? GENESIS_HASH : previous.getEventHash();
    String canonical = String.join("|", organizationId.toString(), String.valueOf(projectId), actor, action, entityType, String.valueOf(entityId), payload == null ? "{}" : payload, String.valueOf(sequence), previousHash);
    return events.save(new AuditEvent(organizationId, projectId, actor, action, entityType, entityId, payload, sequence, previousHash, sha256(canonical)));
  }

  private String sha256(String input) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException("Unable to hash audit event", error); }
  }
}

