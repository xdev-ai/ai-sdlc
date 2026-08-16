# P3.1 Reliability and P3.3 Runtime AI Governance Design

**Status:** Proposed implementation design
**Owner:** Platform Engineering and Governance Engineering
**Scope:** Post-P2 roadmap design; no runtime AI provider is enabled by this document.

## 1. Executive Summary

P3.1 establishes an observable and measurable reliability posture for the AI-SDLC control plane. It introduces a vendor-neutral OpenTelemetry (OTel) pipeline, privacy-safe instrumentation, service-level indicators (SLIs), service-level objectives (SLOs), error budgets, alert routing, synthetic checks, and operational runbooks. It extends the current Actuator readiness baseline rather than replacing it.

P3.3 introduces a **runtime AI governance gateway**. The gateway is a policy enforcement point between an authenticated agent workload and an approved model provider or tool. It is not an autonomous delivery mechanism, an approval substitute, or an uncontrolled model proxy. The design preserves three platform invariants: the deterministic validator never invokes AI, `--bare` remains prohibited and an explicit model pin remains mandatory, and a human approval remains mandatory at every delivery decision point.

> OpenTelemetry semantic conventions provide common names for operations and data across traces, metrics, logs, profiles, and resources.[1]

> NIST describes the Generative AI Profile as a companion implementation resource for governing, mapping, measuring, and managing generative-AI risk through its lifecycle.[2]

## 2. Current-State Assessment

The management server currently exposes Spring Boot Actuator `health`, `info`, and `metrics`. Its readiness group includes the application readiness state, PostgreSQL, and the audit ledger. It already has a request correlation filter, JSON logs, immutable audit chaining, role-aware project access, CEL-based policy evaluation, evidence storage, notification orchestration, and an agent-governance evidence ledger.

The existing agent-governance module deliberately records only prompt/context/tool fingerprints and does not run a model, store raw prompt content, grant production access, or treat an agent as an approver. P3.3 must reuse these safety properties. It must not turn the current evidence ledger into a general-purpose prompt archive or bypass the approval orchestration service.

| Existing asset | P3.1 usage | P3.3 usage |
|---|---|---|
| Actuator liveness/readiness | Base health checks and synthetic target | Gateway dependency/readiness input only; not authorization evidence |
| `X-Correlation-Id` filter | Link request logs, spans, audit records, and incident tickets | Link a runtime decision to its policy and approval evidence |
| CEL policy engine | Instrument policy evaluation duration/outcome | Evaluate bounded, canonical pre-flight and post-flight contexts |
| Audit hash chain | Audit SLO and tamper-evidence health | Record authority, policy, decision, and evidence fingerprints |
| Agent session/evidence ledger | Agent-governance SLIs | Existing session and human-approval linkage; no bypass |
| Keycloak JWT resource server | Authentication telemetry | Human identities and initial service-account workload authentication |

## 3. P3.1 Reliability: Target Architecture

### 3.1 Reference Topology

The production reference topology is **application instrumentation → private OTel Collector gateway → organization-selected telemetry backends**. The Collector is the only component permitted to export application telemetry outside the private application network. It receives OTLP over mutually authenticated TLS in production, applies redaction and sampling, performs retry/queue management, and routes traces, metrics, and logs to separate approved backends. The design does not require a particular commercial observability vendor.

```text
Portal SSR ─────┐
Management API ─┼─ OTLP/mTLS ─> OTel Collector Gateway ─> Trace backend
Keycloak* ──────┤                                  ├───> Metrics backend
Synthetic jobs ─┘                                  └───> Log backend
       │
       └─ W3C trace context / X-Correlation-Id ─> Audit evidence linkage

* Keycloak is monitored externally and through private health checks. It is not modified by P3.1.
```

The first implementation uses the OpenTelemetry Java agent for broad, low-risk automatic instrumentation. It adds manual spans and metrics only for platform-specific governance operations that cannot be inferred automatically. The Java instrumentation guidance recommends beginning with the agent; it supports automatic library detection while allowing manual instrumentation through the same global telemetry instance.[3]

