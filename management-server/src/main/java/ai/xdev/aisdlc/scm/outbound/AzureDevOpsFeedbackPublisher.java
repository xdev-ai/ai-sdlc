package ai.xdev.aisdlc.scm.outbound;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmPolicyConclusion;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Azure DevOps pull-request statuses.
 *
 * <p>Azure DevOps is the one provider here that does not attach the status to a commit. A status belongs to a pull
 * request, so a push event has nowhere to publish and is skipped — the decision surfaces when the change reaches a
 * pull request, which is also the only point at which Azure can block it.
 *
 * <p>The organization is configuration, not payload: it appears nowhere in the webhook body, and the status URL
 * cannot be built without it.
 */
@Component
public class AzureDevOpsFeedbackPublisher implements ScmFeedbackPublisher {
  private final ScmConnectorProperties.Connector config;
  private final ScmFeedbackSupport support;

  public AzureDevOpsFeedbackPublisher(ScmConnectorProperties properties, ScmFeedbackSupport support) {
    this.config = properties.forKey("azure-devops");
    this.support = support;
  }

  @Override public ScmProvider provider() { return ScmProvider.AZURE_DEVOPS; }
  @Override public boolean isConfigured() { return config.isOutboundConfigured() && !config.getOrganization().isBlank(); }

  @Override
  public Optional<String> publish(ScmRepositoryLink link, ScmEvent event, PolicyFeedback feedback) {
    if (event.getPullRequestNumber() == null) return Optional.empty();
    String[] repository = ScmFeedbackSupport.twoPartName(link.getRepositoryFullName(), "Azure DevOps", "project/repository");
    ObjectNode body = support.newBody();
    body.put("state", state(feedback.conclusion()));
    body.put("description", ScmFeedbackSupport.truncate(feedback.summary()));
    ObjectNode context = body.putObject("context");
    context.put("genre", "ai-sdlc");
    context.put("name", contextName());
    if (feedback.detailsUrl() != null) body.put("targetUrl", feedback.detailsUrl());
    URI uri = URI.create(config.getApiBaseUrl() + "/" + ScmFeedbackSupport.encodeSegment(config.getOrganization())
        + "/" + ScmFeedbackSupport.encodeSegment(repository[0])
        + "/_apis/git/repositories/" + ScmFeedbackSupport.encodeSegment(repository[1])
        + "/pullRequests/" + event.getPullRequestNumber() + "/statuses?api-version=7.1");
    // Azure DevOps personal access tokens authenticate as the password half of Basic with an empty user.
    JsonNode response = support.send("POST", uri, body,
        ScmFeedbackSupport.basicAuth("", config.getApiToken()), config, "Azure DevOps");
    return Optional.of(response.path("id").isMissingNode()
        ? Integer.toString(event.getPullRequestNumber()) : response.path("id").asText());
  }

  /** The genre carries the namespace, so the name is the context with any genre-like prefix removed. */
  private String contextName() {
    String context = config.getStatusContext();
    int separator = context.lastIndexOf('/');
    String name = separator < 0 ? context : context.substring(separator + 1);
    return name.isBlank() ? "policy" : name;
  }

  /**
   * {@code ACTION_REQUIRED} maps to {@code failed}. Azure's {@code pending} leaves a required status unresolved but
   * reads as "still running", and {@code notApplicable} would let the pull request complete.
   */
  private static String state(ScmPolicyConclusion conclusion) {
    return switch (conclusion) {
      case SUCCESS -> "succeeded";
      case FAILURE, ACTION_REQUIRED -> "failed";
      case NEUTRAL -> "notApplicable";
    };
  }
}
