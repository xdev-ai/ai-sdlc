package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_permission_sets", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "permission_key"}))
public class TenantPermissionSet {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "permission_key", nullable = false, length = 100) private String permissionKey;
  @Column(name = "display_name", nullable = false, length = 160) private String displayName;
  @Column(name = "permissions_json", nullable = false, columnDefinition = "jsonb") private String permissionsJson;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  protected TenantPermissionSet() {}
  public TenantPermissionSet(UUID tenantId, String permissionKey, String displayName, String permissionsJson) { this.tenantId = tenantId; this.permissionKey = permissionKey; this.displayName = displayName; this.permissionsJson = permissionsJson; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public String getPermissionKey() { return permissionKey; } public String getDisplayName() { return displayName; } public String getPermissionsJson() { return permissionsJson; }
}
