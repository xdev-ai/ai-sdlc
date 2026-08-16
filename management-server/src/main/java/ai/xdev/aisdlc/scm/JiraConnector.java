package ai.xdev.aisdlc.scm;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Jira work-management webhooks, verified by HMAC-SHA256 over the raw payload.
 *
 * <p>Jira is a work-management provider, not an SCM: its events carry an issue key rather than a repository. The
 * issue key is recorded as the correlation key and as the link name a project registers, so a Jira event lands in
 * the same event ledger and traceability graph as a pull request without a separate pipeline.
 */
@Component
public class JiraConnector implements ScmConnector {
  static final String KEY = "jira";
  private final ScmConnectorProperties.Connector config;

  public JiraConnector(ScmConnectorProperties properties) {
    this.config = properties.forKey(KEY);
    if (config.getSignatureHeader().isBlank()) config.setSignatureHeader("X-Hub-Signature");
  }

  @Override public ScmProvider provider() { return ScmProvider.JIRA; }
  @Override public boolean isConfigured() { return config.isConfigured(); }

  @Override
  public boolean verify(byte[] payload, Map<String, String> headers) {
    if (!isConfigured()) return false;
    return ScmConnectorSupport.hmacSha256Matches(config.getSecret(), payload,
        ScmConnectorSupport.header(headers, config.getSignatureHeader()), "sha256=");
  }

  @Override
  public Optional<InboundEvent> parse(JsonNode payload, Map<String, String> headers) {
    String event = payload.path("webhookEvent").asText(null);
    if (event == null || !event.startsWith("jira:issue_")) return Optional.empty();
    String issueKey = payload.path("issue").path("key").asText(null);
    if (issueKey == null || issueKey.isBlank()) return Optional.empty();
    String projectKey = payload.path("issue").path("fields").path("project").path("key").asText(null);
    String linkName = projectKey == null || projectKey.isBlank() ? issueKey : projectKey;
    String deliveryId = payload.hasNonNull("id") ? payload.path("id").asText() : null;
    return Optional.of(new InboundEvent(
        deliveryId == null || deliveryId.isBlank() ? ScmConnectorSupport.derivedDeliveryId(KEY, payload.toString().getBytes(StandardCharsets.UTF_8)) : deliveryId,
        ScmEventType.WORK_ITEM, linkName, event, null, null, null, issueKey));
  }
}
