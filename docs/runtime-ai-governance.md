# Runtime AI governance

Governing what an AI agent may do at runtime: workload identity, model and provider allowlists, tool capability grants, the provider proxy, and the evidence each produces.

- [P3.1 Reliability and P3.3 Runtime AI Governance Design](#p31-reliability-and-p33-runtime-ai-governance-design)
- [AI-Agent Governance](#ai-agent-governance)
- [Runtime AI Workload Identity and Provider Proxy Rollout](#runtime-ai-workload-identity-and-provider-proxy-rollout)
- [P3.3 Tool Broker: Capability Grants](#p33-tool-broker-capability-grants)
- [P3.3 Provider Proxy Execution Design](#p33-provider-proxy-execution-design)

## P3.1 Reliability and P3.3 Runtime AI Governance Design

**Status:** Proposed implementation design
**Owner:** Platform Engineering and Governance Engineering
**Scope:** Post-P2 roadmap design; no runtime AI provider is enabled by this document.

### 1. Executive Summary

P3.1 establishes an observable and measurable reliability posture for the AI-SDLC control plane. It introduces a vendor-neutral OpenTelemetry (OTel) pipeline, privacy-safe instrumentation, service-level indicators (SLIs), service-level objectives (SLOs), error budgets, alert routing, synthetic checks, and operational runbooks. It extends the current Actuator readiness baseline rather than replacing it.

P3.3 introduces a **runtime AI governance gateway**. The gateway is a policy enforcement point between an authenticated agent workload and an approved model provider or tool. It is not an autonomous delivery mechanism, an approval substitute, or an uncontrolled model proxy. The design preserves three platform invariants: the deterministic validator never invokes AI, `--bare` remains prohibited and an explicit model pin remains mandatory, and a human approval remains mandatory at every delivery decision point.

> OpenTelemetry semantic conventions provide common names for operations and data across traces, metrics, logs, profiles, and resources.[1]

> NIST describes the Generative AI Profile as a companion implementation resource for governing, mapping, measuring, and managing generative-AI risk through its lifecycle.[2]

### 2. Current-State Assessment

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

### 3. P3.1 Reliability: Target Architecture

#### 3.1 Reference Topology

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

#### 3.2 Resource, Propagation, and Attribute Contract

All application signals must use W3C trace context propagation and the same correlation ID already returned by the API. The instrumentation adapter must not replace an inbound valid trace context; it must create a new root only when no acceptable context is present. Browser-facing code propagates a trace context only to same-origin portal/API calls. Cross-origin propagation remains denied by the existing CORS policy unless explicitly reviewed.

| Attribute class | Allowed examples | Prohibited examples |
|---|---|---|
| Standard resource | `service.name`, `service.version`, `service.namespace`, `deployment.environment.name` | Runtime secrets, database URL, internal topology unrelated to operations |
| Request span | HTTP route template, method, status class, sanitized correlation ID | Raw query string, `Authorization`, session cookie, bearer token |
| Governance span | Stable operation name, policy bundle version, decision outcome, bounded latency bucket | Prompt text, model output, tool arguments, evidence content, personal data |
| Metrics dimension | Service, operation class, status class, policy outcome, provider class | Project/tenant/user IDs, request ID, trace ID, prompt hash, model output hash |
| Error event | Exception class and sanitized error code | Stack traces containing inputs, credential values, raw provider response |

Project, tenant, session, approval, and evidence identifiers are intentionally excluded from metrics labels to prevent unbounded cardinality. They must also be absent from third-party telemetry by default. When an operator needs a trace-to-audit pivot, the gateway stores the linkage in the platform audit record and grants access only through existing tenant/project authorization; it does not solve the problem by exporting sensitive attributes.

#### 3.3 Required Instrumentation

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

#### 3.4 Collector Security and Resilience Controls

The Collector gateway must run privately, authenticate application exporters, and hold no application secrets beyond its backend credentials. It must apply a deterministic transform processor before export to delete disallowed attributes, a memory limiter before batching, bounded retry queues, a circuit breaker or exporter timeout, and separate pipelines by signal. If a backend becomes unavailable, the Collector may shed normal telemetry according to an explicitly documented policy; it must never block a user request solely because an external trace backend is unavailable.

Security-relevant or decision-relevant evidence is different: the authoritative audit record must commit transactionally before a runtime action can be released. Telemetry is diagnostic and cannot be the only system of record. This distinction preserves reliable governance when telemetry infrastructure is degraded.

| Failure | Application behavior | Operator action |
|---|---|---|
| Trace/log backend unavailable | Continue service; bounded Collector queue and sampling prevent cascade | Alert platform operations; investigate exporter and capacity |
| Collector unavailable | Continue normal application service with bounded local exporter failure; do not retry per request indefinitely | Page observability owner when loss threshold is crossed |
| PostgreSQL/audit unavailable | Readiness becomes unhealthy; governed mutations and runtime AI actions fail closed | Incident response; do not bypass audit evidence |
| Policy engine error | Decision is `ERROR`; action is blocked unless an existing, documented human exception workflow applies | Review policy bundle and evidence |
| Object storage integrity failure | Evidence operation fails; release-impacting paths fail closed | Preserve metadata, start evidence incident |

### 4. P3.1 SLIs, SLOs, and Error Budgets

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

#### 4.1 P3.1 Implementation Sequence

| Step | Deliverable | Acceptance criterion |
|---:|---|---|
| 1 | `TelemetryProperties`, data-classification policy, resource contract, and OTLP configuration | Configuration defaults to disabled/no exporter; validation rejects insecure production endpoint configuration |
| 2 | Java agent packaging and deterministic manual instrumentation adapter | Existing full test suite passes with telemetry disabled and enabled |
| 3 | Collector reference deployment, mTLS, redaction, batching, retry, and routing configuration | Contract test proves banned attributes are removed before export |
| 4 | SLI metric instruments and versioned SLO definitions | Every SLI has numerator, denominator, exclusions, owner, and runbook link |
| 5 | Dashboards, multi-window burn alerts, synthetic checks, and incident integration | Synthetic failure reaches correct alert route without leaking sensitive input |
| 6 | Telemetry, cardinality, privacy, and Collector-resilience tests | Load test demonstrates bounded memory/queue behavior and no metric identity explosion |
| 7 | Operations documentation and controlled rollout | One project in observe-only mode completes 28-day baseline before broad enforcement |

#### 4.2 Implementation Reference Artefacts

The initial implementation artefacts are versioned with the repository so that deployment, policy, and test work starts from an auditable baseline rather than unreviewed console configuration.

| Artefact | Purpose | Validation status |
|---|---|---|
| [`infra/observability/otelcol-gateway.yaml`](../infra/observability/otelcol-gateway.yaml) | Private mTLS Collector gateway with allowlisting, redaction, memory limiting, batching, bounded retry, and backend routing. | YAML structure reviewed; the P3.1 selected digest-pinned Collector distribution must run its `validate` command before deployment. |
| [`infra/observability/p3-slo-burn-rate-rules.yaml`](../infra/observability/p3-slo-burn-rate-rules.yaml) | Versioned SLI recording rules, fast/slow multi-window burn rules, and zero-tolerance audit/evidence integrity alert. | Prometheus rule syntax and alert routing must be checked by the P3.1 monitoring stack before enabling paging. |
| [`management-server/src/test/resources/runtime-ai-governance-policies/`](../management-server/src/test/resources/runtime-ai-governance-policies/) | CEL bundle samples for workload/model allowlisting, input/tool containment, post-flight approval, and emergency override. | Evaluated through the real `PolicyExpressionEngine` in `RuntimeAiGovernancePolicySamplesTest`. |
| [`docs/observability-and-resilience.md`](observability-and-resilience.md) | Sprint 1 implementation: versioned `aisdlc.telemetry` configuration model, resource/span/metric allowlists, and W3C trace-context propagation in the management server. | Covered by `TelemetryPropertiesTest`, `TelemetryAttributeContractTest`, `W3CTraceContextTest`, and `TraceContextFilterTest`; telemetry stays disabled by default. |

### 5. P3.3 Runtime AI Governance: Architecture and Requirements

#### 5.1 Scope and Non-Goals

The gateway governs runtime interactions that an approved application or agent makes with an approved model provider and registered tools. It supports **declaration, deterministic enforcement, evidence, budget control, and human decision gates**. It does not provide a conversational UI, build a general model hosting platform, train/fine-tune models, or authorize direct merge/release/deployment. The existing deterministic validator remains fully separate and never calls a model.

Prompt injection remains a core threat: external or user-derived content can alter model behavior and can enable unauthorized function access or critical-decision influence. OWASP therefore advises strict privilege control, code-mediated function handling, clearly segregated untrusted content, and human approval for high-risk actions.[4] The runtime architecture treats all model output and tool-observed content as untrusted data.

#### 5.2 Logical Architecture

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

#### 5.3 Trust Boundaries and Identity

Human users authenticate through the existing Keycloak JWT flow. Agent workloads must not impersonate human users or receive `admin`, `developer`, `reviewer`, or `viewer` authority. The initial implementation uses short-lived Keycloak client-credential access tokens with a dedicated runtime audience and a dedicated `agent_runtime` authority. The gateway validates issuer, audience, authorized party, expiry, workload identity, tenant, project, and a bound agent configuration before it evaluates any policy.

Each runtime workload has a human sponsor, tenant, project scope, allowed environment, allowed provider/model versions, tool capability set, policy bundle/version, rate limit, concurrency budget, and spend/token budget. Rotatable provider credentials stay in the gateway secret store and are never supplied to an agent or copied into the control-plane database.

For Kubernetes or cross-environment enterprise deployments, P3.2 may add SPIFFE/SPIRE workload attestation and mTLS. SPIFFE provides cryptographically attested workload identities and cross-service authentication for zero-trust systems.[5] It is an extension path, not a prerequisite for the P3.3 initial deployment because Keycloak is already the established platform identity system.

| Identity type | Permitted use | Never permitted |
|---|---|---|
| Human Keycloak subject | Configure providers, policies, tools, approvals, and review evidence according to role | Directly expose provider credential; approve their own restricted override if segregation policy prohibits it |
| Agent workload identity | Submit a scoped runtime request; receive a gateway decision; call a granted tool through broker | Satisfy approval quorum; call model provider/tool directly; mutate policy or model allowlist |
| Gateway service identity | Fetch provider credentials, append decision evidence, dispatch approved provider/tool calls | Delegate policy ownership to a provider; bypass audit transaction |
| Tool execution identity | Execute a one-use, scoped capability grant | Reuse grant, broaden scope, perform arbitrary shell/database/network action |

#### 5.4 Decision Model and Lifecycle

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

#### 5.5 Model, Prompt, Data, and Tool Controls

The gateway accepts references and digests rather than silently accepting mutable model aliases. A request must specify `provider`, `model_name`, `model_version`, `prompt_template_key`, `prompt_template_version`, and template digest. Model profiles are versioned and include endpoint, credential reference, permitted environment, maximum context/output tokens, timeout, data residency classification, and deprecation status. A retired or incident-affected model profile blocks new requests and identifies affected sessions via existing evidence links.

Raw prompt, context, response, and tool payload retention defaults to **off**. The gateway processes data in memory and records only classification, length/token counts, canonical request/response/tool digests, provider/model/profile version, policy version, outcome, timestamps, and immutable audit identifiers. If a regulated tenant explicitly requires payload retention, it requires a separate approved evidence class, envelope encryption, strict TTL, project authorization, legal-hold compatibility, and a documented data-residency decision. That retention mode is not enabled in P3.3 initial rollout.

Tools are registered artifacts, not ad-hoc URLs or shell strings. Every tool manifest has a stable key, semantic version, digest, owner, risk tier, typed JSON Schema input/output contracts, egress target policy, capability scope, maximum execution time, and approval rule. The model can propose a tool invocation, but it cannot execute it itself. The broker owns tool credentials, deterministic argument validation, allowlisted targets, one-use capability grants, and sanitized receipts.

| Tool tier | Example | Runtime rule |
|---|---|---|
| Read-only, low-risk | Query an approved public repository metadata endpoint | Policy pass plus scoped workload grant; no secret-bearing response forwarded blindly |
| Read-only, sensitive | Retrieve internal evidence metadata | Project/tenant authorization, classification gate, redacted result, enhanced audit |
| Mutating | Create a draft issue or attach a review artifact | Proposal plus deterministic validation; approval required when policy marks impact high |
| Release-impacting | Merge, deploy, rotate credential, modify production data | Gateway never executes directly; existing human quorum approval and downstream controlled workflow remain mandatory |

#### 5.6 Data Model and API Contract

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

#### 5.7 Resilience, Cost, and Safe Failure

Provider calls use separate bulkheads by provider and tenant tier, strict connect/read/overall deadlines, bounded concurrency, retries only for proven idempotent provider operations, and circuit breakers. Tool actions are never retried automatically unless the manifest explicitly declares idempotency and the gateway can prove the same idempotency key. Budget checks use a transactional reservation before provider dispatch and reconciliation against provider-reported usage. The system refuses a request when it cannot calculate or reserve budget safely.

The gateway must distinguish operational observability degradation from governance-evidence degradation. Loss of a trace backend cannot itself authorize or block an action. Loss of the audit/control-plane database, policy engine, identity validator, or required approval state blocks the action. This makes security availability fail closed while keeping diagnostics from creating an unnecessary dependency loop.

#### 5.8 Test and Rollout Strategy

P3.3 requires deterministic tests with a fake provider and fake tools; no test invokes a production model. Test suites cover forged/missing workload token, audience confusion, tenant/project scope escape, retired model version, policy timeout/error, idempotency replay, budget race, tool schema violation, grant replay, prompt-injection corpus, indirect untrusted-content injection, output schema bypass, audit-store loss, and approval-expiry race. The prompt-injection corpus is treated as security test data, not model training data.

Rollout proceeds in four gates: (1) control-plane schema and APIs disabled by feature flag; (2) observe-only pre-flight decisions with no provider access; (3) one internal project using a fake/isolated provider and read-only tools; and (4) project-by-project enforcement after human owners accept policy, budget, data classification, and runbook conditions. No production deployment, merge, or destructive tool is in the initial scope.

### 6. Dependencies, Decisions, and Exit Criteria

| Workstream | Blocking dependency | Decision required before code | Exit criterion |
|---|---|---|---|
| OTel application instrumentation | Pinned compatible Java agent and Collector image | Approved attribute allowlist and telemetry retention policy | End-to-end traces/metrics/logs are correlated without prohibited data |
| SLO and alerting | Telemetry backend and incident destination | Initial target/alert ownership and maintenance exclusion policy | 28-day baseline plus tested burn-rate alerts and runbooks |
| Runtime identity | Keycloak service-account realm/client design | Audience, claims, rotation, sponsor/revocation process | Workload cannot impersonate human or cross tenant/project boundary |
| Provider profiles | Secret manager and outbound egress policy | Approved provider/model/version and data residency | Provider direct access is technically blocked for agents |
| Tool broker | Manifest schema and capability-grant format | Risk-tier/approval matrix | Tool cannot execute outside one-use, bounded, policy-approved grant |
| Runtime evidence | Existing audit/evidence/approval services | Minimal metadata vs regulated retention mode | Every action has an immutable decision/audit link; raw payload stays off by default |

### 7. Delivery Order

P3.1 is implemented first through its data-classification and instrumentation contract, then the Collector and baseline SLO measurement. P3.3 begins only after the policy-engine latency and audit-correctness SLOs are observable, because the gateway relies on those components as fail-closed dependencies. The initial P3.3 release is provider-isolated and read-only; mutations remain outside scope until the approval, tool-broker, and incident exercises pass.

| Release slice | Scope | Success condition |
|---|---|---|
| P3.1-A | OTel contract, Java agent, Collector, safe resource/attribute filters | No sensitive attributes reach test exporter; existing services remain stable |
| P3.1-B | Domain telemetry, SLI metrics, dashboards, synthetic checks | Complete 28-day baseline with documented SLO ownership |
| P3.1-C | Burn alerts, incident links, operational evidence and resilience tests | Controlled failure triggers the correct runbook and escalation |
| P3.3-A | Runtime schema, workload/model/tool registry, observe-only pre-flight | Every candidate request is classified and explained without provider access |
| P3.3-B | Isolated provider adapter, budgets, audit outbox, read-only tools | Fail-closed test matrix and tenant isolation pass |
| P3.3-C | Enforced project opt-in with human approval, post-flight controls | All decision points preserve existing human-approval invariant |

### References

[1] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[2] [NIST AI 600-1, Artificial Intelligence Risk Management Framework: Generative Artificial Intelligence Profile](https://doi.org/10.6028/NIST.AI.600-1)

[3] [OpenTelemetry Java, Instrumentation Ecosystem](https://opentelemetry.io/docs/languages/java/instrumentation/)

[4] [OWASP GenAI Security Project, LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

[5] [SPIFFE, Secure Production Identity Framework for Everyone](https://spiffe.io/)

---

## AI-Agent Governance

### Purpose and Non-Negotiable Controls

AI-agent governance records the provenance of AI-assisted changes without allowing an agent to make a delivery decision. The platform preserves these invariants:

1. The deterministic validator never invokes an AI model.
2. The CLI must use an explicit model pin and must not use `--bare`.
3. A human approval is required at every delivery decision point.

This module is an evidence ledger and governance gate. It is not an agent runtime, a prompt execution service, or a mechanism to grant an AI system production access.

### Recorded Evidence

| Record | Required provenance | What is deliberately excluded |
|---|---|---|
| Prompt template | Stable key, semantic version, SHA-256 fingerprint, classification, source reference | Prompt content is not copied into the governance ledger. |
| Agent session | Agent identity, provider, model and model version, session fingerprint, optional context/tool-invocation digests | Raw context and tool-call payloads are not persisted. |
| Generated change | Change reference, generated-change SHA-256, policy decision/reference, optional validation/evidence IDs | No synthetic policy pass or agent-generated approval is accepted. |
| Human decision | Approval request link, quorum state, individual human approvers | An agent identity can never satisfy the approval quorum. |

All SHA-256 values are lower-case 64-character hexadecimal digests. A session fingerprint is idempotent within a project, and a generated-change digest is idempotent within an agent session. Retries therefore cannot create duplicate provenance or duplicate approval requests.

### Governance Flow

1. An authorized project owner, developer, or reviewer registers a versioned prompt fingerprint when a reusable prompt template is in scope.
2. An authorized member declares an agent session and records its provider/model version and bounded digests.
3. The system permits an eligible generated-change declaration only while the session is `DECLARED`.
4. A `FAIL` policy decision is rejected before any approval request is created. `PASS` and `WARN` decisions create a governed approval request.
5. The existing approval orchestration service collects a human decision, quorum, delegation, SLA reminders, escalation, and immutable audit trail.
6. An authorized member completes or blocks the session. Blocking requires owner or reviewer authority.

> A declared agent session or a recorded provenance item is not approval to merge, release, or deploy. The linked human approval status remains the only decision evidence.

### API Surface

All endpoints are project scoped and require a JWT subject with project membership.

| Endpoint | Method | Use |
|---|---|---|
| `/api/v1/projects/{projectId}/agent-governance/prompt-templates` | `POST`, `GET` | Register or list versioned prompt fingerprints. |
| `/api/v1/projects/{projectId}/agent-governance/sessions` | `POST`, `GET` | Declare or list idempotent agent sessions. |
| `/api/v1/projects/{projectId}/agent-governance/sessions/{sessionId}/complete` | `POST` | Complete a declared session. |
| `/api/v1/projects/{projectId}/agent-governance/sessions/{sessionId}/block` | `POST` | Block a session; requires owner or reviewer role. |
| `/api/v1/projects/{projectId}/agent-governance/sessions/{sessionId}/evidence` | `POST` | Declare a policy-eligible generated change and create linked human approval. |
| `/api/v1/projects/{projectId}/agent-governance/evidence` | `GET` | List generated-change provenance and linked approval state. |

The SSR portal exposes the same controls at **AI-agent governance**. Browser forms are authenticated server side; access tokens are not stored in the browser.

#### How this surface is verified

`scripts/agent-governance-sweep.sh` runs 35 assertions against the live stack and is wired into
`scripts/integration-smoke.sh`, so it runs in CI. It covers what unit tests cannot see: that a re-declared fingerprint
returns the same session rather than a second one, that the same digest sent in uppercase resolves to that same session,
that a malformed fingerprint, an out-of-range tool count, a blank agent identity and a prompt template from another
project are each refused with `400`, that a policy-failed change cannot open a human approval, that a completed session
refuses further evidence, and that the approval it opens starts `PENDING`.

It was written because this surface had no live coverage at all, and its first run found one: the service validated
digests with a lowercase-only pattern while the controller published `^[a-fA-F0-9]{64}$`, so a caller formatting digests
with `%X` was told a valid SHA-256 was invalid. `DigestCaseConsistencyTest` now fails on any lowercase-only digest
pattern in main source.

`scripts/agent-evidence-acceptance.sh` covers the complementary half — the harness evidence-forwarder plugin's own
digest computation, spool file and behaviour on a rejected credential. That plugin is not in this repository, so the
suite runs only when `AISDLC_EVIDENCE_LIB_DIR` points at a built copy; set the repository variable of the same name to
enable it in CI. When it is unset, `integration-smoke.sh` says so rather than passing quietly.

### Operational Guidance

Use a provider-neutral `agentIdentity` that identifies the accountable automation configuration, not a human actor. Always capture an immutable model version where the provider supports it. Keep source prompt content in the approved source/evidence repository and store only its fingerprint in this ledger. For sensitive context, use only a digest and classify the prompt template conservatively.

When a policy fails, remediate the change or correct the policy evidence before declaring it. Do not re-label a failed decision as a warning to bypass human review. If a model or toolchain is retired or has a security incident, block active sessions and use the audit record plus evidence ledger to locate affected generated changes.

---

## Runtime AI Workload Identity and Provider Proxy Rollout

**Status:** Implemented and disabled by default. Enabling the internal surface is an explicit deployment decision.
**Scope:** Keycloak `agent_runtime` identity, resource-server audience and authorized-party enforcement, the internal agent-runtime-only provider-invocation endpoint, and the secret-manager binding for `ProviderCredentialResolver`.

The tool-grant half of the same internal surface is described in [`runtime-ai-governance.md#p33-tool-broker-capability-grants`](runtime-ai-governance.md#p33-tool-broker-capability-grants).

This closes the rollout precondition recorded in [`runtime-ai-governance.md#p33-provider-proxy-execution-design`](runtime-ai-governance.md#p33-provider-proxy-execution-design): the provider adapter existed but had no authenticated caller and no credential source. It does not widen the adapter's scope — tool brokering, post-flight approval mutation, and payload retention remain out of scope.

### Workload Identity

`agent_runtime` is a realm role in [`infra/keycloak/ai-sdlc-realm.json`](../infra/keycloak/ai-sdlc-realm.json), held by the `aisdlc-agent-runtime` confidential service-account client. Two rules keep a workload from becoming a human principal:

1. A token carrying `agent_runtime` receives exactly one authority, `ROLE_agent_runtime`. It never receives `ROLE_admin`, `ROLE_developer`, `ROLE_reviewer`, or `ROLE_viewer`.
2. A token carrying `agent_runtime` **and** any human realm role is treated as an impersonation attempt. The validator rejects it, and the authority converter independently grants nothing, so neither identity is granted even if one layer is bypassed.

Human callers are correspondingly kept off the runtime surface: `/api/**` now requires one of the four human authorities rather than merely an authenticated principal, and `/internal/runtime-ai/**` requires `ROLE_agent_runtime`.

### Audience and Authorized Party

`RuntimeTokenValidator` runs inside the resource-server `JwtDecoder`, after the standard issuer, signature, and expiry validation.

| Property | Environment variable | Default | Effect |
|---|---|---|---|
| `aisdlc.security.audience.runtime` | `AISDLC_RUNTIME_AUDIENCE` | empty | Audience a runtime token must carry. **While empty, every `agent_runtime` token is rejected**, so the internal surface cannot be reached by accident. |
| `aisdlc.security.audience.control-plane` | `AISDLC_CONTROL_PLANE_AUDIENCE` | empty | When set, every human token must carry this audience. Empty preserves the behaviour of a realm whose clients do not yet emit an audience mapper. |
| `aisdlc.security.audience.runtime-authorized-party` | `AISDLC_RUNTIME_AUTHORIZED_PARTY` | empty | When set, a runtime token's `azp` must match it, which pins runtime access to one Keycloak client. |

A human token that carries the runtime audience is also rejected once the runtime audience is configured, which prevents audience confusion in the other direction. The realm file ships the matching mappers: `aisdlc-management` for the portal and CLI clients, `aisdlc-runtime` for the agent-runtime client.

Recommended production values are `AISDLC_RUNTIME_AUDIENCE=aisdlc-runtime`, `AISDLC_CONTROL_PLANE_AUDIENCE=aisdlc-management` once the realm mappers are deployed, and `AISDLC_RUNTIME_AUTHORIZED_PARTY=aisdlc-agent-runtime`.

### Internal Provider-Invocation Endpoint

```
POST /internal/runtime-ai/projects/{projectId}/provider-invocations
Idempotency-Key: <uuid>
```

The endpoint bean exists only when `AISDLC_RUNTIME_AI_PROVIDER_PROXY_ENABLED=true`. It is deliberately outside `/api/**` and outside the browser CORS policy, so no browser origin can reach it.

The workload subject comes from the validated token subject, never from the request body, so a caller cannot dispatch as another workload; `RuntimeAiBrokerService` still requires that subject to be a registered, active workload for the project. The request body carries the agent session, provider, pinned model, request fingerprint, policy context, and an opaque provider payload. The idempotency key is a header, matching the platform convention, and is forwarded upstream by the adapter.

| Outcome | HTTP status | Body |
|---|---|---|
| `COMPLETE` | 200 | Reason code, digests, attempt count, decision ID, and the provider response returned to the authorized caller |
| `BLOCKED` | 403 | Reason code and digests; no provider response, and no outbound call was made |
| `BLOCKED` with `DUPLICATE_REQUEST` | 409 | A replayed idempotency key is a conflict, not an authorization failure, so a safe retry is not reported as a governance block |
| `FAILED` | 502 | Reason code, attempt count, and digests; the provider response is withheld |

Only digests reach the audit record. The provider response is never persisted.

### Provider Credential Binding

`ProviderCredentialResolver` is bound by `RuntimeAiCredentialConfiguration`:

- With no `AISDLC_RUNTIME_AI_CREDENTIAL_MOUNT_PATH`, the fail-closed resolver stays in place and every dispatch is blocked with `PROVIDER_CREDENTIAL_UNAVAILABLE`.
- With a mount path configured, `MountedSecretProviderCredentialResolver` reads material from that read-only directory. The application fails to start if the path is not an existing directory.

A read-only mount is the provider-neutral delivery mechanism that every approved secret manager already supports — a Vault Agent, a secrets-store CSI driver, or a Kubernetes Secret all project material into a directory. Substituting a direct secret-manager SDK is one additional implementation of the interface and requires no other change.

| Rule | Behaviour |
|---|---|
| Reference format | Only `mount:<name>` with `[a-z0-9][a-z0-9._-]{0,62}`; anything else, including a path separator or `..`, is refused before touching the filesystem. |
| Containment | The resolved path is normalized and must remain inside the mount. |
| File shape | Regular file only; a symbolic link is refused, and size must be non-zero and bounded (8 KiB for a credential, 256 KiB for a keystore). |
| Permissions | On a POSIX filesystem, material readable or writable by group or others is refused. |
| Credential value | Trimmed; a blank value, or one containing a control character, is refused so it cannot be used for header injection. |
| mTLS | `<name>.p12` (PKCS#12) plus `<name>.p12.pass`, both under the same rules. A profile requiring mTLS fails closed when the identity is missing or unusable. |
| Disclosure | No secret value appears in a log line, exception message, API response, or audit record. The password buffer is zeroed after use. |

### Rollout Order

1. Import the updated realm, then create one service-account workload holding only `agent_runtime`.
2. Set `AISDLC_RUNTIME_AUDIENCE` and `AISDLC_RUNTIME_AUTHORIZED_PARTY`, and verify a human token is still accepted and a runtime token is rejected by `/api/**`.
3. Register the workload subject and the provider profile for one internal project through the existing owner-only broker endpoints.
4. Mount the provider credential and confirm the application starts and the fail-closed resolver is no longer in use.
5. Set `AISDLC_RUNTIME_AI_PROVIDER_PROXY_ENABLED=true` for that deployment only, against a fake or isolated read-only provider.
6. Enable `AISDLC_CONTROL_PLANE_AUDIENCE` once every human client emits the audience mapper.

### Verification

```sh
mvn -pl management-server test
```

`RuntimeTokenValidatorTest`, `SecurityConfigTest`, `RuntimeAiCredentialConfigurationTest`, `MountedSecretProviderCredentialResolverTest`, and `RuntimeAiProviderProxyControllerTest` cover the unconfigured-audience rejection, audience confusion in both directions, authorized-party pinning, the mixed-identity token, the mount reference and permission rules, symlink and traversal refusal, mTLS failure closure, secret non-disclosure, and the endpoint's subject binding and response shaping.

### References

[1] [OWASP GenAI Security Project, LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

[2] [NIST AI 600-1, Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)

[3] [Keycloak, Audience Support](https://www.keycloak.org/docs/latest/server_admin/index.html)

---

## P3.3 Tool Broker: Capability Grants

**Status:** Implemented and disabled by default. The internal surface exists only when a deployment enables it.
**Scope:** Tenant-scoped single-use tool capability grants, canonical argument fingerprinting, explicit approval linkage for high-impact tools, and digest-only persistence.

This implements the tool-broker half of the P3.3 decision model in [`runtime-ai-governance.md`](runtime-ai-governance.md) §5.4–§5.6. The broker authorizes and accounts for a tool action; it does **not** execute one. Outbound tool dispatch, tool credentials, and release-impacting actions remain outside the delivered scope, and the platform invariant that a human approval is mandatory at every delivery decision point is unchanged.

### Why the Grant Is Bound to Arguments

A tool name is not a sufficient unit of authorization. A model can propose a benign call, obtain permission, and then execute a different one — the propose/execute gap that OWASP describes for prompt injection.[1] The broker therefore authorizes an exact argument set: it canonicalizes the arguments, fingerprints them with SHA-256, and binds the grant to that fingerprint. Redemption recomputes the fingerprint from the arguments actually presented and refuses any difference.

Canonicalization orders object members by key and preserves array order, because member order is not part of a JSON value but element order is. The same arguments therefore always produce the same fingerprint, and a reordered payload is not treated as a new authorization.

### Grant Lifecycle

| Stage | Control | Failure outcome |
|---|---|---|
| Issue | Tool must be registered and active for the project; `RuntimeAiBrokerService.authorizeTool` runs the workload check, the approval requirement, and the CEL decision | `TOOL_NOT_ALLOWLISTED`, `HUMAN_APPROVAL_REQUIRED`, or the policy reason code; no grant row is written |
| Issue | A `HIGH_IMPACT` capability requires a linked approved request, enforced independently by the broker, by this service, and by a table check constraint | `HUMAN_APPROVAL_REQUIRED` |
| Issue | Lifetime bounded to 1–300 seconds, default 60 | `IllegalArgumentException` before any authorization work |
| Issue | A 32-byte secret is generated; only its SHA-256 is stored and the secret is returned exactly once | Reading the database yields no usable grant |
| Redeem | One conditional `UPDATE` matches nonce digest, project, workload subject, argument fingerprint, `ISSUED` status, and an unexpired deadline | Two concurrent replays cannot both win; the loser is diagnosed, not redeemed |
| Redeem | A miss is diagnosed after the fact | `GRANT_UNKNOWN`, `GRANT_ALREADY_REDEEMED`, `GRANT_EXPIRED`, `GRANT_SUBJECT_MISMATCH`, `GRANT_ARGUMENT_MISMATCH` |
| Redeem | Receipt digest is `SHA-256(nonce digest \| argument fingerprint)`, so a receipt proves the redeemer held the secret | Recorded on the grant and in the audit event |
| Revoke | Project owner only, and only while the grant is still `ISSUED` | A redeemed grant is history and is never rewritten |

An expired grant found during diagnosis is transitioned to `EXPIRED`, so the record reflects why it can no longer be used.

### What Is Stored

`runtime_ai_tool_grants` (Flyway `V18`) carries `tenant_id` and `project_id`, and holds the capability reference, workload subject, agent session, runtime decision link, approval request link, capability scope, tool manifest digest, argument fingerprint, grant nonce digest, status, reason code, and lifecycle timestamps.

No raw prompt, model output, or tool argument is written — to the table or to the audit event. The grant secret is never written at all. The policy context is derived inside the broker from bounded facts (tool name, impact level, argument fingerprint, approval linkage) rather than accepted from the caller, so a workload cannot feed the policy engine its own evidence.

### API

The workload surface is internal and exists only when `AISDLC_RUNTIME_AI_TOOL_BROKER_ENABLED=true`. It sits under `/internal/runtime-ai/**`, which requires `ROLE_agent_runtime` and is outside the browser CORS policy — see [`runtime-ai-governance.md#runtime-ai-workload-identity-and-provider-proxy-rollout`](runtime-ai-governance.md#runtime-ai-workload-identity-and-provider-proxy-rollout).

| Endpoint | Caller | Purpose |
|---|---|---|
| `POST /internal/runtime-ai/projects/{projectId}/tool-grants` | Agent workload | Authorize an argument set; 201 with the one-time secret, or 403 with a reason code |
| `POST /internal/runtime-ai/projects/{projectId}/tool-grants/redemptions` | Agent workload | Redeem once for the same arguments; 200 with a receipt digest, or 403 |
| `POST /api/v1/projects/{projectId}/runtime-ai-broker/tool-grants/{grantId}/revocations` | Project owner | Revoke an unredeemed grant; 204, or 409 when it is no longer `ISSUED` |

The workload subject always comes from the validated token, never from the request body, so a caller cannot obtain or redeem another workload's grant.

### Verification

```sh
mvn -pl management-server test
```

`RuntimeAiToolBrokerServiceTest`, `RuntimeAiToolBrokerControllerTest`, and the `V18` guard in `AuditMigrationTest` cover fingerprint canonicalization, unregistered tools, policy and approval denial, the high-impact approval rule, the absence of raw argument values in both the insert parameters and the audit payload, single-use redemption, each replay and mismatch reason code, revocation, and the schema constraints.

### References

[1] [OWASP GenAI Security Project, LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

[2] [NIST AI 600-1, Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)

---

## P3.3 Provider Proxy Execution Design

### Purpose and Scope

This document defines the first executable provider-adapter slice of Runtime AI Governance. The adapter is an internal runtime boundary that dispatches an already-authorized request to one registered provider profile. It does not choose a provider, mutate a policy, inspect or persist raw prompts or responses, execute tools, or replace the existing human-approval workflow.

The implementation is deliberately provider-neutral. A provider-specific JSON request shape is treated as opaque in transit and must be validated by the project policy before proxy dispatch. The adapter returns the provider response only to the authenticated runtime caller. It stores and audits metadata, digests, counts, policy-decision references, and failure reason codes—not prompt, context, response, authorization material, or TLS private-key data.

> A provider dispatch is permitted only after workload identity, provider/model allowlist, budget enforcement, and CEL pre-flight authorization have succeeded. Any missing dependency, malformed request, authentication ambiguity, unavailable credential, profile change, TLS failure, timeout, or evidence-write failure blocks dispatch.

### Execution Contract

The proxy receives a project, workload subject, agent session, provider, pinned model, request fingerprint, policy context, an idempotency key, and an in-memory JSON payload. It uses the existing `RuntimeAiBrokerService.preflight` operation before resolving the active provider profile. A non-`ALLOW` pre-flight decision returns no network response and never opens an outbound connection.

| Control | Enforcement rule | Persistent evidence |
| --- | --- | --- |
| Workload identity | The subject must be an active identity registered for the project. | Existing runtime governance decision/audit record. |
| Model and endpoint allowlist | The `(project, provider, model)` profile must be active; the exact HTTPS endpoint is stored in the profile. Arbitrary request URLs are not accepted. | Profile ID and endpoint digest. |
| Model pin | The request model must equal the profile model. Mutable aliases are not accepted by this adapter. | Provider/model fields and request fingerprint. |
| Credential isolation | Database rows store opaque secret references only. A separate runtime resolver obtains a short-lived authorization value and optional `SSLContext`; callers, database queries, logs, exceptions, HTTP response metadata, and API responses never include secret values. | Secret-reference digest only. |
| mTLS | If the profile requires mTLS, the resolver must return a non-null client `SSLContext`. A missing or unusable TLS identity blocks the request. | mTLS-required flag and failure reason only. |
| Retry | At most the configured `max_attempts` (1–3), with bounded exponential backoff. A retry is allowed only for 408, 429, or 5xx responses and only when a validated idempotency key is forwarded upstream. | Attempt count and final response/status digest. |
| Timeout | The configured timeout governs the whole HTTP request. A timeout is a failed dispatch, never an authorization success or implicit retry without idempotency. | `PROVIDER_TIMEOUT` plus elapsed time. |
| Evidence | Request and response digests use SHA-256. The audit event records identifiers, decisions, profile, status, attempts, and digests. | Immutable `audit_events` record; no payload retention. |

### Profile and Secret Boundary

Migration V17 will extend `runtime_ai_provider_profiles` with `endpoint_uri`, `mtls_reference`, and `require_mtls`. The endpoint is validated at configuration time and again at dispatch time: it must be HTTPS, contain no user-info or fragment, and carry no query-string controlled by a caller. The runtime request cannot supply an endpoint, custom headers, a credential reference, a trust store, or arbitrary TLS options.

`ProviderCredentialResolver` is a narrow internal interface. It accepts a stored opaque reference and returns a transient credential material object containing an authorization header value and, when requested, a client `SSLContext`. The production deployment must bind this interface to the approved secret manager. The default implementation fails closed; it does not read plaintext credentials from the database, process arguments, request body, or an unaudited fallback file. Test doubles use in-memory tokens and certificates only.

The initial adapter uses JDK `HttpClient` behind an injectable `ProviderHttpTransport`. This separates governance tests from networking and lets the test suite use a fake transport rather than a production model or an external provider. `HttpClient` follows no redirects, uses an explicit per-request deadline, and creates a client configured with the optional mTLS context for that single dispatch.

### Idempotency and Retry Semantics

The caller supplies a bounded UUID idempotency key. The adapter forwards the same value as `Idempotency-Key` on each attempt. It does not automatically retry any request without that key, a client error other than 408, or a provider response that cannot prove safe retry behaviour. A transport exception or timeout is retried only when the key is present and attempts remain; otherwise it is returned as a deterministic failed result.

The initial slice records evidence for every completed dispatch attempt, but does not cache or replay raw provider responses. End-to-end response replay and durable invocation idempotency require a separate encrypted response-retention decision and are out of scope. A client that cannot tolerate an uncertain upstream result after a timeout must use its own approved workflow rather than assuming the provider did not receive the request.

### Failure Matrix

| Failure | Outbound network call | Result reason code | Notes |
| --- | --- | --- | --- |
| Unknown workload, inactive profile, deny pre-flight, or unavailable budget/policy | No | Existing broker reason | Governance remains fail closed. |
| Endpoint invalid or profile changes after pre-flight | No | `PROVIDER_PROFILE_UNAVAILABLE` | The profile is re-read immediately before dispatch. |
| Credential/mTLS resolver unavailable | No | `PROVIDER_CREDENTIAL_UNAVAILABLE` or `PROVIDER_MTLS_UNAVAILABLE` | No fallback to database or caller-supplied secret. |
| Timeout or transport failure | Yes, only after pre-flight | `PROVIDER_TIMEOUT` or `PROVIDER_TRANSPORT_FAILURE` | Bounded retry only with an idempotency key. |
| Retryable HTTP response exhausted | Yes | `PROVIDER_RETRY_EXHAUSTED` | Response body is not persisted. |
| Non-retryable HTTP response | Yes | `PROVIDER_HTTP_<status>` | The response is returned only to the authorized runtime caller. |
| Audit/evidence write failure | No future release or retry | `PROVIDER_EVIDENCE_FAILURE` | The current response is treated as failed and not released. |

### Rollout and Test Gates

The adapter is feature-gated and ships with no real provider profile, no provider secret, and no default endpoint. Unit tests use a fake transport and fake credential resolver. The test matrix covers pre-flight denial without a transport invocation, exact endpoint use, credential isolation, mTLS-required failure, retryable and non-retryable response handling, timeout handling, attempt cap, idempotency-key forwarding, digest-only evidence, and failure of audit persistence.

An authenticated runtime API is not exposed until the Keycloak service-account audience and `agent_runtime` authority checks are enforced at the resource boundary. The initial operational rollout is restricted to one internal project with a fake or isolated read-only provider. No production deploy, merge, destructive tool, or provider credential is included in the repository or test environment.

Those preconditions are now implemented. The `agent_runtime` realm identity, the runtime-audience and authorized-party validation, the feature-gated internal endpoint, and the secret-manager mount binding for `ProviderCredentialResolver` are described in [`runtime-ai-governance.md#runtime-ai-workload-identity-and-provider-proxy-rollout`](runtime-ai-governance.md#runtime-ai-workload-identity-and-provider-proxy-rollout). Each control ships disabled: the runtime audience has no default, so no runtime token validates, and the endpoint bean is not created unless a deployment enables it.

### References

[1] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[2] [NIST AI 600-1, Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)

[3] [SPIFFE, Secure Production Identity Framework for Everyone](https://spiffe.io/)