### 3.2 Resource, Propagation, and Attribute Contract

All application signals must use W3C trace context propagation and the same correlation ID already returned by the API. The instrumentation adapter must not replace an inbound valid trace context; it must create a new root only when no acceptable context is present. Browser-facing code propagates a trace context only to same-origin portal/API calls. Cross-origin propagation remains denied by the existing CORS policy unless explicitly reviewed.

| Attribute class | Allowed examples | Prohibited examples |
|---|---|---|
| Standard resource | `service.name`, `service.version`, `service.namespace`, `deployment.environment.name` | Runtime secrets, database URL, internal topology unrelated to operations |
| Request span | HTTP route template, method, status class, sanitized correlation ID | Raw query string, `Authorization`, session cookie, bearer token |
| Governance span | Stable operation name, policy bundle version, decision outcome, bounded latency bucket | Prompt text, model output, tool arguments, evidence content, personal data |
| Metrics dimension | Service, operation class, status class, policy outcome, provider class | Project/tenant/user IDs, request ID, trace ID, prompt hash, model output hash |
| Error event | Exception class and sanitized error code | Stack traces containing inputs, credential values, raw provider response |

Project, tenant, session, approval, and evidence identifiers are intentionally excluded from metrics labels to prevent unbounded cardinality. They must also be absent from third-party telemetry by default. When an operator needs a trace-to-audit pivot, the gateway stores the linkage in the platform audit record and grants access only through existing tenant/project authorization; it does not solve the problem by exporting sensitive attributes.

### 3.3 Required Instrumentation

Automatic instrumentation covers inbound Spring MVC requests, outbound HTTP, JDBC/JPA, JVM runtime, and supported HTTP clients. Manual instrumentation adds named spans and counters for the domain operations below. Each manual operation must emit a bounded outcome (`success`, `rejected`, `failed`, `timeout`) and no free-form user value.

| Component | Span or metric | Primary reliability question |
|---|---|---|
| Policy evaluation | `aisdlc.policy.evaluate`; outcome and duration histogram | Are governance policies evaluated correctly and within latency budget? |
| Approval orchestration | `aisdlc.approval.transition`; overdue and escalation counters | Are mandatory human decisions routed and completed? |
| Evidence repository | `aisdlc.evidence.write/read`; integrity-validation counter | Can governed evidence be persisted and verified? |
| SCM ingestion | `aisdlc.scm.ingest`; idempotency and signature-rejection counters | Are webhook events accepted safely and processed once? |
| Notifications | `aisdlc.notification.dispatch`; queued/attempted/delivered counters | Are governance notifications arriving in time? |
| Audit ledger | `aisdlc.audit.append/verify`; chain-failure counter | Is governance evidence continuously appendable and verifiable? |
| Runtime AI gateway | `aisdlc.ai.preflight/provider/tool/postflight`; decision and budget metrics | Is a permitted action governed, bounded, and attributable? |

### 3.4 Collector Security and Resilience Controls

The Collector gateway must run privately, authenticate application exporters, and hold no application secrets beyond its backend credentials. It must apply a deterministic transform processor before export to delete disallowed attributes, a memory limiter before batching, bounded retry queues, a circuit breaker or exporter timeout, and separate pipelines by signal. If a backend becomes unavailable, the Collector may shed normal telemetry according to an explicitly documented policy; it must never block a user request solely because an external trace backend is unavailable.

Security-relevant or decision-relevant evidence is different: the authoritative audit record must commit transactionally before a runtime action can be released. Telemetry is diagnostic and cannot be the only system of record. This distinction preserves reliable governance when telemetry infrastructure is degraded.

