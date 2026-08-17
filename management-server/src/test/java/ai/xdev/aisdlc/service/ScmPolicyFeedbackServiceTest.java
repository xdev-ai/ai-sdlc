package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ai.xdev.aisdlc.domain.DomainTypes.ScmEventType;
import ai.xdev.aisdlc.domain.DomainTypes.ScmFeedbackState;
import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import ai.xdev.aisdlc.scm.outbound.ScmFeedbackPublisher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Dispatch, isolation between providers, and what happens to an event when the provider will not take the decision. */
class ScmPolicyFeedbackServiceTest {
  @Test void aDecisionOnOneProviderIsNeverPublishedToAnother() {
    // The defect this class was written to remove. The GitHub gate checked only that a GitHub App was configured,
    // never that the link was a GitHub link, so a GitLab event on a GitHub-configured deployment attempted a Check
    // Run against a GitLab project path — and the absent installation id turned that into a failed operation.
    ScmFeedbackPublisher github = publisher(ScmProvider.GITHUB, true);
    ScmFeedbackPublisher gitlab = publisher(ScmProvider.GITLAB, true);
    ScmPolicyFeedbackService service = service(github, gitlab);
    ScmEvent event = event(ScmProvider.GITLAB);

    service.publishRequiredEvidenceGate(link(ScmProvider.GITLAB), event);

    verify(gitlab).publish(any(), any(), any());
    verify(github, never()).publish(any(), any(), any());
    assertEquals(ScmFeedbackState.PUBLISHED, event.getPolicyFeedbackState());
  }

  @Test void anUnreachableProviderRecordsAFailureInsteadOfBreakingIngestion() {
    // The event is already in the audit ledger by this point. Losing the notification must not lose the event, and
    // must not be silent either.
    ScmFeedbackPublisher gitlab = publisher(ScmProvider.GITLAB, true);
    when(gitlab.publish(any(), any(), any())).thenThrow(new ScmFeedbackPublisher.ScmFeedbackException("GitLab policy feedback returned HTTP 503"));
    ScmEvent event = event(ScmProvider.GITLAB);

    service(gitlab).publishRequiredEvidenceGate(link(ScmProvider.GITLAB), event);

    assertEquals(ScmFeedbackState.FAILED, event.getPolicyFeedbackState());
    assertNull(event.getPolicyFeedbackRef());
  }

  @Test void aProviderErrorMessageCannotForgeLogLines() {
    // CodeQL java/log-injection, alert #186. Two tainted values reached the failure log: the fallback external id,
    // which is copied out of a provider's webhook header, and a message from an exception this class did not build.
    ScmFeedbackPublisher gitlab = publisher(ScmProvider.GITLAB, true);
    String forged = "503\nWARN  Policy feedback to GITHUB for event 00000000 succeeded";
    when(gitlab.publish(any(), any(), any())).thenThrow(new ScmFeedbackPublisher.ScmFeedbackException(forged));
    ScmEvent event = event(ScmProvider.GITLAB);

    Logger logger = (Logger) LoggerFactory.getLogger(ScmPolicyFeedbackService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service(gitlab).publishRequiredEvidenceGate(link(ScmProvider.GITLAB), event);
    } finally {
      logger.detachAppender(appender);
    }

    assertEquals(1, appender.list.size());
    String rendered = appender.list.get(0).getFormattedMessage();
    assertFalse(rendered.contains("\n"), "a newline in provider text must not split the log entry");
    assertTrue(rendered.contains("503"), "the useful detail is kept, only the control characters are removed");
    assertEquals(ScmFeedbackState.FAILED, event.getPolicyFeedbackState());
  }

  @Test void aProviderWithNoOutboundCredentialIsSkippedRatherThanFailed() {
    ScmFeedbackPublisher gitlab = publisher(ScmProvider.GITLAB, false);
    ScmEvent event = event(ScmProvider.GITLAB);

    service(gitlab).publishRequiredEvidenceGate(link(ScmProvider.GITLAB), event);

    assertEquals(ScmFeedbackState.SKIPPED, event.getPolicyFeedbackState());
    verify(gitlab, never()).publish(any(), any(), any());
  }

  @Test void aDisabledPolicyGateSendsNothingAndRecordsNothing() {
    ScmFeedbackPublisher gitlab = publisher(ScmProvider.GITLAB, true);
    ScmEvent event = event(ScmProvider.GITLAB);
    ScmRepositoryLink disabled = new ScmRepositoryLink(UUID.randomUUID(), ScmProvider.GITLAB, "acme/platform", null, "main", false, "tester");

    service(gitlab).publishRequiredEvidenceGate(disabled, event);

    assertNull(event.getPolicyFeedbackState());
    verify(gitlab, never()).publish(any(), any(), any());
  }

  @Test void twoPublishersClaimingOneProviderIsRejectedAtStartup() {
    // Otherwise which one publishes is decided by bean ordering, and the loser's absence is invisible.
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> service(publisher(ScmProvider.GITLAB, true), publisher(ScmProvider.GITLAB, true)));

    assertEquals(true, error.getMessage().contains("GITLAB"));
  }

  private static ScmPolicyFeedbackService service(ScmFeedbackPublisher... publishers) {
    return new ScmPolicyFeedbackService(List.of(publishers), new ScmConnectorProperties());
  }

  private static ScmFeedbackPublisher publisher(ScmProvider provider, boolean configured) {
    ScmFeedbackPublisher publisher = mock(ScmFeedbackPublisher.class);
    when(publisher.provider()).thenReturn(provider);
    when(publisher.isConfigured()).thenReturn(configured);
    when(publisher.publish(any(), any(), any())).thenReturn(Optional.of("status-1"));
    return publisher;
  }

  private static ScmRepositoryLink link(ScmProvider provider) {
    return new ScmRepositoryLink(UUID.randomUUID(), provider, "acme/platform", null, "main", true, "tester");
  }

  private static ScmEvent event(ScmProvider provider) {
    return new ScmEvent(UUID.randomUUID(), UUID.randomUUID(), provider, "delivery-1", ScmEventType.PULL_REQUEST,
        "opened", "acme/platform", null, "refs/heads/topic", "abc123", 7, null, null, "sha", "{}");
  }
}
