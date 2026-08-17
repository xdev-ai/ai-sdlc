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
 * GitLab commit statuses.
 *
 * <p>GitLab addresses a project by the URL-encoded {@code group/project} path and attaches the status to a commit,
 * so an event with no head commit has nowhere to publish and is skipped.
 */
@Component
public class GitLabFeedbackPublisher implements ScmFeedbackPublisher {
  private final ScmConnectorProperties.Connector config;
  private final ScmFeedbackSupport support;

  public GitLabFeedbackPublisher(ScmConnectorProperties properties, ScmFeedbackSupport support) {
    this.config = properties.forKey("gitlab");
    this.support = support;
  }

  @Override public ScmProvider provider() { return ScmProvider.GITLAB; }
  @Override public boolean isConfigured() { return config.isOutboundConfigured(); }

  @Override
  public Optional<String> publish(ScmRepositoryLink link, ScmEvent event, PolicyFeedback feedback) {
    if (event.getCommitSha() == null || event.getCommitSha().isBlank()) return Optional.empty();
    ObjectNode body = support.newBody();
    body.put("state", state(feedback.conclusion()));
    body.put("name", feedback.title());
    body.put("description", ScmFeedbackSupport.truncate(feedback.summary()));
    if (feedback.detailsUrl() != null) body.put("target_url", feedback.detailsUrl());
    URI uri = URI.create(config.getApiBaseUrl() + "/api/v4/projects/"
        + ScmFeedbackSupport.encodeSegment(link.getRepositoryFullName()) + "/statuses/"
        + ScmFeedbackSupport.encodeSegment(event.getCommitSha()));
    JsonNode response = support.send("POST", uri, body, "Bearer " + config.getApiToken(), config, "GitLab");
    return Optional.of(response.path("id").isMissingNode() ? event.getCommitSha() : response.path("id").asText());
  }

  /**
   * GitLab has no equivalent of {@code action_required}. It maps to {@code failed} rather than {@code pending}
   * because both mean the change must not merge, and {@code pending} passes wherever pipelines are not required —
   * a decision the provider cannot express exactly must degrade towards blocking, never towards approval.
   */
  private static String state(ScmPolicyConclusion conclusion) {
    return switch (conclusion) {
      case SUCCESS -> "success";
      case FAILURE, ACTION_REQUIRED -> "failed";
      case NEUTRAL -> "canceled";
    };
  }
}