| Failure | Application behavior | Operator action |
|---|---|---|
| Trace/log backend unavailable | Continue service; bounded Collector queue and sampling prevent cascade | Alert platform operations; investigate exporter and capacity |
| Collector unavailable | Continue normal application service with bounded local exporter failure; do not retry per request indefinitely | Page observability owner when loss threshold is crossed |
| PostgreSQL/audit unavailable | Readiness becomes unhealthy; governed mutations and runtime AI actions fail closed | Incident response; do not bypass audit evidence |
| Policy engine error | Decision is `ERROR`; action is blocked unless an existing, documented human exception workflow applies | Review policy bundle and evidence |
| Object storage integrity failure | Evidence operation fails; release-impacting paths fail closed | Preserve metadata, start evidence incident |

## 4. P3.1 SLIs, SLOs, and Error Budgets

The objectives below are **initial proposed targets**, not historical claims. The first 28 days of instrumented operation establish baseline volume and latency distributions. Targets can change only through the normal policy/architecture review, with a recorded rationale. User-caused 4xx responses, deliberately denied requests, and planned maintenance windows announced under the operations policy are excluded only where the SLI definition explicitly says so; every exclusion must remain auditable.

| Service journey | SLI definition | Initial 30-day objective | Error-budget implication |
|---|---|---:|---|
| Public control-plane availability | Valid authenticated API requests returning a non-5xx outcome ÷ valid authenticated API requests | 99.90% | 43.2 minutes equivalent unavailability/month |
| Policy decision latency | `PASS`, `FAIL`, or `WARN` evaluation within 500 ms ÷ completed evaluations | 99.95% | 21.6 minutes equivalent latency breach/month |
| Audit append correctness | Valid governance mutations with a linked, verified audit entry ÷ valid governance mutations | 100% | Zero tolerated missing/invalid entries; immediate incident |
| Evidence write durability | Accepted evidence writes retrievable with matching SHA-256 on verification ÷ accepted evidence writes sampled or fully verified | 100% | Zero tolerated integrity mismatch; immediate incident |
| Notification timeliness | Eligible notifications delivered or terminally failed with an auditable receipt within 15 minutes ÷ eligible deliveries | 99.50% | 216 minutes equivalent breach/month |
| SCM ingestion freshness | Valid signed webhooks accepted and durably recorded within 60 seconds ÷ valid signed webhooks | 99.90% | 43.2 minutes equivalent breach/month |
| Runtime AI governance decision | Authorized, budgeted runtime requests ending in an auditable `ALLOWED`, `BLOCKED`, or `PENDING_APPROVAL` decision within 2 seconds, excluding provider generation time | 99.90% | 43.2 minutes equivalent decision-latency breach/month |

The `100%` objectives are **integrity objectives**, not conventional availability targets. A single verified missing audit link or evidence digest mismatch is a security and governance incident, not an error budget to burn. Such events must be retained and escalated even if the associated request eventually succeeds.

Alerting uses multi-window burn-rate rules after baseline collection. A fast burn pages the primary on-call when a journey is likely to exhaust a material share of its 30-day budget rapidly; a slow burn creates a ticket or business-hours alert. Alert payloads must include service, journey, environment, SLO, current burn rate, correlation/trace link if authorized, deployment version, and a link to the relevant runbook. They must never include prompt content, tokens, raw evidence, or credentials.

### 4.1 P3.1 Implementation Sequence

| Step | Deliverable | Acceptance criterion |
|---:|---|---|
| 1 | `TelemetryProperties`, data-classification policy, resource contract, and OTLP configuration | Configuration defaults to disabled/no exporter; validation rejects insecure production endpoint configuration |
| 2 | Java agent packaging and deterministic manual instrumentation adapter | Existing full test suite passes with telemetry disabled and enabled |
| 3 | Collector reference deployment, mTLS, redaction, batching, retry, and routing configuration | Contract test proves banned attributes are removed before export |
| 4 | SLI metric instruments and versioned SLO definitions | Every SLI has numerator, denominator, exclusions, owner, and runbook link |
| 5 | Dashboards, multi-window burn alerts, synthetic checks, and incident integration | Synthetic failure reaches correct alert route without leaking sensitive input |
| 6 | Telemetry, cardinality, privacy, and Collector-resilience tests | Load test demonstrates bounded memory/queue behavior and no metric identity explosion |
| 7 | Operations documentation and controlled rollout | One project in observe-only mode completes 28-day baseline before broad enforcement |

