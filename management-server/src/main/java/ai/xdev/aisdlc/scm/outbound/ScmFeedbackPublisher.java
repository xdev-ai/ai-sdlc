package ai.xdev.aisdlc.scm.outbound;

import ai.xdev.aisdlc.domain.DomainTypes.ScmPolicyConclusion;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import java.util.Optional;

/**
 * The versioned outbound contract: how a governance decision reaches the provider the change came from.
 *
 * <p>The inbound contract ({@code scm.inbound.v1}) collapses five providers onto one event shape. This is the return
 * path, and it does not collapse as cleanly. GitHub publishes a Check Run against a commit, GitLab a commit status,
 * Bitbucket a keyed build status, Azure DevOps a status on a pull request rather than a commit, and Jira has no
 * status concept at all — the decision is a comment on an issue. A publisher therefore owns its own addressing,
 * authentication, and state vocabulary; what it does not own is whether to publish, or what the decision was.
 *
 * <p>Two rules every implementation follows:
 *
 * <ul>
 *   <li><b>Unconfigured means silent, not broken.</b> {@link #isConfigured()} is false until the deployment supplies
 *       an API token, and the dispatcher skips the publisher rather than failing the event.
 *   <li><b>Fail closed on the decision, fail open on delivery.</b> A publisher maps {@code ACTION_REQUIRED} onto the
 *       provider's blocking state, never a passing one — a decision the provider cannot express exactly must not
 *       degrade into approval. But a provider outage throws {@link ScmFeedbackException}, and the dispatcher records
 *       the failure instead of rolling back an event that is already in the audit ledger.
 * </ul>
 */
public interface ScmFeedbackPublisher {
  /** Contract version of the outbound shape. A breaking change to {@link PolicyFeedback} increments this. */
  String CONTRACT_VERSION = "scm.outbound.v1";

  ScmProvider provider();

  /** True when this publisher holds the credential and base URL it needs to reach the provider. */
  boolean isConfigured();

  /**
   * Publishes one decision.
   *
   * @return the provider-side reference — check-run id, status id, status key, comment id — or empty when the event
   *     carries nothing this provider can be addressed by, such as a Jira event with no issue key. Empty is a skip,
   *     not a failure.
   * @throws ScmFeedbackException when the provider was reachable but rejected the publish, or was unreachable
   */
  Optional<String> publish(ScmRepositoryLink link, ScmEvent event, PolicyFeedback feedback);

  /**
   * One decision in provider-neutral terms.
   *
   * @param conclusion the governance outcome
   * @param title short label; becomes the status context or check name
   * @param summary human-readable explanation shown next to the status
   * @param externalId the AI-SDLC event identifier, so a status can be traced back to the ledger row that caused it
   * @param detailsUrl deep link into the platform, or null when no public base URL is configured
   */
  record PolicyFeedback(ScmPolicyConclusion conclusion, String title, String summary, String externalId,
                        String detailsUrl) {}

  /** Raised when the provider was reached and refused, or could not be reached at all. */
  class ScmFeedbackException extends RuntimeException {
    public ScmFeedbackException(String message) { super(message); }
    public ScmFeedbackException(String message, Throwable cause) { super(message, cause); }
  }
}
