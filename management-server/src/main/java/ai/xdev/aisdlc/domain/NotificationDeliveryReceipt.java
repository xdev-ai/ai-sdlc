package ai.xdev.aisdlc.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery_receipts")
public class NotificationDeliveryReceipt {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "delivery_id", nullable = false) private UUID deliveryId;
  @Column(name = "attempt_number", nullable = false) private int attemptNumber;
  @Column(nullable = false, length = 30) private String outcome;
  @Column(name = "http_status") private Integer httpStatus;
  @Column(name = "error_code", length = 120) private String errorCode;
  @Column(name = "payload_sha256", nullable = false, columnDefinition = "char(64)") private String payloadSha256;
  @Column(name = "delivery_timestamp", nullable = false) private Instant deliveryTimestamp = Instant.now();
  protected NotificationDeliveryReceipt() {}
  public NotificationDeliveryReceipt(UUID deliveryId, int attemptNumber, String outcome, Integer httpStatus, String errorCode, String payloadSha256) { this.deliveryId = deliveryId; this.attemptNumber = attemptNumber; this.outcome = outcome; this.httpStatus = httpStatus; this.errorCode = errorCode; this.payloadSha256 = payloadSha256; }
  public UUID getId() { return id; } public UUID getDeliveryId() { return deliveryId; } public int getAttemptNumber() { return attemptNumber; } public String getOutcome() { return outcome; } public Integer getHttpStatus() { return httpStatus; } public String getErrorCode() { return errorCode; } public String getPayloadSha256() { return payloadSha256; } public Instant getDeliveryTimestamp() { return deliveryTimestamp; }
}