### 4.2 Implementation Reference Artefacts

The initial implementation artefacts are versioned with the repository so that deployment, policy, and test work starts from an auditable baseline rather than unreviewed console configuration.

| Artefact | Purpose | Validation status |
|---|---|---|
| [`infra/observability/otelcol-gateway.yaml`](../infra/observability/otelcol-gateway.yaml) | Private mTLS Collector gateway with allowlisting, redaction, memory limiting, batching, bounded retry, and backend routing. | YAML structure reviewed; the P3.1 selected digest-pinned Collector distribution must run its `validate` command before deployment. |
| [`infra/observability/p3-slo-burn-rate-rules.yaml`](../infra/observability/p3-slo-burn-rate-rules.yaml) | Versioned SLI recording rules, fast/slow multi-window burn rules, and zero-tolerance audit/evidence integrity alert. | Prometheus rule syntax and alert routing must be checked by the P3.1 monitoring stack before enabling paging. |
| [`management-server/src/test/resources/runtime-ai-governance-policies/`](../management-server/src/test/resources/runtime-ai-governance-policies/) | CEL bundle samples for workload/model allowlisting, input/tool containment, post-flight approval, and emergency override. | Evaluated through the real `PolicyExpressionEngine` in `RuntimeAiGovernancePolicySamplesTest`. |

## 5. P3.3 Runtime AI Governance: Architecture and Requirements

### 5.1 Scope and Non-Goals

The gateway governs runtime interactions that an approved application or agent makes with an approved model provider and registered tools. It supports **declaration, deterministic enforcement, evidence, budget control, and human decision gates**. It does not provide a conversational UI, build a general model hosting platform, train/fine-tune models, or authorize direct merge/release/deployment. The existing deterministic validator remains fully separate and never calls a model.

Prompt injection remains a core threat: external or user-derived content can alter model behavior and can enable unauthorized function access or critical-decision influence. OWASP therefore advises strict privilege control, code-mediated function handling, clearly segregated untrusted content, and human approval for high-risk actions.[4] The runtime architecture treats all model output and tool-observed content as untrusted data.

### 5.2 Logical Architecture

```text
Approved workload / agent
  │  workload credential + request digest + idempotency key
  ▼
Runtime AI Governance Gateway
  ├─ Identity & tenant/project authorization
  ├─ Pre-flight classifier, budget, model/tool allowlist, CEL policy evaluation
  ├─ Decision ledger / transactional audit outbox
  ├─ Provider adapter (egress allowlist, per-provider credentials)
  ├─ Tool broker (capability grants, JSON-schema validation, approval hold)
  └─ Post-flight validator, classifier, evidence fingerprinting
       │                       │
       │                       ├─ existing AgentGovernanceService
       │                       ├─ existing ApprovalOrchestrationService
       │                       ├─ PolicyEvaluationService
       │                       └─ AuditService / evidence repository
       ▼
Approved model provider(s) and registered tools only
```

The gateway is a dedicated internal service boundary, initially delivered as a module in the management-server deployment only if resource isolation and threat model findings permit. The recommended production destination is a separately deployable `runtime-ai-gateway` service with the existing management server remaining the policy/control plane. This separation limits blast radius, makes outbound provider networking explicit, and permits independent scaling and credential rotation.

### 5.3 Trust Boundaries and Identity

Human users authenticate through the existing Keycloak JWT flow. Agent workloads must not impersonate human users or receive `admin`, `developer`, `reviewer`, or `viewer` authority. The initial implementation uses short-lived Keycloak client-credential access tokens with a dedicated runtime audience and a dedicated `agent_runtime` authority. The gateway validates issuer, audience, authorized party, expiry, workload identity, tenant, project, and a bound agent configuration before it evaluates any policy.

