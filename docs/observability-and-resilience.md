# Observability and resilience

Telemetry that never blocks a request, SLOs not yet allowed to page, and the fault injection that proves which failures are meant to be survived and which are meant to stop everything.

- [P3.1 Sprint 1: Telemetry Configuration Model and Trace Context](#p31-sprint-1-telemetry-configuration-model-and-trace-context)
- [P3.1: OpenTelemetry Agent Packaging and Governance Instrumentation](#p31-opentelemetry-agent-packaging-and-governance-instrumentation)
- [P3.1 SLO Runbooks](#p31-slo-runbooks)
- [P3.1 Resilience: Fault-Injection Adapters and Scenario Tests](#p31-resilience-fault-injection-adapters-and-scenario-tests)
- [P3.1 Reliability: Resilience and Chaos Engineering Test Plan](#p31-reliability-resilience-and-chaos-engineering-test-plan)

## P3.1 Sprint 1: Telemetry Configuration Model and Trace Context

**Status:** Implemented in the management server. Telemetry is disabled by default and no exporter is created.
**Scope:** Configuration contract, resource-attribute allowlist, W3C trace-context propagation, and privacy/cardinality contract tests. Java agent packaging, the Collector deployment, domain instrumentation, SLI metrics, and burn-rate alerting remain open P3.1 work.

This sprint delivers steps 1 and part of step 2 of the implementation sequence in [`runtime-ai-governance.md`](runtime-ai-governance.md). It intentionally adds no OpenTelemetry SDK dependency: the configuration model, the data-classification contract, and the propagation behaviour are established and tested first, so the later agent and Collector work starts from a reviewed contract rather than from defaults.

### Configuration Model

The prefix is `aisdlc.telemetry` and the model is bound by `TelemetryProperties`. Defaults keep an existing deployment unchanged: `enabled` is `false` and `exporter-endpoint` is empty, so nothing is exported and no exporter is constructed.

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `enabled` | `AISDLC_TELEMETRY_ENABLED` | `false` | Master switch; an export is possible only when this is true **and** an endpoint is set. |
| `contract-version` | — | `telemetry.v1` | Version of the data-classification contract. A mismatch fails startup rather than exporting under an unreviewed policy. |
| `service-name` | `AISDLC_TELEMETRY_SERVICE_NAME` | `ai-sdlc-management-server` | `service.name` resource attribute. |
| `service-namespace` | `AISDLC_TELEMETRY_SERVICE_NAMESPACE` | `ai-sdlc` | `service.namespace` resource attribute. |
| `service-version` | `AISDLC_TELEMETRY_SERVICE_VERSION` | empty | `service.version`; omitted from the resource when empty. |
| `service-instance-id` | `AISDLC_TELEMETRY_SERVICE_INSTANCE_ID` | empty | `service.instance.id`; omitted from the resource when empty. |
| `environment` | `DEPLOYMENT_ENVIRONMENT` | `development` | `deployment.environment.name`; restricted to `development`, `staging`, or `production`. |
| `exporter-endpoint` | `AISDLC_TELEMETRY_EXPORTER_ENDPOINT` | empty | Private Collector endpoint. |
| `exporter-timeout` | `AISDLC_TELEMETRY_EXPORTER_TIMEOUT` | `PT10S` | Bounded to 1–30 seconds. |
| `trace-sample-ratio` | `AISDLC_TELEMETRY_TRACE_SAMPLE_RATIO` | `0.1` | Root-span sampling ratio, bounded to 0.0–1.0. |
| `accept-remote-trace-context` | `AISDLC_TELEMETRY_ACCEPT_REMOTE_TRACE_CONTEXT` | `true` | When false, every request starts a new root instead of continuing an inbound trace. |

Validation runs at startup and fails closed. The following configurations are rejected:

- an exporter endpoint that is not HTTPS, except plain HTTP to a loopback host while `environment` is `development`;
- an endpoint that embeds user information, a query string, or a fragment;
- `enabled` without an endpoint, or an export timeout outside the bounded range;
- a deployment environment outside the supported set, which prevents a tenant or project value from becoming a resource attribute;
- a sample ratio outside 0.0–1.0, a contract-version mismatch, or an operator-supplied resource attribute outside the allowlist.

### Resource and Attribute Contract

`TelemetryAttributeContract` is the single source of the allowlists. Resource attributes and metric labels are strict allowlists; span attributes additionally pass a prohibited-token scan as defense in depth. The Collector transform stage in [`infra/observability/otelcol-gateway.yaml`](../infra/observability/otelcol-gateway.yaml) repeats these removals, but the application is expected not to produce a prohibited value at all.

| Signal | Permitted keys |
|---|---|
| Resource | `service.name`, `service.namespace`, `service.version`, `service.instance.id`, `deployment.environment.name`, `aisdlc.telemetry.contract` |
| Span | `http.request.method`, `http.route`, `http.response.status_code`, `server.address`, `aisdlc.operation`, `aisdlc.outcome`, `aisdlc.policy.bundle_version`, `aisdlc.correlation_id` |
| `aisdlc_sli_events_total` | `service`, `environment`, `journey`, `outcome` (exactly `good` or `bad`) |
| `aisdlc_slo_target_ratio` | `service`, `environment`, `journey`, `window` |

Prohibited tokens cover credentials and authorization material, prompts and completions, tool arguments, request and response bodies, raw database statements, full URLs and query strings, and tenant/project/user/session/subject/evidence identifiers. Governance span outcomes are limited to `success`, `rejected`, `failed`, and `timeout`.

Tenant, project, user, session, request, and trace identifiers are excluded from metric labels so metric identity stays bounded. When an operator needs a trace-to-audit pivot, the linkage is read through the control plane under existing tenant/project authorization; it is not solved by exporting the identifier.

### W3C Trace Context Propagation

`TraceContextFilter` binds a `W3CTraceContext` to every management-server request, immediately after the existing correlation filter and before rate limiting.

- An acceptable inbound `traceparent` is **continued**, not replaced: the trace identifier and the sampling decision are inherited, the inbound span identifier becomes the parent, and only a new span identifier is generated.
- A new root is created only when no acceptable context is present. Version `ff`, an all-zero trace or parent identifier, a malformed field, and a version-`00` header with extra fields are all unacceptable. A higher version is accepted by parsing its first four fields.
- An unparsable or oversized (more than 32 members) `tracestate` is discarded while the `traceparent` still continues the trace.
- Root sampling is deterministic in the trailing 64 bits of the trace identifier, so independent services derive the same decision for the same trace without coordination.
- `traceId` and `spanId` are placed in the logging context and included in the JSON log encoder; both are removed, and the thread-local context cleared, even when the downstream chain throws.
- Trace identifiers are **not** returned to the caller. `X-Correlation-Id` remains the client-facing handle.

`W3CTraceContext.outboundHeaders()` renders the version-`00` `traceparent` and, when present, the forwarded `tracestate` for outbound calls. Wiring it into the outbound HTTP clients, and adding the same propagation to the SSR portal, belongs to P3.1 Sprint 2 instrumentation; today a portal-to-API request starts a new root at the API boundary.

The agent deployment profile and the domain instrumentation built on this contract are described in [`observability-and-resilience.md`](observability-and-resilience.md).

### Verification

```sh
mvn -pl management-server test
```

The contract tests are `TelemetryPropertiesTest`, `TelemetryAttributeContractTest`, `W3CTraceContextTest`, and `TraceContextFilterTest` under `management-server/src/test/java/ai/xdev/aisdlc/telemetry/`. They assert the disabled-by-default posture, every rejection rule above, the resource and metric-label allowlists, the prohibited-token scan, trace continuation and root creation, deterministic sampling, logging-context lifecycle, and that no trace identifier reaches the response.

### References

[1] [W3C, Trace Context](https://www.w3.org/TR/trace-context/)

[2] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[3] [OpenTelemetry, Transforming Telemetry](https://opentelemetry.io/docs/collector/transforming-telemetry/)

---

## P3.1: OpenTelemetry Agent Packaging and Governance Instrumentation

**Status:** Implemented and disabled by default. No deployment exports telemetry until an operator enables it.
**Scope:** Sprint 1 agent deployment profile and Sprint 2 domain instrumentation. Collector deployment, SLI recording rules, dashboards, and burn-rate alerting remain open.

Builds on the configuration model and trace-context contract in [`observability-and-resilience.md`](observability-and-resilience.md).

### Agent Packaging

Both runtime images carry a pinned OpenTelemetry Java agent:

| Property | Value |
|---|---|
| Version | `2.30.0` |
| SHA-256 | `9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d` |
| Location in image | `/opt/opentelemetry/opentelemetry-javaagent.jar`, root-owned, mode `0444` |

The agent is downloaded in a separate build stage and verified with `sha256sum -c`, so a compromised or truncated download fails the image build rather than shipping an unverified agent into a governed runtime. Both values are build arguments, so an upgrade is a reviewable one-line change with a new digest.

### Conditional Attachment

`ENTRYPOINT` is [`infra/observability/entrypoint-with-optional-agent.sh`](../infra/observability/entrypoint-with-optional-agent.sh), not a bare `java -jar`. Its contract:

| Condition | Behaviour |
|---|---|
| `AISDLC_TELEMETRY_ENABLED` unset or `false` | Plain JVM: no `-javaagent`, no `otel.*` system property, no exporter. Byte-for-byte the previous startup behaviour. |
| Enabled, agent readable, endpoint set | Attaches the agent and pins service name, namespace, `deployment.environment.name`, the telemetry contract version, the OTLP endpoint and protocol, `parentbased_traceidratio` sampling, and `tracecontext,baggage` propagation. |
| Enabled, agent missing or unreadable | Exit 78. A deployment must not believe it is observed when it is not. |
| Enabled, `AISDLC_TELEMETRY_EXPORTER_ENDPOINT` empty | Exit 78, for the same reason. |

The application owns the resource identity: the entrypoint passes `otel.service.name` and `otel.resource.attributes` explicitly rather than letting the agent infer them, so the exported resource matches `TelemetryAttributeContract`.

### Instrumented Operations

`GovernanceTelemetry` adds manual spans, a duration histogram, and one service-level indicator event per operation. Automatic instrumentation from the agent covers Spring MVC, JDBC, and outbound HTTP; these are the domain operations it cannot infer.

| Component | Operation | Journey | Reliability question |
|---|---|---|---|
| `PolicyEvaluationService` | `aisdlc.policy.evaluate` | `policy-decision-latency` | Are governance policies evaluated within budget? |
| `ApprovalOrchestrationService` | `aisdlc.approval.transition` | `approval-orchestration` | Are mandatory human decisions completing? |
| `EvidenceRepositoryService` | `aisdlc.evidence.write` | `evidence-durability` | Can governed evidence be persisted? |
| `ScmIntegrationService` | `aisdlc.scm.ingest` | `scm-ingestion-freshness` | Are webhooks accepted and processed once? |
| `NotificationService` | `aisdlc.notification.dispatch` | `notification-timeliness` | Are governance notifications arriving? |
| `AuditService` | `aisdlc.audit.append` | `audit-correctness` | Is governance evidence continuously appendable? |
| `AuditLedgerHealthIndicator` | `aisdlc.health.audit_ledger` | `control-plane-availability` | Is the audit dependency healthy? |

Outcomes come from the bounded vocabulary `success`, `rejected`, `failed`, `timeout`. A thrown failure is classified without exposing its message: a timeout type maps to `timeout`, `SecurityException` and `IllegalArgumentException` map to `rejected`, everything else to `failed`. The cause chain is walked with a cycle guard.

#### Metric Names

| Instrument | OTel name | Prometheus name after export |
|---|---|---|
| SLI counter | `aisdlc.sli.events` | `aisdlc_sli_events_total` |
| Operation duration | `aisdlc.operation.duration` | `aisdlc_operation_duration_milliseconds` |

`aisdlc_sli_events_total` is the metric the recording rules in [`p3-slo-burn-rate-rules.yaml`](../infra/observability/p3-slo-burn-rate-rules.yaml) consume, with labels `service`, `environment`, `journey`, `outcome` and `outcome` restricted to `good` or `bad`. The label set is asserted on every emission, so a future change cannot silently widen metric cardinality.

### Fail-Open Behaviour

Instrumentation observes; it never changes a result.

- Without an agent attached, `GlobalOpenTelemetry` resolves to a no-op implementation, so the default build and every existing test path are unaffected.
- The recording path catches its own failures. A telemetry error cannot turn a healthy governance operation into an error, and a rejected outcome value is dropped rather than thrown.
- Operation results and exceptions pass through unchanged — the original exception instance is rethrown, not wrapped.

Governance evidence continues to fail closed through the audit, policy, and approval paths; see [`observability-and-resilience.md#p31-resilience-fault-injection-adapters-and-scenario-tests`](observability-and-resilience.md#p31-resilience-fault-injection-adapters-and-scenario-tests).

### Verification

```sh
mvn -pl management-server test
sh scripts/test-agent-entrypoint.sh
```

`GovernanceTelemetryTest` covers result pass-through, single invocation, unwrapped rethrow for checked and unchecked failures, the outcome vocabulary including deep and self-referencing cause chains, fail-open on an invalid outcome, and the SLI label contract.

`scripts/test-agent-entrypoint.sh` drives the entrypoint with a recording `java` stub, so it proves which JVM arguments each configuration produces without starting a JVM or contacting a network. It also asserts that both Dockerfiles pin and verify an agent digest. CI runs it in the observability configuration job.

### References

[1] [OpenTelemetry Java, Automatic Instrumentation](https://opentelemetry.io/docs/zero-code/java/agent/)

[2] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[3] [OpenTelemetry, Prometheus Compatibility](https://opentelemetry.io/docs/specs/otel/compatibility/prometheus_and_openmetrics/)

---

## P3.1 SLO Runbooks

Every burn-rate and integrity alert links to a section here. The anchor is the journey label on the alert, so a new
objective in [`p3-slo-definitions.yaml`](../infra/observability/p3-slo-definitions.yaml) needs a section with the same
name; `scripts/test-observability-contracts.sh` fails the build when one is missing.

**Two rules apply to every response below.**

1. Telemetry is diagnostic. A degraded Collector or telemetry backend is never a reason to bypass a governance control,
   and never on its own an incident affecting users.
2. Nothing from a prompt, model output, tool argument, evidence body, raw audit payload, or credential goes into an
   incident channel, ticket, or alert annotation. Reference the correlation ID and the audit record instead, and read
   them through the control plane under normal authorization.

### Current objectives

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

### control-plane-availability

**Signal.** Valid authenticated API requests returning a non-5xx outcome, plus the authenticated synthetic journey.

**First checks.** Readiness (`/actuator/health/readiness`) including the `db` and `auditLedger` groups; recent
deployment version; PostgreSQL availability and connection saturation; whether `scripts/synthetic-health-journey.sh`
fails at the token, health, or authorized-read stage.

**Likely causes.** Database unavailable or saturated, Keycloak/JWKS unreachable so every request fails authentication,
or a bad deployment.

**Do not.** Disable authentication, widen `/api/**` authorities, or restart to clear a symptom before capturing which
dependency failed.

### policy-decision-latency

**Signal.** `PASS`/`FAIL`/`WARN` evaluations completing inside the latency budget.

**First checks.** `aisdlc.policy.evaluate` duration distribution; whether a recently activated policy bundle changed;
CEL expression complexity in the active bundle; database latency, since evaluation reads bundle state.

**Likely causes.** An expensive expression in a newly promoted bundle, or evaluation contending with database load.

**Do not.** Bypass or disable policy evaluation to restore latency. A missing policy result is a `DENY`, and that is
the intended behaviour — degrade the action, not the control.

### approval-orchestration

**Signal.** Approval transitions completing without an orchestration failure.

**First checks.** Whether failures concentrate on one project or approver; the approval SLA and reminder scheduler;
notification delivery, since an approver who is never notified cannot decide.

**Likely causes.** Notification outage upstream, quorum or delegation misconfiguration, or scheduler contention.

**Do not.** Auto-approve, lower quorum, or decide on an approver's behalf to clear a backlog. Human approval is a
platform invariant.

### notification-timeliness

**Signal.** Eligible notifications delivered or terminally failed with an auditable receipt inside the window.

**First checks.** Delivery receipts by outcome; attempt counts against the retry policy; whether one channel type
dominates the failures; provider status.

**Likely causes.** Provider outage or rate limiting, an expired channel secret, or a misconfigured destination.

**Do not.** Copy recipient addresses or channel secrets into the incident. A provider outage keeps deliveries
retryable and leaves the approval outcome unchanged; that is the designed behaviour.

### scm-ingestion-freshness

**Signal.** Valid signed webhooks accepted and durably recorded inside the window.

**First checks.** Signature rejection versus processing failure; duplicate delivery identifiers; GitHub App
installation and delivery backlog; database write latency.

**Likely causes.** Provider retry storm, a rotated webhook secret, or database contention.

**Do not.** Disable signature verification, or replay deliveries manually without confirming the idempotency marker.
Ingestion is idempotent by delivery identifier; let the sender retry.

### audit-correctness

**Integrity objective — zero tolerated failures. A single occurrence is a security incident, not a budget burn.**

**Signal.** `aisdlc_audit_integrity_failures_total`, incremented when hash-chain verification finds a break.

**Immediate actions.** Preserve state — do not truncate, repair, or re-run migrations against `audit_events`. Record
the organization and the first invalid sequence from the verification result. Treat every governance decision after
that sequence as unverified until reviewed. Engage security engineering.

**Do not.** Rewrite or delete audit rows. The table is append-only at the database level; an attempt to modify it is
itself a finding worth capturing.

### evidence-durability

**Integrity objective — zero tolerated failures.**

**Signal.** `aisdlc_evidence_integrity_failures_total`, incremented when a stored digest does not match.

**Immediate actions.** Preserve the object and its metadata row. Confirm whether object-lock retention is intact.
Identify releases or approvals that referenced the affected evidence. Evidence-dependent actions fail closed by
design; keep them closed until the mismatch is explained.

**Do not.** Re-upload over the affected key, or mark provenance verified to clear the alert.

### governance-integrity

The routing anchor for `AiSdlcAuditOrEvidenceIntegrityViolation`, which fires for either integrity objective. Follow
the matching section above based on which counter increased, and note that this alert bypasses grouping delay and
suppresses burn-rate noise for the same service while it is open.

### Telemetry pipeline degradation

Not an SLO, but the most common false alarm. If alerts stop arriving or SLI series go stale, check the Collector
before concluding the platform is healthy: an absent signal is not a good signal. The Collector sheds telemetry under
memory pressure by design and never blocks a user request. See
[`observability-and-resilience.md#p31-resilience-fault-injection-adapters-and-scenario-tests`](observability-and-resilience.md#p31-resilience-fault-injection-adapters-and-scenario-tests) for the fail-open boundary.

---

## P3.1 Resilience: Fault-Injection Adapters and Scenario Tests

**Status:** Implemented for the unit and service tiers. The disposable-chaos and game-day tiers in [`observability-and-resilience.md#p31-reliability-resilience-and-chaos-engineering-test-plan`](observability-and-resilience.md#p31-reliability-resilience-and-chaos-engineering-test-plan) remain approval-gated and are not automated here.
**Scope:** Chaos seams for every declared component, and deterministic scenarios asserting that telemetry fails open while governance fails closed.

### The Degradation Model Under Test

The platform draws one line: **telemetry is diagnostic and fails open; governance evidence is authoritative and fails closed.** Losing a trace backend must never turn a valid request into an error, and losing the policy engine, evidence store, identity provider, or audit path must never let a governed action through.

`TraceContextFilter` now implements the open half explicitly. Establishing trace context is wrapped so that any runtime failure degrades observability for that request — no trace identifiers in the logs, no bound context — while the request itself proceeds and the thread-local state is left clean. The closed half is enforced by each governance path propagating its failure.

### Fault-Injection Adapters

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

### Scenario Coverage

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

### Component Coverage Matrix

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

### What Is Not Automated Here

The five cases marked *not automated* need a running Collector, database, scheduler, or container resource limits. They stay in the disposable-chaos tier and require the approval gate, blast-radius controls, abort criteria, and evidence capture defined in [the chaos test plan](observability-and-resilience.md#p31-reliability-resilience-and-chaos-engineering-test-plan). Nothing in this implementation enables a destructive action, targets a shared environment, or accepts fault parameters over HTTP.

One of the five is partly closed by other means: `RES-DB-03` asks whether governance state survives losing PostgreSQL, and `scripts/verify-recovery.sh` answers the recovery half of that on a disposable database — backup, restore, and a hash-chain verification that must reach the same head digest. What it does not answer is behaviour *during* the outage, which still needs a running database to interrupt.

### Verification

```sh
mvn -pl management-server test
```

`ResilienceScenarioTest` and `ChaosFaultRegistryProfileIntegrationTest` run together; the latter continues to prove that no chaos bean exists outside the explicit profile.

### References

[1] [AWS Fault Injection Service: safety controls](https://aws.amazon.com/fis/features/)

[2] [Google SRE Book: Testing Reliability](https://sre.google/sre-book/testing-reliability/)

---

## P3.1 Reliability: Resilience and Chaos Engineering Test Plan

**Status:** implementation-ready reference  
**Audience:** platform engineering, SRE, security engineering, and governance operators  
**Scope:** P3.1 reliability controls only; the plan does not authorize experiments against production.

### 1. Purpose and Safety Model

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

### 2. Test Harness Architecture

The P3.1 harness must use dependency adapters rather than runtime shell access. A `FaultInjectionProfile` is explicitly selected by CI and can inject delay, timeout, connection refusal, deterministic 5xx, bounded queue pressure, a stale token, or an object-store write failure. It cannot target a hostname, environment, or credential not declared by the isolated compose fixture.

The test profile is not packaged in a production deployment. Production runtime code has no management endpoint capable of enabling chaos. Where an underlying dependency must be emulated, use a container-local proxy or a fake adapter controlled only by the test process. The pipeline must run destructive concurrency and storage tests serially and retain diagnostics as test evidence.

### 3. Detailed Test Cases

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

The unit and service tiers of this plan are implemented; see [`observability-and-resilience.md#p31-resilience-fault-injection-adapters-and-scenario-tests`](observability-and-resilience.md#p31-resilience-fault-injection-adapters-and-scenario-tests) for the seam placement, the automated case coverage, and the cases that remain approval-gated.

### 4. Game-Day Procedure

| Stage | Operator action | Exit criterion |
|---|---|---|
| Prepare | Review manifest digest, verify isolated environment, establish baseline SLIs, acknowledge stop conditions, and nominate incident lead/observer. | Baseline probes green for five minutes and evidence sink reachable. |
| Inject | Enable exactly one predefined fault profile for its approved duration. | The target and fault match the signed manifest; no unrelated service is affected. |
| Observe | Record availability, latency, audit/evidence integrity, queue depth, retries, and operator actions. | Hypothesis is proved or disproved with retained evidence. |
| Abort or recover | Immediately remove the fault on any stop condition; otherwise let the controlled duration elapse. | Dependency health and service probes meet recovery target. |
| Verify | Run reconciliation, idempotency, tenant-isolation, and evidence-digest checks. | No duplicate, missing, cross-tenant, or unverifiable record remains. |
| Review | Publish outcome, SLO impact, remediation owner/date, and whether the scenario may advance to a broader non-production environment. | Human reviewer accepts the evidence; no automatic promotion occurs. |

### 5. Automated Test Tiers and Acceptance Criteria

The unit and integration tiers run on every pull request. The disposable chaos tier runs only with explicit non-production environment approval. A production game day is out of scope until the project has collected a 28-day SLO baseline and an operations review has approved the exact experiment manifest.

| Tier | Frequency | Allowed scenarios | Pass threshold |
|---|---|---|---|
| Unit | Every change | Adapter faults, retry bounds, fail-open/fail-closed decisions | 100% deterministic tests pass. |
| Integration | Every change affecting a dependency contract | `RES-OTEL-01`, `RES-POL-05`, `RES-SCM-07`, `RES-NOTIFY-08` | No unsafe side effect; test evidence retained. |
| Disposable chaos | Manual, approval-gated | All cases except production-only provider experiments | Recovery within budget; no integrity or tenant-isolation violation. |
| Game day | Quarterly after baseline approval | A reviewed subset with one narrow failure mode | Review board accepts evidence and remediation plan. |

### References

[1]: https://aws.amazon.com/fis/features/ "AWS Fault Injection Service: safety controls"
[2]: https://sre.google/sre-book/testing-reliability/ "Google SRE Book: Testing Reliability"
