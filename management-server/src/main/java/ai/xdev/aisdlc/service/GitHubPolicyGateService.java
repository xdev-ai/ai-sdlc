package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.ScmPolicyConclusion;
import ai.xdev.aisdlc.domain.DomainTypes.ValidationStatus;
import ai.xdev.aisdlc.domain.ScmEvent;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import ai.xdev.aisdlc.domain.ValidationRun;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

@Service
public class GitHubPolicyGateService {
  private final GitHubAppClient github;
  public GitHubPolicyGateService(GitHubAppClient github) { this.github = github; }

  public OptionalLong publishRequiredEvidenceGate(ScmRepositoryLink link, ScmEvent event) {
    if (!link.isPolicyGateEnabled() || !github.isAvailable() || event.getCommitSha() == null || event.getCommitSha().isBlank()) return OptionalLong.empty();
    long checkRun = github.createCheckRun(link, event.getCommitSha(), ScmPolicyConclusion.ACTION_REQUIRED,
        "No validation run has been linked to this change. A human-approved AI-SDLC validation decision is required before the policy gate can pass.", event.getId().toString());
    return OptionalLong.of(checkRun);
  }

  public void publishValidationDecision(ScmRepositoryLink link, ScmEvent event, ValidationRun validationRun) {
    if (!link.isPolicyGateEnabled() || !github.isAvailable() || event.getCommitSha() == null || event.getCommitSha().isBlank()) return;
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
    if (event.getPolicyCheckRunId() == null) event.recordPolicyCheckRun(github.createCheckRun(link, event.getCommitSha(), conclusion, summary, event.getId().toString()));
    else github.updateCheckRun(link, event.getPolicyCheckRunId(), event.getCommitSha(), conclusion, summary, event.getId().toString());
  }
}
