# GitHub SCM Integration

## Purpose

The GitHub integration connects source-control events to AI-SDLC projects, validation evidence, human decisions, and release governance. It is intentionally event-driven: GitHub delivers signed webhook payloads, AI-SDLC records an idempotent event ledger, and the GitHub App may publish a policy Check Run only after the required governance evidence has been evaluated.

## Verified GitHub Contract

GitHub signs each configured webhook payload in the `X-Hub-Signature-256` header with an HMAC SHA-256 digest. The receiver must verify the unmodified raw request body using a constant-time comparison before parsing it. The webhook delivery identifier is carried in `X-GitHub-Delivery` and is globally unique; AI-SDLC stores it with a provider-scoped unique constraint to make delivery retries idempotent. [1] [2]

The GitHub App manifest subscribes only to the events that AI-SDLC handles: `push`, `pull_request`, `check_run`, `workflow_run`, and `release`. A payload can be as large as 25 MB, so gateway and application request limits must remain aligned with this contract. [2]

GitHub Check Runs are writable only through a GitHub App. The application obtains a short-lived installation access token from the GitHub App JWT, creates or updates a Check Run for the relevant commit, and includes an external identifier that points back to the immutable AI-SDLC SCM event. [3]

## Security Boundaries

| Boundary | Control |
|---|---|
| Inbound authenticity | Fail closed unless `X-Hub-Signature-256` verifies against `AISDLC_GITHUB_WEBHOOK_SECRET`. |
| Replay safety | Enforce the unique `(provider, delivery_id)` ledger key. Repeated accepted deliveries return the original event identity without reprocessing. |
| Repository scope | Accept an event only when its `repository.full_name` is linked to an AI-SDLC project. |
| Outbound least privilege | Use a GitHub App installation token, not a user token, for Checks API operations. |
| Secret handling | Keep app private key and webhook secret in deployment secrets; never commit them. |
| Auditability | Record repository links, event payload digest, validation association, and policy Check Run identity in the append-only audit ledger. |

## Required Deployment Settings

| Environment variable | Required when | Description |
|---|---|---|
| `AISDLC_GITHUB_APP_ENABLED` | Publishing policy checks | Enables outbound GitHub App operations. Defaults to `false`. |
| `AISDLC_GITHUB_APP_ID` | Publishing policy checks | Numeric GitHub App identifier used as the JWT issuer. |
| `AISDLC_GITHUB_APP_PRIVATE_KEY_PEM` | Publishing policy checks | PKCS#8 RSA private key PEM for signing GitHub App JWTs. |
| `AISDLC_GITHUB_WEBHOOK_SECRET` | Receiving GitHub webhooks | High-entropy secret used for HMAC SHA-256 verification. |
| `AISDLC_GITHUB_CHECK_DETAILS_URL_TEMPLATE` | Optional | Check details URL. The `{externalId}` placeholder is replaced with the SCM event ID. |

## GitHub App Permissions and Events

The manifest requests read access to repository contents, pull requests, actions, and metadata, plus write access to Checks. The write permission is necessary because GitHub permits Check Run creation and updates only to GitHub Apps. [3]

## References

[1]: https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries "GitHub Docs: Validating webhook deliveries"
[2]: https://docs.github.com/webhooks/webhook-events-and-payloads "GitHub Docs: Webhook events and payloads"
[3]: https://docs.github.com/en/rest/checks/runs "GitHub Docs: REST API endpoints for check runs"
