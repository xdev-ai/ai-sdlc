package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "project_id", nullable = false) private UUID projectId;
  @Column(name = "channel_id", nullable = false) private UUID channelId;
  @Column(name = "event_type", nullable = false, length = 120) private String eventType;
  @Column(nullable = false, length = 300) private String subject;
  @Column(nullable = false, columnDefinition = "text") private String body;
  @Column(name = "idempotency_key", nullable = false, length = 180) private String idempotencyKey;
  @Column(name = "payload_sha256", nullable = false, columnDefinition = "char(64)") private String payloadSha256;
  @Column(name = "recipient_fingerprint", nullable = false, columnDefinition = "char(64)") private String recipientFingerprint;
  @Enumerated(EnumType.STRING) @Column(name = "delivery_status", nullable = false, length = 30) private DomainTypes.NotificationDeliveryStatus deliveryStatus = DomainTypes.NotificationDeliveryStatus.PENDING;
  @Column(nullable = false) private int attempts;
  @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt = Instant.now();
  @Column(name = "last_attempt_at") private Instant lastAttemptAt;
  @Column(name = "delivered_at") private Instant deliveredAt;
  @Column(name = "terminal_error_code", length = 120) private String terminalErrorCode;
  @Version private long version;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
  protected NotificationDelivery() {}
  public NotificationDelivery(UUID projectId, UUID channelId, String eventType, String subject, String body, String idempotencyKey, String payloadSha256, String recipientFingerprint) { this.projectId = projectId; this.channelId = channelId; this.eventType = eventType; this.subject = subject; this.body = body; this.idempotencyKey = idempotencyKey; this.payloadSha256 = payloadSha256; this.recipientFingerprint = recipientFingerprint; }
  public void claim(Instant now) { deliveryStatus = DomainTypes.NotificationDeliveryStatus.SENDING; attempts++; lastAttemptAt = now; updatedAt = now; }
  public void delivered(Instant now) { deliveryStatus = DomainTypes.NotificationDeliveryStatus.DELIVERED; deliveredAt = now; terminalErrorCode = null; updatedAt = now; }
  public void retry(Instant nextAttemptAt, String errorCode) { deliveryStatus = DomainTypes.NotificationDeliveryStatus.RETRY_SCHEDULED; this.nextAttemptAt = nextAttemptAt; terminalErrorCode = errorCode; updatedAt = Instant.now(); }
  public void fail(String errorCode) { deliveryStatus = DomainTypes.NotificationDeliveryStatus.FAILED; terminalErrorCode = errorCode; updatedAt = Instant.now(); }
  public UUID getId() { return id; } public UUID getProjectId() { return projectId; } public UUID getChannelId() { return channelId; } public String getEventType() { return eventType; } public String getSubject() { return subject; } public String getBody() { return body; } public String getIdempotencyKey() { return idempotencyKey; } public String getPayloadSha256() { return payloadSha256; } public String getRecipientFingerprint() { return recipientFingerprint; } public DomainTypes.NotificationDeliveryStatus getDeliveryStatus() { return deliveryStatus; } public int getAttempts() { return attempts; } public Instant getNextAttemptAt() { return nextAttemptAt; } public Instant getLastAttemptAt() { return lastAttemptAt; } public Instant getDeliveredAt() { return deliveredAt; } public String getTerminalErrorCode() { return terminalErrorCode; } public Instant getCreatedAt() { return createdAt; }
}
