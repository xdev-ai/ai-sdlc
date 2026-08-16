package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.config.NotificationProperties;
import ai.xdev.aisdlc.domain.*;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.repo.Repositories.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class NotificationService {
  static final String OUTBOUND_WEBHOOK_SCHEMA_VERSION = "1.0";
  record Attempt(boolean delivered, boolean retryable, Integer httpStatus, String code) {}
  record DispatchCandidate(UUID deliveryId, UUID channelId, NotificationChannelType channelType, String destination, String secret, String subject, String body, String eventType, String payloadSha256, int attempt) {}
  private final ProjectAccessService access; private final NotificationChannelRepository channels; private final NotificationDeliveryRepository deliveries; private final NotificationDeliveryReceiptRepository receipts; private final AuditService audit; private final NotificationSecretCipher cipher; private final NotificationProperties properties; private final ObjectMapper mapper; private final ObjectProvider<JavaMailSender> mailSender; private final TransactionTemplate transactions; private final RestClient http = RestClient.create();
  private final ObjectProvider<ChaosFaultRegistry> chaosFaults;
  public NotificationService(ProjectAccessService access, NotificationChannelRepository channels, NotificationDeliveryRepository deliveries, NotificationDeliveryReceiptRepository receipts, AuditService audit, NotificationSecretCipher cipher, NotificationProperties properties, ObjectMapper mapper, ObjectProvider<JavaMailSender> mailSender, PlatformTransactionManager transactionManager) { this(access, channels, deliveries, receipts, audit, cipher, properties, mapper, mailSender, transactionManager, null); }
  @org.springframework.beans.factory.annotation.Autowired public NotificationService(ProjectAccessService access, NotificationChannelRepository channels, NotificationDeliveryRepository deliveries, NotificationDeliveryReceiptRepository receipts, AuditService audit, NotificationSecretCipher cipher, NotificationProperties properties, ObjectMapper mapper, ObjectProvider<JavaMailSender> mailSender, PlatformTransactionManager transactionManager, ObjectProvider<ChaosFaultRegistry> chaosFaults) { this.access = access; this.channels = channels; this.deliveries = deliveries; this.receipts = receipts; this.audit = audit; this.cipher = cipher; this.properties = properties; this.mapper = mapper; this.mailSender = mailSender; this.transactions = new TransactionTemplate(transactionManager); this.chaosFaults = chaosFaults; }

  @Transactional
  public UUID createChannel(UUID projectId, String actor, NotificationChannelType type, String name, String destination, String sharedSecret) {
    Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    validateDestination(type, destination, sharedSecret);
    NotificationChannel channel = channels.save(new NotificationChannel(projectId, type, name.trim(), cipher.encrypt(destination.trim()), sharedSecret == null || sharedSecret.isBlank() ? null : cipher.encrypt(sharedSecret.trim()), actor));
    audit.append(project.getOrganizationId(), projectId, actor, "NOTIFICATION_CHANNEL_CREATED", "notification_channel", channel.getId().toString(), "{\"type\":\"" + type + "\",\"destinationFingerprint\":\"" + fingerprint(destination) + "\"}");
    return channel.getId();
  }
  @Transactional
  public void setChannelEnabled(UUID projectId, UUID channelId, boolean enabled, String actor) {
    Project project = access.requireMembership(projectId, actor, MembershipRole.OWNER);
    NotificationChannel channel = channels.findByIdAndProjectId(channelId, projectId).orElseThrow(() -> new IllegalArgumentException("Notification channel not found"));
    channel.setEnabled(enabled);
    audit.append(project.getOrganizationId(), projectId, actor, enabled ? "NOTIFICATION_CHANNEL_ENABLED" : "NOTIFICATION_CHANNEL_DISABLED", "notification_channel", channelId.toString(), "{}");
  }
  @Transactional(readOnly = true)
  public List<Map<String, Object>> listChannels(UUID projectId, String actor) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.DEVELOPER, MembershipRole.REVIEWER, MembershipRole.VIEWER);
    return channels.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::channelView).toList();
  }
  @Transactional(readOnly = true)
  public ai.xdev.aisdlc.web.PageResponse<Map<String, Object>> listDeliveries(UUID projectId, String actor, int page, int size) {
    access.requireMembership(projectId, actor, MembershipRole.OWNER, MembershipRole.REVIEWER);
    return ai.xdev.aisdlc.web.PageResponse.from(deliveries.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)))).map(this::deliveryView));
  }
  @Transactional
  public void queueProjectNotification(UUID projectId, String eventType, String subject, String body, String idempotencyKey) {
    if (subject.length() > 300 || body.length() > 24_000) throw new IllegalArgumentException("Notification message exceeds transport-safe size");
    for (NotificationChannel channel : channels.findByProjectIdAndEnabledTrueOrderByCreatedAtAsc(projectId)) {
      if (deliveries.findByChannelIdAndIdempotencyKey(channel.getId(), idempotencyKey).isPresent()) continue;
      String payloadSha = sha256(eventType + "\n" + subject + "\n" + body);
      deliveries.save(new NotificationDelivery(projectId, channel.getId(), eventType, subject, body, idempotencyKey, payloadSha, fingerprint(cipher.decrypt(channel.getDestinationCiphertext()))));
    }
  }
  public int dispatchEligible() {
    Instant now = Instant.now();
    List<UUID> ids = deliveries.findEligibleIds(Set.of(NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.RETRY_SCHEDULED), now, now.minus(java.time.Duration.ofMinutes(10)), PageRequest.of(0, Math.max(1, Math.min(100, properties.getBatchSize()))));
    for (UUID id : ids) {
      DispatchCandidate candidate = transactions.execute(status -> claim(id));
      if (candidate == null) continue;
      Attempt attempt = send(candidate);
      transactions.executeWithoutResult(status -> complete(id, attempt));
    }
    return ids.size();
  }
  private DispatchCandidate claim(UUID deliveryId) {
    NotificationDelivery delivery = deliveries.lockById(deliveryId).orElse(null);
    if (delivery == null || !eligible(delivery, Instant.now())) return null;
    NotificationChannel channel = channels.findById(delivery.getChannelId()).orElse(null);
    Instant now = Instant.now();
    delivery.claim(now);
    if (channel == null || !channel.isEnabled()) { complete(delivery, new Attempt(false, false, null, "CHANNEL_DISABLED")); return null; }
    return new DispatchCandidate(delivery.getId(), channel.getId(), channel.getChannelType(), cipher.decrypt(channel.getDestinationCiphertext()), channel.getSecretCiphertext() == null ? null : cipher.decrypt(channel.getSecretCiphertext()), delivery.getSubject(), delivery.getBody(), delivery.getEventType(), delivery.getPayloadSha256(), delivery.getAttempts());
  }
  private void complete(UUID deliveryId, Attempt attempt) {
    NotificationDelivery delivery = deliveries.lockById(deliveryId).orElse(null);
    if (delivery == null || delivery.getDeliveryStatus() != NotificationDeliveryStatus.SENDING) return;
    complete(delivery, attempt);
  }
  private void complete(NotificationDelivery delivery, Attempt attempt) {
    receipts.save(new NotificationDeliveryReceipt(delivery.getId(), delivery.getAttempts(), attempt.delivered ? "DELIVERED" : attempt.retryable ? "RETRY_SCHEDULED" : "FAILED", attempt.httpStatus, attempt.code, delivery.getPayloadSha256()));
    if (attempt.delivered) delivery.delivered(Instant.now());
    else if (attempt.retryable && delivery.getAttempts() < properties.getMaxAttempts()) delivery.retry(Instant.now().plus(backoff(delivery.getAttempts())), attempt.code);
    else delivery.fail(attempt.code);
  }
  private Attempt send(DispatchCandidate candidate) {
    try {
      if (chaosFaults != null) chaosFaults.ifAvailable(registry -> registry.check(ChaosFaultRegistry.Component.NOTIFICATION_PROVIDER));
      if (candidate.channelType() == NotificationChannelType.EMAIL) return sendEmail(candidate.destination(), candidate);
      return sendWebhook(candidate);
    // A provider outage is retryable transport, not a configuration error: the approval outcome must stay unchanged.
    } catch (ChaosFaultRegistry.ChaosFaultException injected) { return new Attempt(false, true, null, "NETWORK_ERROR"); }
      catch (ResourceAccessException error) { return new Attempt(false, true, null, "NETWORK_ERROR"); }
      catch (RestClientResponseException error) { int status = error.getStatusCode().value(); return new Attempt(false, status == 429 || status >= 500, status, "HTTP_" + status); }
      catch (Exception error) { return new Attempt(false, false, null, "DELIVERY_CONFIGURATION_ERROR"); }
  }
  private Attempt sendEmail(String destination, DispatchCandidate candidate) {
    if (properties.getFromAddress() == null || properties.getFromAddress().isBlank() || mailSender.getIfAvailable() == null) return new Attempt(false, false, null, "EMAIL_NOT_CONFIGURED");
    SimpleMailMessage message = new SimpleMailMessage(); message.setFrom(properties.getFromAddress()); message.setTo(destination); message.setSubject(candidate.subject()); message.setText(candidate.body() + "\n\nAI-SDLC delivery: " + candidate.deliveryId());
    mailSender.getObject().send(message); return new Attempt(true, false, 202, null);
  }
  private Attempt sendWebhook(DispatchCandidate candidate) throws JsonProcessingException {
    Map<String, Object> payload = webhookPayload(candidate, Instant.now());
    String json = mapper.writeValueAsString(payload);
    RestClient.RequestBodySpec request = http.post().uri(candidate.destination()).header("Content-Type", "application/json");
    if (candidate.channelType() == NotificationChannelType.GENERIC_WEBHOOK) {
      String timestamp = Instant.now().toString();
      request.header("X-AISDLC-Delivery", candidate.deliveryId().toString()).header("X-AISDLC-Timestamp", timestamp).header("X-AISDLC-Event-Schema", OUTBOUND_WEBHOOK_SCHEMA_VERSION).header("X-AISDLC-Signature-256", "sha256=" + hmac(timestamp + "." + json, candidate.secret()));
    }
    int status = request.body(json).retrieve().toBodilessEntity().getStatusCode().value();
    return new Attempt(status >= 200 && status < 300, status == 429 || status >= 500, status, status >= 200 && status < 300 ? null : "HTTP_" + status);
  }
  static Map<String, Object> webhookPayload(DispatchCandidate candidate, Instant occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (candidate.channelType() == NotificationChannelType.GENERIC_WEBHOOK) {
      payload.put("specversion", "1.0");
      payload.put("schemaVersion", OUTBOUND_WEBHOOK_SCHEMA_VERSION);
      payload.put("id", candidate.deliveryId().toString());
      payload.put("type", "ai.xdev.aisdlc." + candidate.eventType());
      payload.put("source", "urn:ai-sdlc:notification-channel:" + candidate.channelId());
      payload.put("time", occurredAt.toString());
      payload.put("datacontenttype", "application/json");
      payload.put("data", Map.of("subject", candidate.subject(), "text", candidate.body(), "payloadSha256", candidate.payloadSha256()));
      return payload;
    }
    payload.put("eventType", candidate.eventType()); payload.put("deliveryId", candidate.deliveryId()); payload.put("subject", candidate.subject()); payload.put("text", candidate.body()); payload.put("occurredAt", occurredAt.toString());
    return payload;
  }
  private void validateDestination(NotificationChannelType type, String destination, String secret) {
    if (destination == null || destination.isBlank() || destination.length() > 2_000) throw new IllegalArgumentException("Notification destination is required");
    if (type == NotificationChannelType.EMAIL && !destination.contains("@")) throw new IllegalArgumentException("Email channel requires an email destination");
    if (type != NotificationChannelType.EMAIL && !destination.startsWith("https://")) throw new IllegalArgumentException("Webhook destination must use HTTPS");
    if (type == NotificationChannelType.GENERIC_WEBHOOK && (secret == null || secret.isBlank())) throw new IllegalArgumentException("Generic webhook requires a signing secret");
  }
  private Map<String, Object> channelView(NotificationChannel channel) { Map<String, Object> view = new LinkedHashMap<>(); view.put("id", channel.getId()); view.put("type", channel.getChannelType()); view.put("name", channel.getName()); view.put("enabled", channel.isEnabled()); view.put("createdBy", channel.getCreatedBy()); view.put("createdAt", channel.getCreatedAt()); view.put("destinationFingerprint", fingerprint(cipher.decrypt(channel.getDestinationCiphertext()))); return view; }
  private Map<String, Object> deliveryView(NotificationDelivery delivery) { Map<String, Object> view = new LinkedHashMap<>(); view.put("id", delivery.getId()); view.put("eventType", delivery.getEventType()); view.put("subject", delivery.getSubject()); view.put("status", delivery.getDeliveryStatus()); view.put("attempts", delivery.getAttempts()); view.put("nextAttemptAt", delivery.getNextAttemptAt()); view.put("deliveredAt", delivery.getDeliveredAt()); view.put("errorCode", delivery.getTerminalErrorCode()); view.put("payloadSha256", delivery.getPayloadSha256()); view.put("createdAt", delivery.getCreatedAt()); return view; }
  private java.time.Duration backoff(int attempts) { long seconds = Math.min(3_600L, properties.getRetryBaseDelay().toSeconds() * (1L << Math.min(6, attempts - 1))); return java.time.Duration.ofSeconds(seconds); }
  private String hmac(String value, String secret) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException("Unable to sign outbound webhook", error); } }
  private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }
  private String fingerprint(String value) { return sha256(value); }
  private boolean eligible(NotificationDelivery delivery, Instant now) { return (delivery.getDeliveryStatus() == NotificationDeliveryStatus.PENDING || delivery.getDeliveryStatus() == NotificationDeliveryStatus.RETRY_SCHEDULED) && !delivery.getNextAttemptAt().isAfter(now) || (delivery.getDeliveryStatus() == NotificationDeliveryStatus.SENDING && delivery.getLastAttemptAt() != null && delivery.getLastAttemptAt().isBefore(now.minus(java.time.Duration.ofMinutes(10)))); }
}
