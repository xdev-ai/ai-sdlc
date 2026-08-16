package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events", uniqueConstraints = { @UniqueConstraint(name = "audit_org_sequence_uq", columnNames = {"organization_id", "sequence"}), @UniqueConstraint(name = "audit_hash_uq", columnNames = "event_hash") })
public class AuditEvent {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "organization_id", nullable = false) private UUID organizationId;
  @Column(name = "project_id") private UUID projectId;
  @Column(name = "actor_subject", nullable = false, length = 120) private String actorSubject;
  @Column(nullable = false, length = 160) private String action;
  @Column(name = "entity_type", nullable = false, length = 100) private String entityType;
  @Column(name = "entity_id", length = 160) private String entityId;
  @Column(columnDefinition = "jsonb") private String payload;
  @Column(nullable = false) private long sequence;
  @Column(name = "previous_hash", nullable = false, length = 64) private String previousHash;
  @Column(name = "event_hash", nullable = false, length = 64) private String eventHash;
  @Column(nullable = false) private Instant occurredAt = Instant.now();
  protected AuditEvent() {}
  public AuditEvent(UUID organizationId, UUID projectId, String actorSubject, String action, String entityType, String entityId, String payload, long sequence, String previousHash, String eventHash) { this.organizationId = organizationId; this.projectId = projectId; this.actorSubject = actorSubject; this.action = action; this.entityType = entityType; this.entityId = entityId; this.payload = payload; this.sequence = sequence; this.previousHash = previousHash; this.eventHash = eventHash; }
  public UUID getId() { return id; }
  public long getSequence() { return sequence; }
  public String getEventHash() { return eventHash; }
  public UUID getOrganizationId() { return organizationId; }
  public UUID getProjectId() { return projectId; }
  public String getActorSubject() { return actorSubject; }
  public Instant getOccurredAt() { return occurredAt; }
  public String getAction() { return action; }
  public String getEntityType() { return entityType; }
  public String getEntityId() { return entityId; }
  public String getPayload() { return payload; }
  public String getPreviousHash() { return previousHash; }
}
