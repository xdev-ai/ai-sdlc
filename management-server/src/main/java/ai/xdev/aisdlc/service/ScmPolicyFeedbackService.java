package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmFeedbackState;
import ai.xdev.aisdlc.domain.DomainTypes.ScmPolicyConclusion;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import ai.xdev.aisdlc.domain.ValidationRun;
import ai.xdev.aisdlc.scm.outbound.ScmFeedbackPublisher;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dispatches a governance decision to whichever provider the change came from.
 *
 * <p>This replaces direct calls into the GitHub gate. The previous arrangement had a defect that only appeared once
 * a second provider existed: {@code GitHubPolicyGateService} checked that a GitHub App was configured, but never
 * that the repository link was a <em>GitHub</em> link. Linking a validation run to a GitLab event on a deployment
 * with a GitHub App configured attempted a GitHub Check Run against a GitLab project path, and the missing
 * installation id turned that into an exception that failed the whole operation.
 *
 * <p>Delivery is fail-open and the outcome is recorded on the event. Publishing is a notification to a system the
 * platform does not control; a provider outage must not roll back an event already written to the audit ledger, and
 * it must not be invisible either — {@link ScmFeedbackState#FAILED} is what distinguishes "the provider rejected
 * this" from "nothing was ever sent".
 */
@Service
public class ScmPolicyFeedbackService {
  private static final Logger log = LoggerFactory.getLogger(ScmPolicyFeedbackService.class);
  private static final String NO_EVIDENCE_SUMMARY =
      "No validation run has been linked to this change. A human-approved AI-SDLC validation decision is required before the policy gate can pass.";

  private final Map<ScmProvider, ScmFeedbackPublisher> publishers = new EnumMap<>(ScmProvider.class);
  private final ScmConnectorProperties properties;

  // No SLI journey is emitted for outbound publishing. The seven journeys in p3-slo-definitions.yaml are awaiting a
  // 28-day baseline (ADR 0003); inventing an eighth target with no observed data is the failure that ADR describes.
  // Outbound health is queryable from scm_events.policy_feedback_state until there is data to set a target from.
  public ScmPolicyFeedbackService(List<ScmFeedbackPublisher> publisherBeans, ScmConnectorProperties properties) {
    this.properties = properties;
    for (ScmFeedbackPublisher publisher : publisherBeans) {
      ScmFeedbackPublisher existing = publishers.put(publisher.provider(), publisher);
      if (existing != null) {
        throw new IllegalStateException("Two outbound publishers claim " + publisher.provider()
            + "; a provider must have exactly one, or which one publishes is decided by bean ordering");
      }
    }
  }

  /** Publishes the blocking status that says a change has arrived with no linked validation evidence. */
  public void publishRequiredEvidenceGate(ScmRepositoryLink link, ScmEvent event) {
    publish(link, event, ScmPolicyConclusion.ACTION_REQUIRED, NO_EVIDENCE_SUMMARY);
  }

  /** Publishes the outcome of a validation run once a human decision has been linked to the change. */
  public void publishValidationDecision(ScmRepositoryLink link, ScmEvent event, ValidationRun validationRun) {
    ScmPolicyConclusion conclusion = switch (validationRun.getStatus()) {
      case PASSED -> ScmPolicyConclusion.SUCCESS;
      case FAILED -> ScmPolicyConclusion.FAILURE;
      case BLOCKED -> ScmPolicyConclusion.ACTION_REQUIRED;
    };
    String summary = switch (validationRun.getStatus()) {
      case PASSED -> "Validation run " + validationRun.getId() + " passed. Human decision evidence remains auditable in AI-SDLC.";
      case FAILED -> "Validation run " + validationRun.getId() + " failed. Resolve findings or obtain an approved exception before release.";
      case BLOCKED -> "Validation run " + validationRun.getId() + " is blocked pending required evidence or a human decision.";
    };
    publish(link, event, conclusion, summary);
  }

  private void publish(ScmRepositoryLink link, ScmEvent event, ScmPolicyConclusion conclusion, String summary) {
    if (!link.isPolicyGateEnabled()) return;
    ScmFeedbackPublisher publisher = publishers.get(link.getProvider());
    if (publisher == null || !publisher.isConfigured()) {
      // Not a failure: a deployment may ingest from a provider it is not yet permitted to write back to.
      event.recordPolicyFeedback(ScmFeedbackState.SKIPPED, null);
      return;
    }
    ScmConnectorProperties.Connector config = properties.forKey(configKey(link.getProvider()));
    String externalId = event.getId() == null ? event.getDeliveryId() : event.getId().toString();
    var feedback = new ScmFeedbackPublisher.PolicyFeedback(conclusion, config.getStatusContext(), summary, externalId, config.detailsUrl(externalId));
    try {
      Optional<String> reference = publisher.publish(link, event, feedback);
      event.recordPolicyFeedback(reference.isPresent() ? ScmFeedbackState.PUBLISHED : ScmFeedbackState.SKIPPED, reference.orElse(null));
      if (reference.isPresent() && link.getProvider() == ScmProvider.GITHUB) {
        // Preserve the pre-contract column so existing GitHub check-run updates keep addressing the same run.
        try { event.recordPolicyCheckRun(Long.parseLong(reference.get())); } catch (NumberFormatException ignored) { /* non-numeric references stay in the neutral column */ }
      }
    } catch (RuntimeException error) {
      // Provider identity and status only. Provider error text can echo the submitted request.
      log.warn("Policy feedback to {} for event {} failed: {}", link.getProvider(), externalId, error.getMessage());
      event.recordPolicyFeedback(ScmFeedbackState.FAILED, null);
    }
  }

  /** Maps the provider enum onto the {@code aisdlc.scm.connectors.<key>} configuration key the connectors already use. */
  private static String configKey(ScmProvider provider) {
    return provider.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
