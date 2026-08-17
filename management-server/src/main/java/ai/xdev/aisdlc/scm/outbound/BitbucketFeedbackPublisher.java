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
 * Bitbucket Cloud build statuses.
 *
 * <p>Bitbucket keys a build status by a caller-chosen string and upserts on that key, so republishing a decision for
 * the same commit replaces the previous one instead of stacking duplicates. The key is derived from the configured
 * status context so that two AI-SDLC deployments writing to one repository do not overwrite each other.
 */
@Component
public class BitbucketFeedbackPublisher implements ScmFeedbackPublisher {
  private final ScmConnectorProperties.Connector config;
  private final ScmFeedbackSupport support;

  public BitbucketFeedbackPublisher(ScmConnectorProperties properties, ScmFeedbackSupport support) {
    this.config = properties.forKey("bitbucket");
    this.support = support;
  }

  @Override public ScmProvider provider() { return ScmProvider.BITBUCKET; }
  @Override public boolean isConfigured() { return config.isOutboundConfigured(); }

  @Override
  public Optional<String> publish(ScmRepositoryLink link, ScmEvent event, PolicyFeedback feedback) {
    if (event.getCommitSha() == null || event.getCommitSha().isBlank()) return Optional.empty();
    String[] repository = ScmFeedbackSupport.twoPartName(link.getRepositoryFullName(), "Bitbucket", "workspace/repository");
    String key = statusKey();
    ObjectNode body = support.newBody();
    body.put("key", key);
    body.put("state", state(feedback.conclusion()));
    body.put("name", feedback.title());
    body.put("description", ScmFeedbackSupport.truncate(feedback.summary()));
    if (feedback.detailsUrl() != null) body.put("url", feedback.detailsUrl());
    URI uri = URI.create(config.getApiBaseUrl() + "/2.0/repositories/"
        + ScmFeedbackSupport.encodeSegment(repository[0]) + "/" + ScmFeedbackSupport.encodeSegment(repository[1])
        + "/commit/" + ScmFeedbackSupport.encodeSegment(event.getCommitSha()) + "/statuses/build");
    JsonNode response = support.send("POST", uri, body, "Bearer " + config.getApiToken(), config, "Bitbucket");
    return Optional.of(response.path("key").isMissingNode() ? key : response.path("key").asText());
  }

  /** Bitbucket keys are opaque and length-limited; the context is normalised rather than passed through verbatim. */
  private String statusKey() {
    String normalized = config.getStatusContext().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    return normalized.length() <= 40 ? normalized : normalized.substring(0, 40);
  }

  /**
   * {@code ACTION_REQUIRED} maps to {@code FAILED}, not {@code INPROGRESS}. An in-progress status reads as "still
   * running" and clears itself from a reviewer's attention; a blocked decision must stay visibly blocked.
   */
  private static String state(ScmPolicyConclusion conclusion) {
    return switch (conclusion) {
      case SUCCESS -> "SUCCESSFUL";
      case FAILURE, ACTION_REQUIRED -> "FAILED";
      case NEUTRAL -> "STOPPED";
    };
  }
}
