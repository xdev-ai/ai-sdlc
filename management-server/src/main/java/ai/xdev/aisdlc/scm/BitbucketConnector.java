package ai.xdev.aisdlc.scm;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Bitbucket Cloud webhooks, verified by HMAC-SHA256 over the raw payload. */
@Component
public class BitbucketConnector implements ScmConnector {
  static final String KEY = "bitbucket";
  private final ScmConnectorProperties.Connector config;

  public BitbucketConnector(ScmConnectorProperties properties) {
    this.config = properties.forKey(KEY);
    if (config.getSignatureHeader().isBlank()) config.setSignatureHeader("X-Hub-Signature");
    if (config.getEventHeader().isBlank()) config.setEventHeader("X-Event-Key");
    if (config.getDeliveryHeader().isBlank()) config.setDeliveryHeader("X-Request-UUID");
  }

  @Override public ScmProvider provider() { return ScmProvider.BITBUCKET; }
  @Override public boolean isConfigured() { return config.isConfigured(); }

  @Override
  public boolean verify(byte[] payload, Map<String, String> headers) {
    if (!isConfigured()) return false;
    return ScmConnectorSupport.hmacSha256Matches(config.getSecret(), payload,
        ScmConnectorSupport.header(headers, config.getSignatureHeader()), "sha256=");
  }

  @Override
  public Optional<InboundEvent> parse(JsonNode payload, Map<String, String> headers) {
    String event = ScmConnectorSupport.header(headers, config.getEventHeader());
    if (event == null || event.isBlank()) return Optional.empty();
    ScmEventType type;
    if (event.startsWith("repo:push")) type = ScmEventType.PUSH;
    else if (event.startsWith("pullrequest:")) type = ScmEventType.PULL_REQUEST;
    else if (event.startsWith("repo:commit_status")) type = ScmEventType.CHECK_RUN;
    else return Optional.empty();

    String repository = payload.path("repository").path("full_name").asText(null);
    if (repository == null || repository.isBlank()) return Optional.empty();
    JsonNode pullRequest = payload.path("pullrequest");
    Integer number = pullRequest.hasNonNull("id") ? pullRequest.path("id").asInt() : null;
    String branch = pullRequest.path("source").path("branch").path("name").asText(null);
    String commit = pullRequest.path("source").path("commit").path("hash").asText(null);
    if (type == ScmEventType.PUSH) {
      JsonNode change = payload.path("push").path("changes").path(0).path("new");
      branch = change.path("name").asText(null);
      commit = change.path("target").path("hash").asText(null);
    }
    String deliveryId = ScmConnectorSupport.header(headers, config.getDeliveryHeader());
    return Optional.of(new InboundEvent(
        deliveryId == null || deliveryId.isBlank() ? ScmConnectorSupport.derivedDeliveryId(KEY, payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) : deliveryId,
        type, repository, event, branch, commit, number, null));
  }
}
