package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_legal_holds", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "hold_key"}))
public class TenantLegalHold {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "hold_key", nullable = false, length = 120) private String holdKey;
  @Column(nullable = false, columnDefinition = "text") private String reason;
  @Column(nullable = false) private boolean active = true;
  @Column(name = "created_by", nullable = false, length = 200) private String createdBy;
  @Column(name = "released_by", length = 200) private String releasedBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "released_at") private Instant releasedAt;
  protected TenantLegalHold() {}
  public TenantLegalHold(UUID tenantId, String holdKey, String reason, String createdBy) { this.tenantId = tenantId; this.holdKey = holdKey; this.reason = reason; this.createdBy = createdBy; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public String getHoldKey() { return holdKey; } public String getReason() { return reason; } public boolean isActive() { return active; } public Instant getCreatedAt() { return createdAt; }
  public void release(String subject) { active = false; releasedBy = subject; releasedAt = Instant.now(); }
}
