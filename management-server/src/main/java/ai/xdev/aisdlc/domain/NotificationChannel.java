package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_channels")
public class NotificationChannel {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Enumerated(EnumType.STRING) @Column(name = "channel_type", nullable = false, length = 30) private DomainTypes.NotificationChannelType channelType;
  @Column(nullable = false, length = 120) private String name;
  @Column(name = "destination_ciphertext", nullable = false, columnDefinition = "text") private String destinationCiphertext;
  @Column(name = "secret_ciphertext", columnDefinition = "text") private String secretCiphertext;
  @Column(nullable = false) private boolean enabled = true;
  @Column(name = "created_by", nullable = false, length = 200) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  protected NotificationChannel() {}
  public NotificationChannel(UUID projectId, DomainTypes.NotificationChannelType channelType, String name, String destinationCiphertext, String secretCiphertext, String createdBy) { this.projectId = projectId; this.channelType = channelType; this.name = name; this.destinationCiphertext = destinationCiphertext; this.secretCiphertext = secretCiphertext; this.createdBy = createdBy; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; this.updatedAt = Instant.now(); }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public DomainTypes.NotificationChannelType getChannelType() { return channelType; } public String getName() { return name; } public String getDestinationCiphertext() { return destinationCiphertext; } public String getSecretCiphertext() { return secretCiphertext; } public boolean isEnabled() { return enabled; } public String getCreatedBy() { return createdBy; } public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
