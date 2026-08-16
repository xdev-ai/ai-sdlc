package ai.xdev.aisdlc.scm;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Azure DevOps service hooks. They authenticate with HTTP Basic rather than a payload signature, so the secret is
 * the expected {@code Authorization} value compared in constant time.
 *
 * <p>Because Basic auth does not bind the credential to the body, a replayed request is indistinguishable from a
 * genuine retry. Shared idempotency on the delivery identifier is what makes that safe, which is why this connector
 * derives a deterministic identifier when the payload carries no {@code id}.
 */
@Component
public class AzureDevOpsConnector implements ScmConnector {
  static final String KEY = "azure-devops";
  private final ScmConnectorProperties.Connector config;

  public AzureDevOpsConnector(ScmConnectorProperties properties) {
    this.config = properties.forKey(KEY);
    if (config.getSignatureHeader().isBlank()) config.setSignatureHeader("Authorization");
  }

  @Override public ScmProvider provider() { return ScmProvider.AZURE_DEVOPS; }
  @Override public boolean isConfigured() { return config.isConfigured(); }

  @Override
  public boolean verify(byte[] payload, Map<String, String> headers) {
    if (!isConfigured()) return false;
    String supplied = ScmConnectorSupport.header(headers, config.getSignatureHeader());
    if (supplied == null || !supplied.startsWith("Basic ")) return false;
    String expected = "Basic " + Base64.getEncoder().encodeToString(config.getSecret().getBytes(StandardCharsets.UTF_8));
    return ScmConnectorSupport.secretEquals(expected, supplied);
  }

  @Override
  public Optional<InboundEvent> parse(JsonNode payload, Map<String, String> headers) {
    String event = payload.path("eventType").asText(null);
    if (event == null || event.isBlank()) return Optional.empty();
    ScmEventType type = switch (event) {
      case "git.push" -> ScmEventType.PUSH;
      case "git.pullrequest.created", "git.pullrequest.updated", "git.pullrequest.merged" -> ScmEventType.PULL_REQUEST;
      case "build.complete", "ms.vss-pipelines.run-state-changed-event" -> ScmEventType.WORKFLOW_RUN;
      case "workitem.created", "workitem.updated" -> ScmEventType.WORK_ITEM;
      default -> null;
    };
    if (type == null) return Optional.empty();
    JsonNode resource = payload.path("resource");
    String project = resource.path("repository").path("project").path("name").asText(
        payload.path("resourceContainers").path("project").path("id").asText(null));
    String repository = resource.path("repository").path("name").asText(null);
    String fullName = repository == null || repository.isBlank()
        ? (project == null ? null : project)
        : (project == null || project.isBlank() ? repository : project + "/" + repository);
    if (fullName == null || fullName.isBlank()) return Optional.empty();
    Integer number = resource.hasNonNull("pullRequestId") ? resource.path("pullRequestId").asInt() : null;
    String deliveryId = payload.path("id").asText(null);
    return Optional.of(new InboundEvent(
        deliveryId == null || deliveryId.isBlank() ? ScmConnectorSupport.derivedDeliveryId(KEY, payload.toString().getBytes(StandardCharsets.UTF_8)) : deliveryId,
        type, fullName, event,
        resource.path("sourceRefName").asText(null),
        resource.path("lastMergeSourceCommit").path("commitId").asText(null),
        number,
        resource.hasNonNull("workItemId") ? resource.path("workItemId").asText() : null));
  }
}
