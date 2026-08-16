# P3.1 Reliability: Resilience and Chaos Engineering Test Plan

**Status:** implementation-ready reference  
**Audience:** platform engineering, SRE, security engineering, and governance operators  
**Scope:** P3.1 reliability controls only; the plan does not authorize experiments against production.

## 1. Purpose and Safety Model

P3.1 verifies that the platform degrades safely under dependency, network, capacity, and data-integrity failures. Telemetry loss must never interrupt a valid request, while a missing governance decision, required approval, or immutable evidence write must prevent a release-impacting action. This distinction preserves the platform's fail-open observability and fail-closed governance model.

Every experiment begins with a written steady-state hypothesis, a named owner, a bounded target set, explicit stop conditions, and a recovery verification. The initial program runs only in a disposable, isolated environment with synthetic tenants and synthetic evidence. It must not target production, shared staging, real identities, real secrets, or a live provider account. Controlled experiments with safety levers and stop conditions are consistent with fault-injection guidance.[1]

| Safety control | Mandatory implementation rule |
|---|---|
| Environment | CI or a dedicated, disposable chaos environment only; `AISDLC_CHAOS_ENABLED=true` is rejected outside an allowlisted non-production deployment. |
| Blast radius | One dependency and one synthetic tenant/project per test; concurrency is fixed to the test fixture. |
| Authorization | An operator-approved experiment manifest is required; no endpoint accepts arbitrary fault parameters from an HTTP request. |
| Abort | Abort immediately on tenant-boundary violation, integrity check failure, recovery time over the test budget, or unexpected dependency target. |
| Evidence | Record experiment ID, immutable manifest digest, injected fault, start/end, observed SLI, abort reason, recovery result, and approving operator. |
| Cleanup | The harness removes fault injection, awaits queue drain, verifies health/readiness, and exports evidence before the environment is destroyed. |

## 2. Test Harness Architecture

The P3.1 harness must use dependency adapters rather than runtime shell access. A `FaultInjectionProfile` is explicitly selected by CI and can inject delay, timeout, connection refusal, deterministic 5xx, bounded queue pressure, a stale token, or an object-store write failure. It cannot target a hostname, environment, or credential not declared by the isolated compose fixture.

The test profile is not packaged in a production deployment. Production runtime code has no management endpoint capable of enabling chaos. Where an underlying dependency must be emulated, use a container-local proxy or a fake adapter controlled only by the test process. The pipeline must run destructive concurrency and storage tests serially and retain diagnostics as test evidence.

## 3. Detailed Test Cases

Each test uses the same synthetic fixture: one tenant, one organization, one project, a model-pinned agent session, a policy requiring a human approval, and an evidence record. Baseline requests must be green for five minutes before fault injection. The expected recovery budget is the smaller of the documented SLO recovery target and 10 minutes unless the table specifies otherwise.