Each runtime workload has a human sponsor, tenant, project scope, allowed environment, allowed provider/model versions, tool capability set, policy bundle/version, rate limit, concurrency budget, and spend/token budget. Rotatable provider credentials stay in the gateway secret store and are never supplied to an agent or copied into the control-plane database.

For Kubernetes or cross-environment enterprise deployments, P3.2 may add SPIFFE/SPIRE workload attestation and mTLS. SPIFFE provides cryptographically attested workload identities and cross-service authentication for zero-trust systems.[5] It is an extension path, not a prerequisite for the P3.3 initial deployment because Keycloak is already the established platform identity system.

| Identity type | Permitted use | Never permitted |
|---|---|---|
| Human Keycloak subject | Configure providers, policies, tools, approvals, and review evidence according to role | Directly expose provider credential; approve their own restricted override if segregation policy prohibits it |
| Agent workload identity | Submit a scoped runtime request; receive a gateway decision; call a granted tool through broker | Satisfy approval quorum; call model provider/tool directly; mutate policy or model allowlist |
| Gateway service identity | Fetch provider credentials, append decision evidence, dispatch approved provider/tool calls | Delegate policy ownership to a provider; bypass audit transaction |
| Tool execution identity | Execute a one-use, scoped capability grant | Reuse grant, broaden scope, perform arbitrary shell/database/network action |

### 5.4 Decision Model and Lifecycle

Every request follows a deterministic lifecycle. A provider call is possible only after pre-flight admits it; tool execution is possible only through the broker; a release-impacting action remains `PENDING_APPROVAL` until existing human approval orchestration reaches quorum.

| Stage | Deterministic control | Allowed outcome |
|---|---|---|
| Intake | Authenticate workload; validate tenant/project/session/idempotency key | `BLOCKED` on any identity/scope failure |
| Classify | Apply deterministic data classification and content boundary rules | `BLOCKED` or redacted/context-reduced request |
| Pre-flight | Confirm declared prompt fingerprint, pinned model version, provider allowlist, budget, registered tools, and CEL policy | `ALLOWED`, `BLOCKED`, or `PENDING_APPROVAL` |
| Provider | Enforce endpoint allowlist, timeout, per-provider budget, request schema, and egress TLS | Bounded provider response or `FAILED` |
| Tool planning | Parse only schema-valid structured tool proposal; evaluate capability/policy | `ALLOWED`, `BLOCKED`, or `PENDING_APPROVAL` |
| Tool execution | Issue single-use, short-lived capability grant; broker validates arguments and target | Bounded execution receipt only |
| Post-flight | Validate output schema, apply classification and post-flight CEL policy, generate fingerprints | `COMPLETE`, `BLOCKED`, or evidence-linked approval request |
| Record | Atomically store decision metadata, policy/evidence fingerprints, audit link, and trace correlation | No action is released before authoritative audit recording |

The gateway must treat parser failures, unknown model versions, missing policy bundles, budget service errors, missing audit storage, expired approval, provider identity mismatch, or unregistered tools as **fail closed**. A timeout never becomes a policy pass. A human exception must use the existing approval and security-exception workflow with purpose, owner, expiry, and immutable audit record; it cannot be an unlogged switch or static bypass.

### 5.5 Model, Prompt, Data, and Tool Controls

The gateway accepts references and digests rather than silently accepting mutable model aliases. A request must specify `provider`, `model_name`, `model_version`, `prompt_template_key`, `prompt_template_version`, and template digest. Model profiles are versioned and include endpoint, credential reference, permitted environment, maximum context/output tokens, timeout, data residency classification, and deprecation status. A retired or incident-affected model profile blocks new requests and identifies affected sessions via existing evidence links.

