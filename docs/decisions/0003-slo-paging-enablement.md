# ADR 0003: Paging stays disabled until a 28-day observe-only baseline completes

**Status:** Accepted — decision closed
**Date:** 2026-08-17

## Context

`infra/observability/p3-slo-definitions.yaml` publishes seven service-level objectives, and `p3-slo-burn-rate-rules.yaml` defines multi-window burn-rate alerts against them. Every target in that file is an **initial proposed value**, not an observed level. Nothing in this repository has ever run long enough to know whether 99.90% availability or a 500 ms policy-decision budget is achievable, generous, or impossible.

The open question was: when may those alerts page a human?

Enabling paging against unvalidated targets produces one of two failures. Targets set too tight page constantly, and the alerts get muted — which is worse than having no alerts, because the mute is invisible. Targets set too loose page never, and the objective is decoration.

## Decision

**Paging remains disabled until a 28-day observe-only baseline completes on a production deployment, after which the targets are reviewed and enablement is explicitly approved.**

This is a closed decision, not a deferral: the conditions are fixed here, and no further judgement call is required to know whether paging may be turned on.

## Exit criteria

All must hold before `severity: page` routes to a human:

1. **28 consecutive days** of `aisdlc_sli_events_total` from a production deployment, with no gap longer than 24 hours. A gap resets the window, because a burn-rate target derived from partial data is not a baseline.
2. Every one of the seven journeys has recorded traffic. A journey with no events has no baseline and its objective stays observe-only individually, even if the others graduate.
3. The observed 30-day ratio for each journey is compared against its proposed target, and each target is either confirmed or amended **with the amendment recorded in this file**.
4. Documented exclusions — user-caused 4xx, deliberate denials, announced maintenance — are confirmed to be excluded from the numerator as the SLI definitions claim.
5. `AISDLC_PAGE_RECEIVER_URL` has been exercised end to end against the non-paging review destination, and the runbook link in each alert resolves.

## Until then

- `alertmanager-routes.yaml` ships with `AISDLC_PAGE_RECEIVER_URL` pointed at a non-paging review destination.
- The two **integrity** objectives are exempt from the waiting period. `audit-correctness` and `evidence-durability` have zero tolerated failures and no error budget to calibrate, so `AiSdlcAuditOrEvidenceIntegrityViolation` may page from day one. There is nothing to learn from 28 days of observation about whether a broken audit chain is an incident.
- Burn-rate alerts continue to evaluate and record, so the baseline accumulates while nobody is woken.

## Owner and review

Platform operations owns the baseline window and records its start date here when a production deployment begins emitting. This ADR is amended, not replaced, when the targets are confirmed.

**Baseline start date:** not yet started — no production deployment is emitting telemetry. The window begins on the first day of production telemetry, not on the date of this decision.

## Consequences

The 28 days are execution, not deliberation. Nobody needs to decide anything further; the criteria above either hold or they do not. Marking this item complete before the window elapses would be fabricating evidence about a system's reliability, which is the specific failure this platform exists to prevent.
