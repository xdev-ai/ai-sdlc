# SCM and Work-Management Connector Contract

**Status:** Inbound ingress implemented for GitLab, Bitbucket, Azure DevOps, and Jira. Outbound policy feedback for those four is **not** implemented; see the scope note below.
**Scope:** `todo.md:130` — extend integrations using one versioned connector contract.

## The contract

`ScmConnector` (contract version `scm.inbound.v1`) is deliberately narrow. A connector does exactly three things:

1. proves the request came from its provider,
2. extracts a stable delivery identifier,
3. maps the payload onto a neutral event shape.

It never decides authorization, never writes to the database, and never calls the provider back. Repository linking, idempotency, persistence, audit, and telemetry are shared, so **a new connector cannot invent its own rules for the parts that matter**. Adding a provider is a parser plus a verifier, not a second ingestion pipeline.

The delivery identifier is the idempotency key. A provider that sends none forces the connector to derive one deterministically from the payload — at-least-once delivery is the norm, and a replay must not create a second event.

## Endpoint

```
POST /api/v1/webhooks/scm/{connectorKey}
```

`connectorKey` is the lower-cased provider with underscores as hyphens: `gitlab`, `bitbucket`, `azure-devops`, `jira`. The original `/api/v1/webhooks/github` endpoint is unchanged.

The path is `permitAll` because webhooks authenticate by provider credential rather than by bearer token. That is safe only because **an unconfigured connector rejects every request** — it never falls through to accepting unverified input.

Order matters: verification runs on the raw bytes **before** the body is parsed, so a malformed or hostile payload is refused before any parsing work.

## Per-connector verification

| Connector | Mechanism | Default header |
|---|---|---|
| `gitlab` | Constant-time shared-token comparison | `X-Gitlab-Token` |
| `bitbucket` | HMAC-SHA256 over the raw body | `X-Hub-Signature` |
| `azure-devops` | HTTP Basic credential, constant-time | `Authorization` |
| `jira` | HMAC-SHA256 over the raw body | `X-Hub-Signature` |

[Unverified] These defaults reflect each provider's published webhook behaviour, but none has been exercised against a live provider tenant from this repository. **Header names are configurable for exactly this reason** — a provider change, or a proxy that renames a header, is a configuration edit rather than a release.

Note the asymmetry: GitLab and Azure DevOps authenticate with a bearer-style secret that is **not bound to the payload**, so a captured request can be replayed verbatim. Shared idempotency on the delivery identifier is what makes that safe, which is why the derived-identifier rule is part of the contract rather than a connector's choice.

## Configuration

```yaml
aisdlc:
  scm:
    connectors:
      gitlab:      { secret: "${AISDLC_SCM_GITLAB_SECRET}" }
      bitbucket:   { secret: "${AISDLC_SCM_BITBUCKET_SECRET}" }
      azure-devops:{ secret: "${AISDLC_SCM_AZURE_DEVOPS_SECRET}" }
      jira:        { secret: "${AISDLC_SCM_JIRA_SECRET}" }
```

Every secret defaults to empty, so every new connector is off until an operator supplies one. `signatureHeader`, `eventHeader`, and `deliveryHeader` are overridable per connector.

## Event mapping

| Neutral type | GitLab | Bitbucket | Azure DevOps | Jira |
|---|---|---|---|---|
| `PUSH` | Push, Tag Push | `repo:push` | `git.push` | — |
| `PULL_REQUEST` | Merge Request | `pullrequest:*` | `git.pullrequest.*` | — |
| `WORKFLOW_RUN` | Pipeline, Job | — | `build.complete` | — |
| `CHECK_RUN` | — | `repo:commit_status` | — | — |
| `RELEASE` | Release | — | — | — |
| `WORK_ITEM` | — | — | `workitem.*` | `jira:issue_*` |

A payload that maps to nothing is recorded as `event_not_represented` and accepted, not rejected: the provider should stop retrying, and guessing a type would put a wrong event in the governance ledger.

Jira is a work-management provider, not an SCM. Its events carry an issue key rather than a repository, so a project links against the Jira **project key** and the issue key is kept as the correlation key. That puts a Jira event in the same ledger and traceability graph as a pull request without a parallel pipeline.

## Scope note: outbound policy feedback is not included

GitHub publishes policy results as a Check Run. GitLab, Bitbucket, Azure DevOps, and Jira each expose a different mechanism — commit status, build status, PR status, issue comment — and each needs its own authenticated API client, token model, and failure handling.

Those are **not implemented here**. Building four outbound clients with no way to exercise them against a real tenant would produce code that looks finished and has never run. The inbound half — ingress, verification, correlation, idempotency, audit — is complete and tested; `todo.md:130` stays open until the outbound half lands.

## Verification

```sh
mvn -pl management-server test
```

`ScmConnectorContractTest` runs the contract against all four implementations: every connector refuses until configured, refuses a missing or wrong credential, declines a payload it does not represent, and produces a deterministic delivery identifier. It also checks that an HMAC connector's signature actually binds to the payload — a tampered body with a valid-looking header is rejected.

No connector has been exercised against a live provider tenant. The parsers are pinned to payload shapes drawn from each provider's documented webhook format, which is a weaker guarantee than a recorded real delivery.
