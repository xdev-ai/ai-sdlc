package ai.xdev.aisdlc.service;

import static org.assertj.core.api.Assertions.assertThat;

import ai.xdev.aisdlc.domain.DomainTypes.NotificationChannelType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationWebhookContractTest {
  @Test
  void genericWebhookUsesVersionedCloudEventsCompatibleEnvelope() {
    UUID deliveryId = UUID.randomUUID();
    NotificationService.DispatchCandidate candidate = new NotificationService.DispatchCandidate(deliveryId, UUID.randomUUID(), NotificationChannelType.GENERIC_WEBHOOK, "https://receiver.example/events", "secret", "Approval required", "A human decision is pending.", "approval.requested", "abc", 1);

    var payload = NotificationService.webhookPayload(candidate, Instant.parse("2026-08-16T00:00:00Z"));

    assertThat(payload).containsEntry("specversion", "1.0").containsEntry("schemaVersion", "1.0").containsEntry("id", deliveryId.toString()).containsEntry("type", "ai.xdev.aisdlc.approval.requested");
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> data = (java.util.Map<String, Object>) payload.get("data");
    assertThat(data).containsEntry("payloadSha256", "abc");
  }
}
