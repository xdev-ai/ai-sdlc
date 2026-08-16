# Approval and Notification Orchestration

## Purpose

The AI-SDLC control plane uses a project-scoped orchestration service to deliver governance notifications and collect **human** decisions. It does not replace any human decision point with automation. Notifications make a decision observable; they never approve, reject, merge, or release software.

## Notification Channels

Project owners can configure `EMAIL`, `SLACK_WEBHOOK`, `TEAMS_WEBHOOK`, or `GENERIC_WEBHOOK` channels through the API or SSR portal. Webhook destinations must use HTTPS. Generic webhooks require a channel-specific signing secret. Email destinations must be syntactically email-like and require a configured Spring Mail sender at runtime.

Destinations and generic-webhook signing secrets are encrypted with AES-256-GCM before persistence. Set `AISDLC_NOTIFICATION_ENCRYPTION_KEY` to a 32-byte base64url value. A channel list response returns only a SHA-256 destination fingerprint; it never returns a destination, encrypted ciphertext, or signing secret.

| Channel | Delivery form | Additional control |
|---|---|---|
| `EMAIL` | Spring Mail plaintext message | Requires configured sender and `from-address`. |
| `SLACK_WEBHOOK` | JSON message to an HTTPS incoming webhook | Retry on network failure, HTTP 429, and HTTP 5xx. |
| `TEAMS_WEBHOOK` | JSON message to an HTTPS incoming webhook | Retry on network failure, HTTP 429, and HTTP 5xx. |
| `GENERIC_WEBHOOK` | Versioned JSON delivery envelope | Includes `X-AISDLC-Delivery`, `X-AISDLC-Timestamp`, and `X-AISDLC-Signature-256`. |

For generic webhooks, verify the signature by recomputing `HMAC-SHA256(timestamp + "." + raw JSON body, channel secret)` and compare it in constant time. Reject delivery timestamps outside the receiving service's replay window.

## Delivery Ledger and Retry Behavior

Each enabled channel gets one `notification_deliveries` entry per idempotency key. The immutable `notification_delivery_receipts` table captures every completed attempt, its payload SHA-256, HTTP status when available, and terminal/error code.

The dispatcher uses short database transactions to claim and complete a delivery. It deliberately performs network I/O outside the database transaction. It retries only network errors, HTTP `429`, and HTTP `5xx`, with capped exponential backoff. Configuration errors, invalid responses, disabled channels, and non-retryable client errors become terminal states. A stale `SENDING` claim is eligible for reconciliation after ten minutes.

`GovernanceAutomationScheduler` invokes delivery dispatch and approval SLA processing through configurable cron expressions. These deterministic tasks are application-native; no LLM or external agent is involved.

## Approval Lifecycle

An approval request is bounded by a project, source reference, quorum from 1 to 50, due timestamp, optional assigned approver, and immutable decisions. Only project owners and reviewers can decide or delegate. If an approver is assigned, only that subject, its explicit delegate, or the organization break-glass owner identity may decide.

| State | Transition | Evidence |
|---|---|---|
| `PENDING` | Created with a future SLA | `APPROVAL_REQUEST_CREATED` audit event and `approval.requested` notification. |
| `PENDING` / `ESCALATED` | Approve quorum is met | Immutable approval decisions, `APPROVAL_DECISION_RECORDED`, and `approval.decided` notification. |
| `PENDING` / `ESCALATED` | Any authorized rejection | Immutable rejection and terminal `REJECTED` state. |
| `PENDING` | Due timestamp passes | `ESCALATED` status and idempotent `approval.escalated` notification. |
| `PENDING` / `ESCALATED` | Due soon | Bounded periodic `approval.reminder` notification. |

Duplicate decisions by the same actor are rejected. Delegation never alters an earlier decision, and delegation is recorded in the audit ledger. Automation can issue reminders and escalation notices only; it cannot manufacture an approval.

## Security Exception Expiry

Security exceptions are persisted as project-scoped records rather than inferred from a mutable runtime file. They must have a future expiry and owner/reviewer authorization. The SLA processor emits expiring and expired notices with idempotency keys and changes an expired exception's lifecycle state. The CI `.trivyignore.yaml` expiry validation remains an independent fail-closed control.

## API Surface

| Endpoint | Use |
|---|---|
| `POST /api/v1/projects/{projectId}/notification-channels` | Create an encrypted channel. |
| `GET /api/v1/projects/{projectId}/notification-channels` | List safe channel metadata. |
| `PATCH /api/v1/projects/{projectId}/notification-channels/{channelId}` | Enable or disable a channel. |
| `GET /api/v1/projects/{projectId}/notification-deliveries` | Read the delivery ledger. |
| `POST /api/v1/projects/{projectId}/approvals` | Request a governed human approval. |
| `GET /api/v1/projects/{projectId}/approvals` | Read project-scoped approval queue. |
| `POST /api/v1/approvals/{approvalId}/decisions` | Record an immutable approval or rejection. |
| `POST /api/v1/approvals/{approvalId}/delegation` | Delegate a pending approval. |
| `POST /api/v1/projects/{projectId}/security-exceptions` | Record a time-bounded security exception. |

## Operations

Set the notification encryption key before enabling any channel. Configure optional email sender settings only when email delivery is required. Review failed delivery receipts, rotate generic-webhook secrets by creating a replacement channel, and disable the old channel after downstream verification. Treat each completed approval decision and delivery receipt as audit evidence subject to the platform's retention policy.
