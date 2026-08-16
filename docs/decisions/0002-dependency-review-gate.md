# ADR 0002: Dependency review is advisory; OSV and Trivy remain the blocking dependency gates

**Status:** Accepted — decision closed
**Date:** 2026-08-17

## Context

The `dependency-review` job has failed on **every** pull request in this repository since it was first exercised. It has never once passed.

The cause is not the code under review:

```
Dependency review is not supported on this repository.
Please ensure that Dependency graph is enabled.
```

`GET /repos/xdev-ai/ai-sdlc/dependency-graph/sbom` returns `404`, and the repository's `security_and_analysis` block does not report a `dependency_graph` entry at all. The organization default was `dependency_graph_enabled_for_new_repositories: false`; that has been changed to `true`, which governs **new** repositories and does not retroactively enable the existing ones. A repository-level `PATCH` is accepted by the API and has no effect. The organization is on the free plan.

[Unverified] Why two public repositories have no dependency graph, when it is normally on by default for public repositories, could not be determined from the API.

## The real problem this creates

A check that always fails is worse than a check that does not exist. Every pull request in this session ended with a red mark that had to be explained away in prose, and every merge required a human to confirm — again — that the only failure was the known-broken one. That is exactly how a team learns to merge past red CI, and it is how a genuine failure eventually gets waved through.

The two defects that mattered most this session were both caught by the **other** dependency gates:

| Finding | Caught by |
|---|---|
| `opentelemetry-javaagent` 2.16.0, CVE-2026-33701, CRITICAL | Trivy |
| `opentelemetry-api` 1.51.0, GHSA-rcgg-9c38-7xpx | OSV |
| `httpclient5`/`httpcore5` advisories via the AWS SDK BOM | OSV |

Those gates work, they block, and they have a demonstrated record on this repository.

## Decision

**`dependency-review` becomes advisory (`continue-on-error: true`). OSV-Scanner and Trivy remain the blocking dependency gates.**

The job stays in the workflow rather than being deleted, so that the day Dependency graph is enabled it starts reporting real findings without anyone having to remember to restore it. Until then its result is informational and does not turn the pull request red.

This is not a reduction in dependency scanning. It is the removal of a signal that carries no information, in favour of two that do.

## Consequences

- Pull requests are green when they are actually green, so a red check means something again.
- Dependency vulnerability coverage is unchanged: OSV on every pull request and the full repository, Trivy on repository dependencies, secrets, IaC, and both production images, plus CodeQL and Dependabot.
- If Dependency graph is later enabled, remove `continue-on-error` in the same change that verifies the job passes.

## Revisit trigger

Reopen when `GET /repos/xdev-ai/ai-sdlc/dependency-graph/sbom` returns an SBOM rather than `404`. That is a one-command check:

```sh
gh api repos/xdev-ai/ai-sdlc/dependency-graph/sbom --jq '.sbom.name'
```
