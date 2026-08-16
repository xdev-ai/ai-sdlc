# Outbound Webhook Events

## Delivery Contract

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

## Event Types

| Event type | Trigger |
|---|---|
| `approval.requested` | A human approval request is created. |
| `approval.decided` | A human decision is recorded or quorum completes. |
| `approval.reminder` | A pending approval approaches its SLA. |
| `approval.escalated` | An approval passes its SLA and needs escalation. |
| `security-exception.expiring` | A project security exception approaches expiry. |
| `security-exception.expired` | A project security exception expires. |

The event stream is notification evidence, not a command channel. It cannot approve a request, change a policy decision, merge a pull request, or release an artifact.
