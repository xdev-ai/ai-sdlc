package ai.xdev.aisdlc.scm.outbound;

import ai.xdev.aisdlc.domain.DomainTypes.ScmProvider;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import ai.xdev.aisdlc.service.GitHubAppClient;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GitHub Check Runs, behind the same contract as the other four.
 *
 * <p>GitHub already had a working outbound path before this contract existed. It is adapted rather than rewritten,
 * so the check-run behaviour and its identifier are unchanged; what changes is that the dispatcher no longer knows
 * GitHub specifically.
 *
 * <p>Unlike the others, this publisher updates in place when the event already carries a check-run id. GitHub
 * distinguishes creating a check run from patching one, where the other providers upsert on commit and key.
 */
@Component
public class GitHubFeedbackPublisher implements ScmFeedbackPublisher {
  private final GitHubAppClient github;

  public GitHubFeedbackPublisher(GitHubAppClient github) { this.github = github; }

  @Override public ScmProvider provider() { return ScmProvider.GITHUB; }
  @Override public boolean isConfigured() { return github.isAvailable(); }

  @Override
  public Optional<String> publish(ScmRepositoryLink link, ScmEvent event, PolicyFeedback feedback) {
    if (event.getCommitSha() == null || event.getCommitSha().isBlank()) return Optional.empty();
    try {
      if (event.getPolicyCheckRunId() == null) {
        long checkRunId = github.createCheckRun(link, event.getCommitSha(), feedback.conclusion(), feedback.summary(), feedback.externalId());
        return Optional.of(Long.toString(checkRunId));
      }
      github.updateCheckRun(link, event.getPolicyCheckRunId(), event.getCommitSha(), feedback.conclusion(), feedback.summary(), feedback.externalId());
      return Optional.of(Long.toString(event.getPolicyCheckRunId()));
    } catch (RuntimeException error) {
      throw new ScmFeedbackException("GitHub policy feedback could not be delivered", error);
    }
  }
}
