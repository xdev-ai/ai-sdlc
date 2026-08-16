# P3.1 SLO Runbooks

Every burn-rate and integrity alert links to a section here. The anchor is the journey label on the alert, so a new
objective in [`p3-slo-definitions.yaml`](../infra/observability/p3-slo-definitions.yaml) needs a section with the same
name; `scripts/test-observability-contracts.sh` fails the build when one is missing.

**Two rules apply to every response below.**

1. Telemetry is diagnostic. A degraded Collector or telemetry backend is never a reason to bypass a governance control,
   and never on its own an incident affecting users.
2. Nothing from a prompt, model output, tool argument, evidence body, raw audit payload, or credential goes into an
   incident channel, ticket, or alert annotation. Reference the correlation ID and the audit record instead, and read
   them through the control plane under normal authorization.

## Current objectives

| Journey | Objective | Budget policy |
|---|---:|---|
| `control-plane-availability` | 99.90% | error-budget |
| `policy-decision-latency` | 99.95% | error-budget |
| `approval-orchestration` | 99.90% | error-budget |
| `notification-timeliness` | 99.50% | error-budget |
| `scm-ingestion-freshness` | 99.90% | error-budget |
| `audit-correctness` | 100% | integrity |
| `evidence-durability` | 100% | integrity |

All values are **initial proposed targets**. They are not observed performance and must be reviewed after the 28-day
observe-only baseline before paging is enabled.

## control-plane-availability

**Signal.** Valid authenticated API requests returning a non-5xx outcome, plus the authenticated synthetic journey.

**First checks.** Readiness (`/actuator/health/readiness`) including the `db` and `auditLedger` groups; recent
deployment version; PostgreSQL availability and connection saturation; whether `scripts/synthetic-health-journey.sh`
fails at the token, health, or authorized-read stage.

**Likely causes.** Database unavailable or saturated, Keycloak/JWKS unreachable so every request fails authentication,
or a bad deployment.

**Do not.** Disable authentication, widen `/api/**` authorities, or restart to clear a symptom before capturing which
dependency failed.

## policy-decision-latency

**Signal.** `PASS`/`FAIL`/`WARN` evaluations completing inside the latency budget.

**First checks.** `aisdlc.policy.evaluate` duration distribution; whether a recently activated policy bundle changed;
CEL expression complexity in the active bundle; database latency, since evaluation reads bundle state.

**Likely causes.** An expensive expression in a newly promoted bundle, or evaluation contending with database load.

**Do not.** Bypass or disable policy evaluation to restore latency. A missing policy result is a `DENY`, and that is
the intended behaviour — degrade the action, not the control.

## approval-orchestration

**Signal.** Approval transitions completing without an orchestration failure.

**First checks.** Whether failures concentrate on one project or approver; the approval SLA and reminder scheduler;
notification delivery, since an approver who is never notified cannot decide.

**Likely causes.** Notification outage upstream, quorum or delegation misconfiguration, or scheduler contention.

**Do not.** Auto-approve, lower quorum, or decide on an approver's behalf to clear a backlog. Human approval is a
platform invariant.

## notification-timeliness

**Signal.** Eligible notifications delivered or terminally failed with an auditable receipt inside the window.

**First checks.** Delivery receipts by outcome; attempt counts against the retry policy; whether one channel type
dominates the failures; provider status.

**Likely causes.** Provider outage or rate limiting, an expired channel secret, or a misconfigured destination.

**Do not.** Copy recipient addresses or channel secrets into the incident. A provider outage keeps deliveries
retryable and leaves the approval outcome unchanged; that is the designed behaviour.

## scm-ingestion-freshness

**Signal.** Valid signed webhooks accepted and durably recorded inside the window.

**First checks.** Signature rejection versus processing failure; duplicate delivery identifiers; GitHub App
installation and delivery backlog; database write latency.

**Likely causes.** Provider retry storm, a rotated webhook secret, or database contention.

**Do not.** Disable signature verification, or replay deliveries manually without confirming the idempotency marker.
Ingestion is idempotent by delivery identifier; let the sender retry.

## audit-correctness

**Integrity objective — zero tolerated failures. A single occurrence is a security incident, not a budget burn.**

**Signal.** `aisdlc_audit_integrity_failures_total`, incremented when hash-chain verification finds a break.

**Immediate actions.** Preserve state — do not truncate, repair, or re-run migrations against `audit_events`. Record
the organization and the first invalid sequence from the verification result. Treat every governance decision after
that sequence as unverified until reviewed. Engage security engineering.

**Do not.** Rewrite or delete audit rows. The table is append-only at the database level; an attempt to modify it is
itself a finding worth capturing.

## evidence-durability

**Integrity objective — zero tolerated failures.**

**Signal.** `aisdlc_evidence_integrity_failures_total`, incremented when a stored digest does not match.

**Immediate actions.** Preserve the object and its metadata row. Confirm whether object-lock retention is intact.
Identify releases or approvals that referenced the affected evidence. Evidence-dependent actions fail closed by
design; keep them closed until the mismatch is explained.

**Do not.** Re-upload over the affected key, or mark provenance verified to clear the alert.

## governance-integrity

The routing anchor for `AiSdlcAuditOrEvidenceIntegrityViolation`, which fires for either integrity objective. Follow
the matching section above based on which counter increased, and note that this alert bypasses grouping delay and
suppresses burn-rate noise for the same service while it is open.

## Telemetry pipeline degradation

Not an SLO, but the most common false alarm. If alerts stop arriving or SLI series go stale, check the Collector
before concluding the platform is healthy: an absent signal is not a good signal. The Collector sheds telemetry under
memory pressure by design and never blocks a user request. See
[`resilience-fault-injection.md`](resilience-fault-injection.md) for the fail-open boundary.
