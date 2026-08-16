# Notification Delivery Contracts

## Purpose

AI-SDLC sends governance notifications only through explicitly configured and enabled channels. Every attempt creates an immutable delivery receipt that records the channel, message identity, destination fingerprint, outcome, HTTP status when applicable, and retry disposition. Channel secrets are never returned by APIs, portal pages, audit event payloads, or delivery receipts.

## Slack Incoming Webhooks

Slack incoming webhooks accept an HTTPS `POST` with a JSON body containing at minimum a `text` value. The webhook URL is itself a secret and must not be committed or exposed. AI-SDLC sends concise plain-text summaries with a stable delivery identifier, treats 2xx as delivered, retries only rate-limit and transient server failures, and marks configuration/authentication failures as terminal. Slack reports actionable errors for malformed payloads, disabled hooks, archived channels, and invalid tokens, so those errors require operator remediation rather than blind retries. [1]

## Microsoft Teams Webhook Workflows

Microsoft recommends Teams Workflows/Power Automate webhook URLs for new deployments because legacy Microsoft 365 connectors are approaching deprecation. A workflow receives an HTTPS `POST` with a JSON payload and can post a message or Adaptive Card. AI-SDLC uses the transport-neutral text payload that Workflow templates accept, with a 28 KB maximum message size. Teams documents a throughput threshold of four requests per second and recommends exponential backoff for HTTP 429 responses; the notification dispatcher therefore applies bounded retry with backoff and never performs unbounded fan-out. [2]

## Security and Operational Rules

| Rule | Requirement |
|---|---|
| Destination protection | Store the destination URL encrypted at rest; show only a redacted fingerprint after creation. |
| Outbound authentication | Every generic outbound webhook receives HMAC SHA-256 headers containing timestamp, delivery ID, and payload signature. |
| Replay resistance | Receivers must reject timestamps outside their accepted skew window and deduplicate the delivery ID. |
| Retry discipline | Retry network errors, HTTP 429, and HTTP 5xx only; respect `Retry-After` when present. |
| Terminal failure | Do not retry malformed payload, authentication, authorization, invalid endpoint, or disabled channel responses. |
| Audit evidence | Preserve message digest and receipt metadata, never the raw notification secret. |

## References

[1]: https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks "Slack Developer Docs: Sending messages using incoming webhooks"
[2]: https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook "Microsoft Learn: Create Incoming Webhooks"