Raw prompt, context, response, and tool payload retention defaults to **off**. The gateway processes data in memory and records only classification, length/token counts, canonical request/response/tool digests, provider/model/profile version, policy version, outcome, timestamps, and immutable audit identifiers. If a regulated tenant explicitly requires payload retention, it requires a separate approved evidence class, envelope encryption, strict TTL, project authorization, legal-hold compatibility, and a documented data-residency decision. That retention mode is not enabled in P3.3 initial rollout.

Tools are registered artifacts, not ad-hoc URLs or shell strings. Every tool manifest has a stable key, semantic version, digest, owner, risk tier, typed JSON Schema input/output contracts, egress target policy, capability scope, maximum execution time, and approval rule. The model can propose a tool invocation, but it cannot execute it itself. The broker owns tool credentials, deterministic argument validation, allowlisted targets, one-use capability grants, and sanitized receipts.

| Tool tier | Example | Runtime rule |
|---|---|---|
| Read-only, low-risk | Query an approved public repository metadata endpoint | Policy pass plus scoped workload grant; no secret-bearing response forwarded blindly |
| Read-only, sensitive | Retrieve internal evidence metadata | Project/tenant authorization, classification gate, redacted result, enhanced audit |
| Mutating | Create a draft issue or attach a review artifact | Proposal plus deterministic validation; approval required when policy marks impact high |
| Release-impacting | Merge, deploy, rotate credential, modify production data | Gateway never executes directly; existing human quorum approval and downstream controlled workflow remain mandatory |

### 5.6 Data Model and API Contract

P3.3 should add a new Flyway migration and independently reusable module containing at minimum the following records. All primary runtime records must carry `tenant_id` and `project_id`, enforce foreign-key scope, and be queried through tenant-aware repositories. Digests are normalized lower-case SHA-256 hexadecimal values. Provider secrets are references to the runtime secret manager, never plaintext database columns.

| Record | Selected fields | Purpose |
|---|---|---|
| `agent_workloads` | identity, sponsor, tenant/project, environment, status, allowed policy profile | Bind a non-human workload to accountable scope |
| `model_provider_profiles` | provider/model/version, endpoint allowlist, secret reference, residency, limits, status | Register immutable model/provider contract |
| `tool_manifests` | key/version/digest, risk tier, schema, capability, approval rule, status | Register controlled tools |
| `runtime_policy_bindings` | workload/model/tool/classification selector, bundle/version, enforcement mode | Resolve deterministic policy in a narrow scope |
| `runtime_requests` | idempotency key, request fingerprint, classification, lifecycle, budget snapshot, trace correlation | Record bounded request state without raw content |
| `runtime_decisions` | stage, outcome, policy evidence ID, reason code, approval request ID, audit sequence | Explain every permission decision |
| `runtime_tool_grants` | one-use nonce hash, capability, expiry, tool manifest digest, parameter fingerprint | Prevent replay and scope creep |
| `runtime_usage_ledger` | token/cost quantities, provider receipt fingerprint, time bucket | Enforce budget and support SLOs without raw payload retention |

The external API is project-scoped and versioned. It should provide an idempotent request submission endpoint, request/decision retrieval endpoints, administrative registration endpoints for workloads/models/tools/bindings, and a human-only approval/override flow. Tool dispatch APIs are internal-only and authenticated with the gateway service identity. API responses return state, reason code, policy/evidence references, and approval state; they never return hidden policy text, secret values, raw audit payloads, or provider credentials.

### 5.7 Resilience, Cost, and Safe Failure

Provider calls use separate bulkheads by provider and tenant tier, strict connect/read/overall deadlines, bounded concurrency, retries only for proven idempotent provider operations, and circuit breakers. Tool actions are never retried automatically unless the manifest explicitly declares idempotency and the gateway can prove the same idempotency key. Budget checks use a transactional reservation before provider dispatch and reconciliation against provider-reported usage. The system refuses a request when it cannot calculate or reserve budget safely.

