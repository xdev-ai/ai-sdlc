package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scim_users")
public class ScimUser {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "external_id", length = 200) private String externalId;
  @Column(nullable = false, length = 200) private String subject;
  @Column(name = "user_name", nullable = false, length = 300) private String userName;
  @Column(name = "display_name", length = 300) private String displayName;
  @Column(nullable = false) private boolean active = true;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "attributes_json", nullable = false, columnDefinition = "jsonb") private String attributesJson;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  protected ScimUser() {}
  public ScimUser(UUID tenantId, String externalId, String subject, String userName, String displayName, boolean active, String attributesJson) { this.tenantId = tenantId; this.externalId = externalId; this.subject = subject; this.userName = userName; this.displayName = displayName; this.active = active; this.attributesJson = attributesJson; }
  public UUID getId() { return id; } public UUID getTenantId() { return tenantId; } public String getExternalId() { return externalId; } public String getSubject() { return subject; } public String getUserName() { return userName; } public String getDisplayName() { return displayName; } public boolean isActive() { return active; } public String getAttributesJson() { return attributesJson; }
  public void update(String userName, String displayName, boolean active, String attributesJson) { this.userName = userName; this.displayName = displayName; this.active = active; this.attributesJson = attributesJson; this.updatedAt = Instant.now(); }
}