| ID | Fault injection and procedure | Expected safe behavior | Required assertions and evidence |
|---|---|---|---|
| `RES-OTEL-01` | Refuse both Collector OTLP ports for ten minutes while executing policy read-only and approval-read requests. | Business requests complete; telemetry exporter failure is bounded and does not create unbounded threads or retry storms. | Request success rate remains at baseline; a telemetry-loss counter increments; memory stays under the configured limit; no raw payload is written to fallback logs. |
| `RES-OTEL-02` | Delay exporter responses by 15 seconds, then return HTTP 503 for five minutes. | Collector queue applies configured retry/back-pressure; application work remains available. | Queue depth never exceeds its configured bound; Collector memory limiter engages before process memory exhaustion; recovery drains queue without duplicate governance evidence. |
| `RES-DB-03` | Deny PostgreSQL connections at the start of a policy decision that requires retained decision evidence. | The release-impacting request is denied or marked unavailable; no decision is inferred from stale or absent evidence. | HTTP/API result contains a non-sensitive failure code; no side effect occurs; audit sequence has no gap claimed as successful; recovery returns readiness only after a real write/read probe passes. |
| `RES-EVID-04` | Return object-storage write failures and timeouts during SBOM/provenance or decision-evidence persistence. | A decision requiring stored evidence fails closed; previously finalized evidence remains readable. | No release or approval transition commits; object key is not reported as persisted without a verified digest; retry is idempotent and produces one canonical record after recovery. |
| `RES-POL-05` | Inject CEL compilation error, non-Boolean result, missing input key, and evaluation timeout into a governed action. | The engine rejects the decision and records a policy evaluation failure without executing the governed action. | Decision is `DENY`/`UNAVAILABLE`, never `ALLOW`; error is classified without exposing policy secrets; human approval cannot override a missing policy result. |
| `RES-IDP-06` | Expire a workload token and make the Keycloak/JWKS dependency unavailable while attempting a new privileged action. | New authentication/authorization is rejected; cached identity may only be used within its documented bounded lifetime and never crosses tenant scope. | No principal escalation, no cross-tenant record access, and no bypass of audience/authority validation; recovery requires a fresh valid token. |
| `RES-SCM-07` | Replay the same GitHub webhook, then inject an interrupted persistence operation before the idempotency marker commits. | Ingestion is idempotent; duplicate events do not create duplicate check runs, evidence, or approvals. | One canonical SCM event and one linked decision exist after recovery; sequence/deduplication evidence identifies the replay. |
| `RES-NOTIFY-08` | Make each configured notification endpoint return timeout, 429, and 503 in turn. | Delivery is retried with bounded backoff; the approval state is unchanged until a valid signed receipt is received. | Attempt count respects retry policy; duplicate message suppression key remains stable; no secret or recipient address appears in telemetry. |
| `RES-APPROVAL-09` | Restart the approval-SLA worker during a due reminder and issue two simultaneous scheduler invocations. | Reminder/escalation remains idempotent and quorum state is preserved. | At most one delivery per logical reminder; no decision is auto-approved; audit trail links all attempts to the same request. |
| `RES-NET-10` | Partition management server from evidence storage and notification service while keeping PostgreSQL available. | Read-only portal functions continue; evidence-dependent action fails closed; telemetry stays best-effort. | Dependency-specific health reports degradation; no global readiness claim when the required evidence path is unavailable. |
| `RES-CAP-11` | Apply bounded CPU/memory pressure to the Collector and management server containers. | Load shedding and memory limits activate without corrupting decisions or allowing unsafe work. | p99 governed-action latency and error rate are measured; no OOM restart loop; policy/approval integrity checks pass after pressure is removed. |
| `RES-AI-12` | For a future P3.3 gateway adapter, inject provider timeout, malformed usage report, model mismatch, and quota exhaustion. | Request is not silently rerouted to an unapproved model; provider failure does not remove human approval or policy checks. | Model/provider allowlist remains enforced; billing record is marked unreconciled rather than fabricated; recovery retry is idempotent. |

## 4. Game-Day Procedure

| Stage | Operator action | Exit criterion |
|---|---|---|
| Prepare | Review manifest digest, verify isolated environment, establish baseline SLIs, acknowledge stop conditions, and nominate incident lead/observer. | Baseline probes green for five minutes and evidence sink reachable. |
| Inject | Enable exactly one predefined fault profile for its approved duration. | The target and fault match the signed manifest; no unrelated service is affected. |
| Observe | Record availability, latency, audit/evidence integrity, queue depth, retries, and operator actions. | Hypothesis is proved or disproved with retained evidence. |
| Abort or recover | Immediately remove the fault on any stop condition; otherwise let the controlled duration elapse. | Dependency health and service probes meet recovery target. |
| Verify | Run reconciliation, idempotency, tenant-isolation, and evidence-digest checks. | No duplicate, missing, cross-tenant, or unverifiable record remains. |
| Review | Publish outcome, SLO impact, remediation owner/date, and whether the scenario may advance to a broader non-production environment. | Human reviewer accepts the evidence; no automatic promotion occurs. |

## 5. Automated Test Tiers and Acceptance Criteria

The unit and integration tiers run on every pull request. The disposable chaos tier runs only with explicit non-production environment approval. A production game day is out of scope until the project has collected a 28-day SLO baseline and an operations review has approved the exact experiment manifest.

| Tier | Frequency | Allowed scenarios | Pass threshold |
|---|---|---|---|
| Unit | Every change | Adapter faults, retry bounds, fail-open/fail-closed decisions | 100% deterministic tests pass. |
| Integration | Every change affecting a dependency contract | `RES-OTEL-01`, `RES-POL-05`, `RES-SCM-07`, `RES-NOTIFY-08` | No unsafe side effect; test evidence retained. |
| Disposable chaos | Manual, approval-gated | All cases except production-only provider experiments | Recovery within budget; no integrity or tenant-isolation violation. |
| Game day | Quarterly after baseline approval | A reviewed subset with one narrow failure mode | Review board accepts evidence and remediation plan. |

## References

[1]: https://aws.amazon.com/fis/features/ "AWS Fault Injection Service: safety controls"
[2]: https://sre.google/sre-book/testing-reliability/ "Google SRE Book: Testing Reliability"
