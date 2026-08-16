package ai.xdev.aisdlc.scm;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GitLab webhooks. GitLab authenticates with a shared secret token rather than an HMAC signature, so verification is
 * a constant-time token comparison; there is no payload binding to check.
 */
@Component
public class GitLabConnector implements ScmConnector {
  static final String KEY = "gitlab";
  private final ScmConnectorProperties.Connector config;

  public GitLabConnector(ScmConnectorProperties properties) {
    this.config = properties.forKey(KEY);
    if (config.getSignatureHeader().isBlank()) config.setSignatureHeader("X-Gitlab-Token");
    if (config.getEventHeader().isBlank()) config.setEventHeader("X-Gitlab-Event");
    if (config.getDeliveryHeader().isBlank()) config.setDeliveryHeader("X-Gitlab-Event-UUID");
  }

  @Override public ScmProvider provider() { return ScmProvider.GITLAB; }
  @Override public boolean isConfigured() { return config.isConfigured(); }

  @Override
  public boolean verify(byte[] payload, Map<String, String> headers) {
    if (!isConfigured()) return false;
    return ScmConnectorSupport.secretEquals(config.getSecret(), ScmConnectorSupport.header(headers, config.getSignatureHeader()));
  }

  @Override
  public Optional<InboundEvent> parse(JsonNode payload, Map<String, String> headers) {
    String event = ScmConnectorSupport.header(headers, config.getEventHeader());
    ScmEventType type = switch (event == null ? "" : event) {
      case "Push Hook", "Tag Push Hook" -> ScmEventType.PUSH;
      case "Merge Request Hook" -> ScmEventType.PULL_REQUEST;
      case "Pipeline Hook", "Job Hook" -> ScmEventType.WORKFLOW_RUN;
      case "Release Hook" -> ScmEventType.RELEASE;
      default -> null;
    };
    if (type == null) return Optional.empty();
    JsonNode attributes = payload.path("object_attributes");
    String repository = payload.path("project").path("path_with_namespace").asText(null);
    if (repository == null || repository.isBlank()) return Optional.empty();
    Integer mergeRequest = attributes.hasNonNull("iid") ? attributes.path("iid").asInt() : null;
    String deliveryId = ScmConnectorSupport.header(headers, config.getDeliveryHeader());
    return Optional.of(new InboundEvent(
        deliveryId == null || deliveryId.isBlank() ? ScmConnectorSupport.derivedDeliveryId(KEY, payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) : deliveryId,
        type,
        repository,
        attributes.path("action").asText(null),
        firstNonBlank(payload.path("ref").asText(null), attributes.path("source_branch").asText(null)),
        firstNonBlank(payload.path("checkout_sha").asText(null), attributes.path("last_commit").path("id").asText(null)),
        mergeRequest,
        null));
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) return first;
    return second == null || second.isBlank() ? null : second;
  }
}
