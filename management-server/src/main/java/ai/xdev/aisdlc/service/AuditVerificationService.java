package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.AuditEvent;
import ai.xdev.aisdlc.repo.Repositories.AuditEventRepository;
import ai.xdev.aisdlc.web.PageRequests;
import ai.xdev.aisdlc.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

@Service
public class AuditVerificationService {
  private static final String GENESIS_HASH = "0".repeat(64);
  private final AuditEventRepository events;

  public AuditVerificationService(AuditEventRepository events) { this.events = events; }

  public PageResponse<AuditEventView> list(UUID organizationId, String action, int page, int size) {
    Page<AuditEvent> result = action == null || action.isBlank()
        ? events.findByOrganizationId(organizationId, PageRequests.of(page, size, "sequence,desc", "sequence", "occurredAt", "action"))
        : events.findByOrganizationIdAndActionContainingIgnoreCase(organizationId, action, PageRequests.of(page, size, "sequence,desc", "sequence", "occurredAt", "action"));
    return PageResponse.from(result.map(this::view));
  }

  public VerificationView verify(UUID organizationId) {
    String expectedPrevious = GENESIS_HASH;
    long expectedSequence = 1;
    long verified = 0;
    for (AuditEvent event : events.findByOrganizationIdOrderBySequenceAsc(organizationId)) {
      String canonical = String.join("|", organizationId.toString(), String.valueOf(event.getProjectId()), event.getActorSubject(), event.getAction(), event.getEntityType(), String.valueOf(event.getEntityId()), event.getPayload() == null ? "{}" : event.getPayload(), String.valueOf(event.getSequence()), event.getPreviousHash());
      String actual = sha256(canonical);
      if (event.getSequence() != expectedSequence || !expectedPrevious.equals(event.getPreviousHash()) || !actual.equals(event.getEventHash())) {
        return new VerificationView(false, verified, event.getSequence(), "Hash chain mismatch at sequence " + event.getSequence());
      }
      expectedPrevious = event.getEventHash();
      expectedSequence++;
      verified++;
    }
    return new VerificationView(true, verified, null, "Audit hash chain is intact");
  }

  private AuditEventView view(AuditEvent event) {
    return new AuditEventView(event.getSequence(), event.getProjectId(), event.getActorSubject(), event.getAction(), event.getEntityType(), event.getEntityId(), event.getPayload(), event.getOccurredAt(), event.getPreviousHash(), event.getEventHash());
  }

  private String sha256(String input) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException("Unable to verify audit hash chain", error); }
  }

  public record AuditEventView(long sequence, UUID projectId, String actorSubject, String action, String entityType, String entityId, String payload, java.time.Instant occurredAt, String previousHash, String eventHash) {}
  public record VerificationView(boolean intact, long verifiedEvents, Long firstInvalidSequence, String message) {}
}
