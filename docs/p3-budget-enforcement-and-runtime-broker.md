# P3 Budget Enforcement and Runtime Broker Foundation

**Status:** Implemented foundation. Provider/tool execution remains out of scope and is deliberately not exposed by this release.

## Budget Enforcement Configuration

`inference_budget_policies` is project-scoped and uses a single active currency policy per project. Amounts are integer minor units, not binary floating point. A policy has a monthly limit, a warning percentage, and one of two decision modes.

| Mode | Below warning | Warning to limit | Limit reached | Human-approved, unexpired exception |
|---|---|---|---|---|
| `ADVISORY` | `ALLOW` | `WARN` | `WARN` | `EXCEPTION_APPROVED` |
| `HOLD` | `ALLOW` | `WARN` | `HOLD` | `EXCEPTION_APPROVED` |

Runtime authorization treats a missing active budget policy as `DENY_NO_POLICY`; it creates an immutable decision record with a canonical evidence digest. A budget exception cannot become effective merely by being requested: its linked `approval_request` must be `APPROVED` and the exception must not be expired. Neither budget evaluation nor exception approval changes a provider, a model route, or a price catalog.

## Usage-Ledger Linkage and Notifications

Each **newly persisted** usage event now invokes budget evaluation after its exact cost allocation and audit record have been written. The linkage uses the immutable `inference_usage_events.id` as `inference_budget_decisions.usage_event_id`, protected by a partial unique index. A provider reconciliation replay therefore finds the original `(project_id, source_event_key)` row before budget evaluation; it cannot append another decision or generate another notification.

| Condition after ingestion | Ledger action | Budget action | Notification action |
|---|---|---|---|
| Replayed source event | Return the existing usage event. | None. | None. |
| New event, no active project policy | Record usage and exact allocation. | None. Runtime authorization remains fail-closed if later invoked without a policy. | None. |
| New event, currency differs from policy | Record usage in its original currency. | None; the platform does not invent a cross-currency conversion. | None. |
| New matching-currency event below warning | Record usage and one linked `ALLOW` decision. | Preserve an immutable evidence record. | None. |
| New matching-currency event at warning or limit | Record usage and one linked `WARN` or `HOLD` decision. | Preserve the exact accumulated spend and policy result. | Queue a deduplicated project notification. |

Notification idempotency is stable per project, calendar month, decision, and reason code. Reconciliation cannot repeat an alert, while a material state transition such as `WARN` to `HOLD` produces a distinct notification. `HOLD` is prospective governance for later runtime pre-flight decisions; it never rewrites, deletes, or retroactively rejects a usage event that has already been recorded. A human-approved, unexpired exception remains the only path to `EXCEPTION_APPROVED`.

| API | Human role required | Purpose |
|---|---|---|
| `PUT /api/v1/projects/{projectId}/inference-costs/budget` | Project owner | Configure the bounded, tenant-scoped monthly policy. |
| `POST /api/v1/projects/{projectId}/inference-costs/budget/evaluate` | Owner, developer, or reviewer | Produce an evidence-linked decision for the current calendar month. |
| `POST /api/v1/projects/{projectId}/inference-costs/budget/exceptions` | Owner or developer | Request an expiry-bounded exception linked to a separate human approval request. |

## Provider Proxy and Tool Broker Configuration

The broker is an **authorization layer**, not an outbound proxy. It accepts an authenticated workload identity, selects only configured provider/model or tool capability profiles, calls the active CEL policy through the platform runtime decision service, and emits an allow/deny response with evidence linkage. It does not store raw prompt/output content, send traffic to providers, hold provider secrets, or execute tools.

| Configured entity | Required controls | Fail-closed behavior |
|---|---|---|
| Workload identity | Project-scoped exact JWT subject, active flag, owner provisioning | Unregistered/inactive subject is rejected. |
| Provider/model profile | Active allowlist entry, active policy bundle, credential reference, 100–120,000 ms timeout, one to three attempts | Missing/inactive provider or model returns `MODEL_OR_PROVIDER_NOT_ALLOWLISTED`. |
| Tool capability | Active allowlist entry, policy bundle, impact level, approval requirement | Missing/inactive tool returns `TOOL_NOT_ALLOWLISTED`; high-impact or approval-required grants reject unless the linked approval is `APPROVED`. |
| Budget | Active project policy and current decision | Missing policy or a hold decision rejects pre-flight provider authorization. |

The relevant endpoints are owner-only configuration APIs for workloads, providers, and tools, plus workload-identity authorization APIs for `provider-authorizations` and `tool-authorizations`. The latter use the JWT subject as the workload identity; no request-body actor field can override it.

## Rollout and Recovery

Deploy the schema and API in `dryRun` policy mode first. Configure one non-production project, one restricted model, and one read-only tool. Verify all deny reasons and evidence IDs before a project owner activates an enforcement policy bundle. If a configuration issue is detected, set the provider/tool profile `active=false`; do not delete evidence or bypass CEL. The operator must retain existing human approval requirements for every high-impact capability.
