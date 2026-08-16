# P3 Implementation Prototype: Chaos, Cost Ledger, and Runtime AI Decisions

**Status:** Foundation implementation; not approved for production enforcement.

## Safety Boundary

`ChaosFaultRegistry` is only registered when Spring runs with the explicit `chaos` profile. It has no bean in default, production, or shared integration profiles. The registry is a deterministic fault seam for test adapters; it does not execute network, database, storage, or process-level destructive actions.

| Component scope | Injected outcomes | Required production behavior |
|---|---|---|
| `POLICY_ENGINE` | timeout, unavailable | Deny the governed action and retain failure evidence. |
| `NOTIFICATION_PROVIDER` | timeout, unavailable | Preserve decision; enqueue a bounded retry without changing approval outcome. |
| `EVIDENCE_STORAGE` | timeout, unavailable | Do not allow an action that requires evidence finalization. |
| `AUTHENTICATION` | timeout, unavailable | Reject new workload authorization; never reuse another identity. |
| `SCM_INGRESS` | timeout, unavailable | Retry only idempotent inbound processing. |
| `RUNTIME_AI_PROVIDER` | timeout, unavailable | Fail closed for governed delivery actions. |

## P3.2 Provider-Neutral Cost Foundation

Flyway V14 introduces `inference_usage_events`, `inference_cost_allocations`, and `inference_cost_forecasts`. All monetary values use integer minor units and ISO currency codes; the ledger does not use floating-point money. Usage ingestion is project-scoped, keyed idempotently by an immutable provider source-event key, and stores a SHA-256 source claim. The initial allocation is `SOURCE_COST_EXACT` to the owning project, preventing unreviewed cross-project allocation logic.

The initial forecast is intentionally explainable: a trailing daily-cost mean is applied to a bounded 1–90-day horizon. Fewer than seven observed active days creates an `INSUFFICIENT_DATA` forecast with no numeric recommendation. Forecasts never change a provider, model, budget, or routing decision.

## P3.3 Runtime AI Governance Foundation

`RuntimeAiGovernanceService` evaluates an active CEL policy through the existing side-effect-free `PolicyEvaluationService` and persists a `runtime_ai_decision`. Only an explicit Boolean `PASS` becomes `ALLOW`; compile, context, evaluation, non-Boolean, or policy-fail outcomes become `DENY`. Decision rows are idempotent by project, stage, and request fingerprint. They contain no raw prompt or response content, only a canonical context digest and policy-evaluation linkage.

The exposed project-scoped APIs are intentionally limited to prototype operations:

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/projects/{projectId}/inference-costs/usage` | Ingest a validated, idempotent inference usage claim. |
| `POST /api/v1/projects/{projectId}/inference-costs/forecasts` | Produce an advisory baseline cost forecast. |
| `POST /api/v1/projects/{projectId}/runtime-ai-governance/decisions` | Evaluate CEL and return an auditable fail-closed decision. |

Workload client credentials, provider proxying, post-flight approval mutation, external tool brokering, pricing-catalog governance, budget enforcement, and production chaos game days remain explicitly out of scope for this foundation and must retain their P3 backlog status.

## Next Foundation Increment: Budget and Authorization Broker

The follow-on V15 foundation adds immutable project budget policies, decisions, human-approved expiry-bound exceptions, registered workload identities, provider/model allowlist profiles, and tool capability profiles. `BudgetEnforcementService` evaluates the current calendar-month allocation before provider pre-flight authorization. `RuntimeAiBrokerService` is deliberately authorization-only: it cannot send a provider request, execute a tool, or access provider credentials. It only returns a fail-closed authorization decision with policy and budget evidence linkage. See [`p3-budget-enforcement-and-runtime-broker.md`](p3-budget-enforcement-and-runtime-broker.md) for the configuration and rollout contract.