The gateway must distinguish operational observability degradation from governance-evidence degradation. Loss of a trace backend cannot itself authorize or block an action. Loss of the audit/control-plane database, policy engine, identity validator, or required approval state blocks the action. This makes security availability fail closed while keeping diagnostics from creating an unnecessary dependency loop.

### 5.8 Test and Rollout Strategy

P3.3 requires deterministic tests with a fake provider and fake tools; no test invokes a production model. Test suites cover forged/missing workload token, audience confusion, tenant/project scope escape, retired model version, policy timeout/error, idempotency replay, budget race, tool schema violation, grant replay, prompt-injection corpus, indirect untrusted-content injection, output schema bypass, audit-store loss, and approval-expiry race. The prompt-injection corpus is treated as security test data, not model training data.

Rollout proceeds in four gates: (1) control-plane schema and APIs disabled by feature flag; (2) observe-only pre-flight decisions with no provider access; (3) one internal project using a fake/isolated provider and read-only tools; and (4) project-by-project enforcement after human owners accept policy, budget, data classification, and runbook conditions. No production deployment, merge, or destructive tool is in the initial scope.

## 6. Dependencies, Decisions, and Exit Criteria

| Workstream | Blocking dependency | Decision required before code | Exit criterion |
|---|---|---|---|
| OTel application instrumentation | Pinned compatible Java agent and Collector image | Approved attribute allowlist and telemetry retention policy | End-to-end traces/metrics/logs are correlated without prohibited data |
| SLO and alerting | Telemetry backend and incident destination | Initial target/alert ownership and maintenance exclusion policy | 28-day baseline plus tested burn-rate alerts and runbooks |
| Runtime identity | Keycloak service-account realm/client design | Audience, claims, rotation, sponsor/revocation process | Workload cannot impersonate human or cross tenant/project boundary |
| Provider profiles | Secret manager and outbound egress policy | Approved provider/model/version and data residency | Provider direct access is technically blocked for agents |
| Tool broker | Manifest schema and capability-grant format | Risk-tier/approval matrix | Tool cannot execute outside one-use, bounded, policy-approved grant |
| Runtime evidence | Existing audit/evidence/approval services | Minimal metadata vs regulated retention mode | Every action has an immutable decision/audit link; raw payload stays off by default |

## 7. Delivery Order

P3.1 is implemented first through its data-classification and instrumentation contract, then the Collector and baseline SLO measurement. P3.3 begins only after the policy-engine latency and audit-correctness SLOs are observable, because the gateway relies on those components as fail-closed dependencies. The initial P3.3 release is provider-isolated and read-only; mutations remain outside scope until the approval, tool-broker, and incident exercises pass.

| Release slice | Scope | Success condition |
|---|---|---|
| P3.1-A | OTel contract, Java agent, Collector, safe resource/attribute filters | No sensitive attributes reach test exporter; existing services remain stable |
| P3.1-B | Domain telemetry, SLI metrics, dashboards, synthetic checks | Complete 28-day baseline with documented SLO ownership |
| P3.1-C | Burn alerts, incident links, operational evidence and resilience tests | Controlled failure triggers the correct runbook and escalation |
| P3.3-A | Runtime schema, workload/model/tool registry, observe-only pre-flight | Every candidate request is classified and explained without provider access |
| P3.3-B | Isolated provider adapter, budgets, audit outbox, read-only tools | Fail-closed test matrix and tenant isolation pass |
| P3.3-C | Enforced project opt-in with human approval, post-flight controls | All decision points preserve existing human-approval invariant |

## References

[1] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[2] [NIST AI 600-1, Artificial Intelligence Risk Management Framework: Generative Artificial Intelligence Profile](https://doi.org/10.6028/NIST.AI.600-1)

[3] [OpenTelemetry Java, Instrumentation Ecosystem](https://opentelemetry.io/docs/languages/java/instrumentation/)

[4] [OWASP GenAI Security Project, LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

[5] [SPIFFE, Secure Production Identity Framework for Everyone](https://spiffe.io/)
