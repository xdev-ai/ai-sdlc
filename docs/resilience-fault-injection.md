# P3.1 Resilience: Fault-Injection Adapters and Scenario Tests

**Status:** Implemented for the unit and service tiers. The disposable-chaos and game-day tiers in [`p3-1-resilience-chaos-test-plan.md`](p3-1-resilience-chaos-test-plan.md) remain approval-gated and are not automated here.
**Scope:** Chaos seams for every declared component, and deterministic scenarios asserting that telemetry fails open while governance fails closed.

## The Degradation Model Under Test

The platform draws one line: **telemetry is diagnostic and fails open; governance evidence is authoritative and fails closed.** Losing a trace backend must never turn a valid request into an error, and losing the policy engine, evidence store, identity provider, or audit path must never let a governed action through.

`TraceContextFilter` now implements the open half explicitly. Establishing trace context is wrapped so that any runtime failure degrades observability for that request — no trace identifiers in the logs, no bound context — while the request itself proceeds and the thread-local state is left clean. The closed half is enforced by each governance path propagating its failure.

## Fault-Injection Adapters

`ChaosFaultRegistry` is a bean only under the explicit `chaos` Spring profile; it has no bean in default, production, or shared integration profiles, and no HTTP endpoint can enable a fault. Each component now consults it through an `ObjectProvider`, so the seam is inert — a null check and nothing else — whenever the registry is absent.

| Component | Seam location | Behaviour under fault |
|---|---|---|
| `POLICY_ENGINE` | `PolicyExpressionEngine.evaluate` | The evaluation fails; the governed action is denied and never becomes an implicit pass. |
| `EVIDENCE_STORAGE` | `EvidenceRepositoryService.upload`, before the object write | No object is written, no metadata row is saved, and no audit event claims a stored asset. |
| `AUTHENTICATION` | `SecurityConfig.chaosAwareDecoder`, before the delegate decoder | The token is rejected with a generic `JwtException`; there is no fallback to a cached or alternative principal. |
| `SCM_INGRESS` | `ScmIntegrationService.ingestGitHub`, before the event is persisted | The transaction rolls back with nothing committed; the sender's retry is de-duplicated by delivery identifier. |
| `NOTIFICATION_PROVIDER` | `NotificationService.send` | Classified as retryable `NETWORK_ERROR`, so the delivery is rescheduled and the approval outcome is unchanged. |
| `RUNTIME_AI_PROVIDER` | `RuntimeAiProviderProxyService`, before each transport attempt | The dispatch fails with `PROVIDER_TIMEOUT`; the transport is never called and no response is released. |

Each seam sits **before** its side effect, so an injected fault proves the absence of a partial commit rather than the presence of a cleanup path.

## Scenario Coverage

`ResilienceScenarioTest` maps to the plan's case identifiers and runs on every change without Docker, a database, or a network.

| Case | Assertion |
|---|---|
| `RES-OTEL-01` | A telemetry failure still runs the downstream chain, returns 200, binds no context, and leaves no thread-local residue; a later request is unaffected. |
| `RES-POL-05` | The policy engine fails, then recovers cleanly once the fault is removed. |
| `RES-EVID-04` | Storage, metadata repository, and audit service all receive zero interactions. |
| `RES-IDP-06` | The decoder rejects the token, the failure message carries no subject, and the delegate is not consulted a second time. |
| `RES-SCM-07` | Nothing is saved during the outage, and the replay after recovery returns the prior event as a duplicate without a second save. |
| `RES-NOTIFY-08` | The receipt records `RETRY_SCHEDULED` / `NETWORK_ERROR` and the delivery ends in `RETRY_SCHEDULED`, not `FAILED`. |
| `RES-AI-12` | The result is `FAILED` / `PROVIDER_TIMEOUT` with no response body and no transport call. |
| Isolation | A fault applies to exactly its declared component; clearing it restores every component. |

## Component Coverage Matrix

Every dependency the platform can lose, which tier exercises it, and what the platform is required to do. A component with no in-process seam is listed with the reason, so the gap is visible rather than implied by absence.

| Component | Tier | Seam or harness | Required behaviour | Evidence |
|---|---|---|---|---|
| **Policy engine** | unit | `PolicyExpressionEngine.evaluate` | **Fail closed** — the governed action is denied | `RES-POL-05` |
| **Evidence storage** | unit | `EvidenceRepositoryService.upload` | **Fail closed** — no object, no metadata row, no audit claim | `RES-EVID-04` |
| **Keycloak / authentication** | unit | `SecurityConfig.chaosAwareDecoder` | **Fail closed** — generic `JwtException`, no cached-principal fallback | `RES-IDP-06` |
| **SCM ingress** | unit | `ScmIntegrationService.ingestGitHub` | **Fail closed** — full rollback; the sender's retry de-duplicates | `RES-SCM-07` |
| **Notification provider** | unit | `NotificationService.send` | **Degrade** — retryable `NETWORK_ERROR`, approval outcome unchanged | `RES-NOTIFY-08` |
| **Runtime AI provider** | unit | `RuntimeAiProviderProxyService` | **Fail closed** — `PROVIDER_TIMEOUT`, no transport call, no response released | `RES-AI-12` |
| **Telemetry (in-process)** | unit | `TraceContextFilter`, `GovernanceTelemetry` | **Fail open** — request succeeds, no context bound, no thread-local residue | `RES-OTEL-01` |
| **OpenTelemetry Collector** | disposable chaos | Gateway container stopped or its queue saturated | **Fail open** — the platform serves traffic while spans are dropped | `RES-OTEL-02` — not automated |
| **Telemetry backend** | disposable chaos | Exporter endpoint refused or throttled | **Fail open** — Collector queue bounds apply, no back-pressure into the application | `RES-OTEL-02` — not automated |
| **PostgreSQL / audit ledger** | disposable chaos | Database stopped mid-transaction | **Fail closed** — no partial governance state; hash chain still verifies after recovery | `RES-DB-03` — not automated; recovery covered by `scripts/verify-recovery.sh` |
| **Approval scheduler** | disposable chaos | `GovernanceAutomationScheduler` paused | **Fail closed** — no approval auto-advances while the scheduler is down | `RES-APPROVAL-09` — not automated |
| **Network partition** | disposable chaos | Container network detached | **Fail closed** on governance, **fail open** on telemetry | `RES-NET-10` — not automated |
| **Resource pressure** | disposable chaos | Container CPU/memory limits | Bounded degradation, no evidence loss | `RES-CAP-11` — not automated |

## What Is Not Automated Here

The five cases marked *not automated* need a running Collector, database, scheduler, or container resource limits. They stay in the disposable-chaos tier and require the approval gate, blast-radius controls, abort criteria, and evidence capture defined in [the chaos test plan](p3-1-resilience-chaos-test-plan.md). Nothing in this implementation enables a destructive action, targets a shared environment, or accepts fault parameters over HTTP.

One of the five is partly closed by other means: `RES-DB-03` asks whether governance state survives losing PostgreSQL, and `scripts/verify-recovery.sh` answers the recovery half of that on a disposable database — backup, restore, and a hash-chain verification that must reach the same head digest. What it does not answer is behaviour *during* the outage, which still needs a running database to interrupt.

## Verification

```sh
mvn -pl management-server test
```

`ResilienceScenarioTest` and `ChaosFaultRegistryProfileIntegrationTest` run together; the latter continues to prove that no chaos bean exists outside the explicit profile.

## References

[1] [AWS Fault Injection Service: safety controls](https://aws.amazon.com/fis/features/)

[2] [Google SRE Book: Testing Reliability](https://sre.google/sre-book/testing-reliability/)
