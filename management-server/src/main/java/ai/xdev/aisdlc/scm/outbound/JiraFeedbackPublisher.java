package ai.xdev.aisdlc.scm.outbound;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmPolicyConclusion;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Jira issue comments.
 *
 * <p>Jira is the provider where the outbound contract stops being a status at all. There is no commit, no pull
 * request, and nothing to mark green or red — a governance decision reaches a Jira issue as a comment, and it
 * blocks nothing. That is a real difference in enforcement strength, recorded here rather than papered over: Jira
 * feedback is a notification, and a Jira-only project has no enforcement point.
 *
 * <p>An issue transition would be closer to enforcement, but a transition depends on a workflow this platform does
 * not own, and driving someone else's workflow from a policy decision is a larger commitment than a comment.
 */
@Component
public class JiraFeedbackPublisher implements ScmFeedbackPublisher {
  private final ScmConnectorProperties.Connector config;
  private final ScmFeedbackSupport support;

  public JiraFeedbackPublisher(ScmConnectorProperties properties, ScmFeedbackSupport support) {
    this.config = properties.forKey("jira");
    this.support = support;
  }

  @Override public ScmProvider provider() { return ScmProvider.JIRA; }
  @Override public boolean isConfigured() { return config.isOutboundConfigured() && !config.getApiUser().isBlank(); }

  @Override
  public Optional<String> publish(ScmRepositoryLink link, ScmEvent event, PolicyFeedback feedback) {
    String issueKey = event.getExternalKey();
    if (issueKey == null || issueKey.isBlank()) return Optional.empty();
    ObjectNode body = support.newBody();
    ObjectNode document = body.putObject("body");
    document.put("type", "doc");
    document.put("version", 1);
    ArrayNode content = document.putArray("content");
    ObjectNode paragraph = content.addObject();
    paragraph.put("type", "paragraph");
    ObjectNode text = paragraph.putArray("content").addObject();
    text.put("type", "text");
    text.put("text", comment(feedback));
    URI uri = URI.create(config.getApiBaseUrl() + "/rest/api/3/issue/"
        + ScmFeedbackSupport.encodeSegment(issueKey) + "/comment");
    JsonNode response = support.send("POST", uri, body,
        ScmFeedbackSupport.basicAuth(config.getApiUser(), config.getApiToken()), config, "Jira");
    return Optional.of(response.path("id").isMissingNode() ? issueKey : response.path("id").asText());
  }

  private String comment(PolicyFeedback feedback) {
    String verdict = switch (feedback.conclusion()) {
      case SUCCESS -> "passed";
      case FAILURE -> "failed";
      case ACTION_REQUIRED -> "is blocked and requires action";
      case NEUTRAL -> "recorded no decision";
    };
    String detail = feedback.detailsUrl() == null ? "" : " Details: " + feedback.detailsUrl();
    return feedback.title() + " " + verdict + ". " + ScmFeedbackSupport.truncate(feedback.summary()) + detail;
  }
}
