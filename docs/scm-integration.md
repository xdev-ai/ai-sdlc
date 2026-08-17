# SCM and work-management integration

Both halves of the connector contract: events coming in from five providers, and governance decisions going back out to them.

- [GitHub SCM Integration](#github-scm-integration)
- [SCM and Work-Management Connector Contract](#scm-and-work-management-connector-contract)
- [Outbound Webhook Events](#outbound-webhook-events)

## GitHub SCM Integration

### Purpose

The GitHub integration connects source-control events to AI-SDLC projects, validation evidence, human decisions, and release governance. It is intentionally event-driven: GitHub delivers signed webhook payloads, AI-SDLC records an idempotent event ledger, and the GitHub App may publish a policy Check Run only after the required governance evidence has been evaluated.

### Verified GitHub Contract

GitHub signs each configured webhook payload in the `X-Hub-Signature-256` header with an HMAC SHA-256 digest. The receiver must verify the unmodified raw request body using a constant-time comparison before parsing it. The webhook delivery identifier is carried in `X-GitHub-Delivery` and is globally unique; AI-SDLC stores it with a provider-scoped unique constraint to make delivery retries idempotent. [1] [2]

The GitHub App manifest subscribes only to the events that AI-SDLC handles: `push`, `pull_request`, `check_run`, `workflow_run`, and `release`. A payload can be as large as 25 MB, so gateway and application request limits must remain aligned with this contract. [2]

GitHub Check Runs are writable only through a GitHub App. The application obtains a short-lived installation access token from the GitHub App JWT, creates or updates a Check Run for the relevant commit, and includes an external identifier that points back to the immutable AI-SDLC SCM event. [3]

### Security Boundaries

| Boundary | Control |
|---|---|
| Inbound authenticity | Fail closed unless `X-Hub-Signature-256` verifies against `AISDLC_GITHUB_WEBHOOK_SECRET`. |
| Replay safety | Enforce the unique `(provider, delivery_id)` ledger key. Repeated accepted deliveries return the original event identity without reprocessing. |
| Repository scope | Accept an event only when its `repository.full_name` is linked to an AI-SDLC project. |
| Outbound least privilege | Use a GitHub App installation token, not a user token, for Checks API operations. |
| Secret handling | Keep app private key and webhook secret in deployment secrets; never commit them. |
| Auditability | Record repository links, event payload digest, validation association, and policy Check Run identity in the append-only audit ledger. |

### Required Deployment Settings

| Environment variable | Required when | Description |
|---|---|---|
| `AISDLC_GITHUB_APP_ENABLED` | Publishing policy checks | Enables outbound GitHub App operations. Defaults to `false`. |
| `AISDLC_GITHUB_APP_ID` | Publishing policy checks | Numeric GitHub App identifier used as the JWT issuer. |
| `AISDLC_GITHUB_APP_PRIVATE_KEY_PEM` | Publishing policy checks | PKCS#8 RSA private key PEM for signing GitHub App JWTs. |
| `AISDLC_GITHUB_WEBHOOK_SECRET` | Receiving GitHub webhooks | High-entropy secret used for HMAC SHA-256 verification. |
| `AISDLC_GITHUB_CHECK_DETAILS_URL_TEMPLATE` | Optional | Check details URL. The `{externalId}` placeholder is replaced with the SCM event ID. |

### GitHub App Permissions and Events

The manifest requests read access to repository contents, pull requests, actions, and metadata, plus write access to Checks. The write permission is necessary because GitHub permits Check Run creation and updates only to GitHub Apps. [3]

### References

[1]: https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries "GitHub Docs: Validating webhook deliveries"
[2]: https://docs.github.com/webhooks/webhook-events-and-payloads "GitHub Docs: Webhook events and payloads"
[3]: https://docs.github.com/en/rest/checks/runs "GitHub Docs: REST API endpoints for check runs"

---

## SCM and Work-Management Connector Contract

**Status:** Inbound ingress implemented for GitLab, Bitbucket, Azure DevOps, and Jira. Outbound policy feedback for those four is **not** implemented; see the scope note below.
**Scope:** `todo.md:130` — extend integrations using one versioned connector contract.

### The contract

`ScmConnector` (contract version `scm.inbound.v1`) is deliberately narrow. A connector does exactly three things:

1. proves the request came from its provider,
2. extracts a stable delivery identifier,
3. maps the payload onto a neutral event shape.

It never decides authorization, never writes to the database, and never calls the provider back. Repository linking, idempotency, persistence, audit, and telemetry are shared, so **a new connector cannot invent its own rules for the parts that matter**. Adding a provider is a parser plus a verifier, not a second ingestion pipeline.

The delivery identifier is the idempotency key. A provider that sends none forces the connector to derive one deterministically from the payload — at-least-once delivery is the norm, and a replay must not create a second event.

### Endpoint

```
POST /api/v1/webhooks/scm/{connectorKey}
```

`connectorKey` is the lower-cased provider with underscores as hyphens: `gitlab`, `bitbucket`, `azure-devops`, `jira`. The original `/api/v1/webhooks/github` endpoint is unchanged.

The path is `permitAll` because webhooks authenticate by provider credential rather than by bearer token. That is safe only because **an unconfigured connector rejects every request** — it never falls through to accepting unverified input.

Order matters: verification runs on the raw bytes **before** the body is parsed, so a malformed or hostile payload is refused before any parsing work.

### Per-connector verification

| Connector | Mechanism | Default header |
|---|---|---|
| `gitlab` | Constant-time shared-token comparison | `X-Gitlab-Token` |
| `bitbucket` | HMAC-SHA256 over the raw body | `X-Hub-Signature` |
| `azure-devops` | HTTP Basic credential, constant-time | `Authorization` |
| `jira` | HMAC-SHA256 over the raw body | `X-Hub-Signature` |

[Unverified] These defaults reflect each provider's published webhook behaviour, but none has been exercised against a live provider tenant from this repository. **Header names are configurable for exactly this reason** — a provider change, or a proxy that renames a header, is a configuration edit rather than a release.

Note the asymmetry: GitLab and Azure DevOps authenticate with a bearer-style secret that is **not bound to the payload**, so a captured request can be replayed verbatim. Shared idempotency on the delivery identifier is what makes that safe, which is why the derived-identifier rule is part of the contract rather than a connector's choice.

### Configuration

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

### Event mapping

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

### Outbound policy feedback — `scm.outbound.v1`

The return path: how a governance decision reaches the provider the change came from. `ScmFeedbackPublisher` is the SPI; `ScmPolicyFeedbackService` dispatches on the repository link's provider, and exactly one publisher may claim each provider — two is a startup failure, because otherwise which one publishes is decided by bean ordering.

| Provider | Mechanism | Addressed by | Skipped when |
|---|---|---|---|
| GitHub | Check Run | commit SHA | no commit SHA |
| GitLab | commit status | URL-encoded `group/project` + commit SHA | no commit SHA |
| Bitbucket | keyed build status | `workspace/repository` + commit SHA | no commit SHA |
| Azure DevOps | pull-request status | organization + `project/repository` + PR number | no pull request |
| Jira | issue comment | issue key | no issue key |

**A skip is not a failure.** Azure DevOps attaches a status to a pull request rather than a commit, so a push event has nowhere to publish; the decision surfaces when the change reaches a pull request, which is also the only point Azure can block it.

#### Two rules every publisher follows

**Fail closed on the decision.** No provider has an exact equivalent of GitHub's `action_required`. Each approximation lands on the blocking side — GitLab `failed`, Bitbucket `FAILED`, Azure `failed` — never `pending`, `INPROGRESS`, or `notApplicable`. A `pending` status passes wherever pipelines are not required, and a state that reads as "still running" clears itself from a reviewer's attention. A decision the provider cannot express exactly must degrade towards blocking, never towards approval.

**Fail open on delivery.** A provider outage must not roll back an event already written to the audit ledger, and must not be silent either. The outcome is recorded on the event as `policy_feedback_state`: `PUBLISHED`, `FAILED`, or `SKIPPED`. Failures are queryable rather than lost:

```sql
select provider, count(*) from scm_events where policy_feedback_state = 'FAILED' group by provider;
```

No SLI journey is emitted for outbound publishing. The seven journeys in `p3-slo-definitions.yaml` are awaiting a 28-day baseline (ADR 0003), and inventing an eighth target with no observed data is the failure that ADR describes.

#### Enforcement strength is not uniform

Jira feedback is a **comment**, and a comment blocks nothing. A Jira-only project has no enforcement point — the decision is recorded and visible, not enforced. An issue transition would be closer to enforcement, but a transition depends on a workflow this platform does not own. This is a real difference between providers, stated rather than papered over.

#### Outbound configuration

Outbound credentials are separate from the inbound webhook secret, and configured independently: a deployment may ingest from a provider long before it is willing to let the platform write back, and enabling ingestion must not silently enable writes.

```yaml
aisdlc:
  scm:
    connectors:
      gitlab:
        apiBaseUrl: "https://gitlab.com"
        apiToken: "${AISDLC_SCM_GITLAB_TOKEN}"
      bitbucket:
        apiBaseUrl: "https://api.bitbucket.org"
        apiToken: "${AISDLC_SCM_BITBUCKET_TOKEN}"
      azure-devops:
        apiBaseUrl: "https://dev.azure.com"
        apiToken: "${AISDLC_SCM_AZURE_DEVOPS_PAT}"
        organization: "contoso"          # appears nowhere in the webhook payload
      jira:
        apiBaseUrl: "https://acme.atlassian.net"
        apiUser: "automation@example.com" # Jira's token is the password half of Basic
        apiToken: "${AISDLC_SCM_JIRA_TOKEN}"
```

`statusContext` defaults to `ai-sdlc/policy` and `detailsUrlTemplate` is empty; set it to deep-link a status back into the platform, with `{externalId}` replaced by the SCM event id.

### Verification

```sh
mvn -pl management-server test
```

`ScmConnectorContractTest` runs the inbound contract against all four implementations: every connector refuses until configured, refuses a missing or wrong credential, declines a payload it does not represent, and produces a deterministic delivery identifier. It also checks that an HMAC connector's signature actually binds to the payload — a tampered body with a valid-looking header is rejected.

`ScmFeedbackContractTest` runs the outbound contract against a real embedded HTTP server rather than a mocked client, so what is asserted is the request that would actually leave the process: the path, the method, the auth scheme, and the state string a provider would receive. It pins the fail-closed mapping on all four providers, that a missing addressing field sends no request at all, that a single-component repository name is rejected rather than written to the wrong place, and that a rejected publish reports the status code without the provider's response body — providers echo the submitted request, and the request carries the `Authorization` header.

`ScmPolicyFeedbackServiceTest` covers dispatch: a GitLab decision never reaches GitHub, an unreachable provider records `FAILED` instead of breaking ingestion, an unconfigured provider records `SKIPPED`, and two publishers claiming one provider is a startup failure.

**No connector has been exercised against a live provider tenant, inbound or outbound.** The parsers are pinned to payload shapes drawn from each provider's documented webhook format, and the publishers to each provider's documented API shape. That is a weaker guarantee than a recorded real delivery or a status visible in a real repository, and it is the gap that remains on this item.

---

## Outbound Webhook Events

### Delivery Contract

AI-SDLC emits outbound events through a project-scoped notification channel of type `GENERIC_WEBHOOK`. The destination must use HTTPS and has an independently encrypted signing secret. Event delivery is asynchronous, receipt-backed, idempotent per event key, and retried only for network failure, HTTP `429`, and HTTP `5xx`.

The platform uses a CloudEvents-compatible JSON envelope with an AI-SDLC schema version. The current schema version is **1.0**. Consumers must reject unsupported major schema versions and should ignore unknown optional attributes for compatible minor evolution.

```json
{
  "specversion": "1.0",
  "schemaVersion": "1.0",
  "id": "b6ca3a31-baa2-4a31-a023-2b19dd04c12b",
  "type": "ai.xdev.aisdlc.approval.requested",
  "source": "urn:ai-sdlc:notification-channel:fdc40d5d-894b-46b4-9d1f-df68fae0259d",
  "time": "2026-08-16T08:30:00Z",
  "datacontenttype": "application/json",
  "data": {
    "subject": "Release approval required",
    "text": "A human decision is pending.",
    "payloadSha256": "lower-case-sha256-of-the-delivered-notification"
  }
}
```

| Header | Meaning |
|---|---|
| `X-AISDLC-Delivery` | Equals envelope `id`; use it as an idempotency key. |
| `X-AISDLC-Timestamp` | RFC 3339 timestamp used in the signature input. |
| `X-AISDLC-Event-Schema` | Current AI-SDLC envelope schema version. |
| `X-AISDLC-Signature-256` | `sha256=` plus HMAC-SHA256 of `timestamp + "." + raw request body`. |

Verify the signature against the **raw** request body before JSON parsing, compare in constant time, enforce a replay window for the timestamp, and store processed `id` values atomically. Never accept a delivery because its source IP or user agent appears trustworthy.

### Event Types

| Event type | Trigger |
|---|---|
| `approval.requested` | A human approval request is created. |
| `approval.decided` | A human decision is recorded or quorum completes. |
| `approval.reminder` | A pending approval approaches its SLA. |
| `approval.escalated` | An approval passes its SLA and needs escalation. |
| `security-exception.expiring` | A project security exception approaches expiry. |
| `security-exception.expired` | A project security exception expires. |

The event stream is notification evidence, not a command channel. It cannot approve a request, change a policy decision, merge a pull request, or release an artifact.
