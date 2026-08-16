package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_audit_events")
public class TenantAuditEvent {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "actor_subject", nullable = false, length = 200) private String actorSubject;
  @Column(nullable = false, length = 160) private String action;
  @Column(name = "entity_type", nullable = false, length = 100) private String entityType;
  @Column(name = "entity_id", length = 160) private String entityId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String payload;
  @Column(name = "occurred_at", nullable = false) private Instant occurredAt = Instant.now();
  protected TenantAuditEvent() {}
  public TenantAuditEvent(UUID tenantId, String actorSubject, String action, String entityType, String entityId, String payload) { this.tenantId = tenantId; this.actorSubject = actorSubject; this.action = action; this.entityType = entityType; this.entityId = entityId; this.payload = payload; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public String getActorSubject() { return actorSubject; } public String getAction() { return action; } public String getEntityType() { return entityType; } public String getEntityId() { return entityId; } public String getPayload() { return payload; } public Instant getOccurredAt() { return occurredAt; }
}
