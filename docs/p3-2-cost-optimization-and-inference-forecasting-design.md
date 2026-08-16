# P3.2 Cost Optimization and Inference Forecasting Design

**Status:** implementation-ready architecture  
**Audience:** platform engineering, FinOps, security, product, and tenant administrators  
**Scope:** provider-neutral inference usage, cost attribution, forecasting, budgets, and human-governed optimization recommendations.

## 1. Purpose and Non-Goals

P3.2 gives a tenant a transparent, reproducible explanation of its inference consumption and forecast without allowing a cost algorithm to change models, relax policy, reroute requests, or bypass approval. It treats cost controls as governance evidence, not an autonomous execution engine.

FinOps guidance recommends tracking AI cost and usage, allocation metadata, and unit metrics such as cost per token before expanding toward outcome-oriented measures.[1] [2] A price catalog and a usage ledger must therefore preserve source, version, effective time, currency, allocation method, and reconciliation status. The platform must represent unknown data as **unallocated** or **unreconciled**, not invent a cost or owner.

> **Invariant:** P3.2 is advisory by default. Any action that could change model selection, provider selection, budget policy, or customer experience requires a named human approval and retained decision evidence.

## 2. Logical Architecture

| Component | Responsibility | Safety constraints |
|---|---|---|
| Usage ingestion adapters | Receive provider usage reports and P3.3 runtime metering events. | Authenticate source, deduplicate by provider event/request reference, and never store prompt/output content. |
| Immutable usage ledger | Append canonical input/output/cache/tool unit counts with correlation references. | Tenant-scoped, append-only, idempotent, hash-linked to runtime decision/evidence IDs. |
| Versioned price catalog | Store reviewed SKU/rate/FX/effective-time records. | Human-approved lifecycle; signed source artifact; no mutable historical price. |
| Cost calculator | Compute direct cost, shared-cost allocation, and unit economics. | Deterministic, versioned formula; records unknown/incomplete inputs; does not call an AI model. |
| Forecasting service | Produce baseline and interval forecasts from ledger history. | Explainable algorithm, back-tested, insufficient-data status, no fabricated projections. |
| Budget and anomaly service | Evaluate approved policy thresholds and create notification/approval requests. | Alerting and recommendation only; no automatic model/routing/blocking action. |
| Portal/API/reporting | Show attribution, forecast provenance, variance, and decision history. | Tenant isolation, role-gated access, redacted identifiers, export legal-hold awareness. |

The proposed module boundary is `management-server` domain/service/controller plus a module-friendly `cost-governance` package. Provider-specific reconciliation remains behind `UsageIngestionAdapter` and `PriceCatalogAdapter` interfaces so individual connectors can be adopted independently.

## 3. Canonical Data Model

| Record | Required fields | Notes |
|---|---|---|
| `InferenceUsageLedgerEntry` | `id`, `tenantId`, `organizationId`, `projectId`, `provider`, `model`, `region`, `modality`, `usageStart`, `usageEnd`, input/output/cache/tool units, `requestReferenceHash`, `runtimeDecisionId`, `status`, `sourceVersion`, `createdAt` | Store hashed correlation references only; no prompt, output, user identifier, or unbounded provider metadata. |
| `ModelPriceCatalogEntry` | `id`, provider/model/region/modality/SKU, input/output/cache/tool rate, currency, unit denominator, effective range, `catalogVersion`, source digest, approval metadata | Effective ranges may not overlap ambiguously for the same SKU. A correction creates a successor row. |
| `CostAllocationRule` | tenant/project/cost-center scope, direct/shared method, proxy metric, effective range, rule version, approver | Supports direct allocation first; shared allocation must name a reviewed proxy metric and rule version. |
| `InferenceCostSnapshot` | ledger ID, price catalog ID, allocation rule ID, direct/shared/total amount, base currency, FX version, calculation version, reconciliation status | Makes every reported amount reproducible. |
| `CostForecastSnapshot` | scope, as-of date, horizon, forecast p50/p90, method version, observation count, back-test metrics, confidence state | `INSUFFICIENT_DATA` is valid and must be displayed. |
| `BudgetPolicy` and `CostAnomaly` | scope, threshold/burn window, notification route, approval requirement, policy version, status | Threshold changes and exceptions require the existing approval orchestration. |

## 4. Calculation and Allocation Rules

For each ledger entry, the calculator resolves a single price catalog entry valid at `usageEnd`. It calculates direct variable cost using measured units and catalog denominators:

```text
direct_cost = Σ(unit_count_i / catalog_unit_denominator_i × approved_rate_i)
total_cost  = direct_cost + shared_cost_allocation
```

Shared cost is optional and transparent. A rule may allocate a shared platform expense proportionally by documented API calls, successful governed actions, or measured token units. If the denominator is absent, zero, stale, or materially incomplete, the amount is reported as `UNALLOCATED`; it must never be silently spread across tenants. This matches FinOps allocation guidance to define allocation, metadata, and shared-cost strategies explicitly.[2]

P3.2 begins with a base reporting currency set by tenant policy. Each non-base source amount keeps its original currency, source, conversion timestamp, and approved FX reference. The first release accepts a manually reviewed FX catalog; a future automated feed must retain the same versioning, source validation, and human approval controls.

## 5. Deterministic Forecasting Model

The first forecast is intentionally simple, explainable, and safe under sparse data. It predicts daily direct inference cost for each tenant/project/provider/model scope using an eight-week trailing window, a day-of-week seasonal baseline, and empirical residual intervals.

```text
baseline[d] = median(actual_cost for the same day-of-week across prior complete weeks)
forecast_p50[d] = baseline[d] + approved_event_adjustment[d]
forecast_p90[d] = baseline[d] + percentile_90(abs(historical_residuals))
```

An event adjustment is optional and must link to an approved demand event such as a planned launch; it cannot be inferred from model output. The engine requires at least 28 complete daily observations and four observations for the target day-of-week. Otherwise it returns `INSUFFICIENT_DATA` with the missing-data explanation, rather than emitting a numerical forecast.

Each forecast executes rolling-origin back-testing over the most recent four complete weeks. It records weighted absolute percentage error where denominators are non-zero, median absolute error, interval coverage, missing-data rate, and the calculation/versioned ledger-price inputs. A forecast with interval coverage below the approved threshold is marked `LOW_CONFIDENCE`; it remains visible but cannot trigger an automated cost-control recommendation.

## 6. Cost Controls, Alerts, and Human Decisions

| Control | Trigger | System action | Human decision requirement |
|---|---|---|---|
| Budget threshold | Actual or p90 forecast crosses approved scope budget. | Create signed notification and evidence record. | Required to change budget, allocation policy, or model/provider strategy. |
| Spend anomaly | Actual cost deviates beyond an approved robust residual threshold with adequate history. | Mark anomaly, link source entries and forecast inputs, notify scope owner. | Required before any control action beyond investigation. |
| Reconciliation gap | Provider bill/usage differs from ledger beyond tolerance. | Mark cost `UNRECONCILED`; exclude from high-confidence forecasting. | Required to approve correction/restate historical snapshots. |
| Unit-economics regression | Cost per approved unit worsens outside policy tolerance. | Produce an advisory recommendation with quality/risk context. | Required before routing, quota, pricing, or product change. |

Model substitution, provider routing, prompt reduction, batching, and cache policy are possible future recommendations only. P3.2 never applies them automatically. A recommendation must demonstrate that P3.3 model allowlists, data classification, policy evaluations, and human approvals remain satisfied.

## 7. Security, Privacy, and Retention

Usage records contain only metering and scoped attribution fields. Stable identifiers are replaced with HMAC-derived correlation references under a versioned tenant key; key rotation preserves the previous lookup capability only for the approved retention period. Cost reports inherit tenant membership/permission checks, legal hold, e-discovery exports, and audit events from P2. Provider credentials remain in existing encrypted notification/integration secret storage, never in price or usage rows.

The ledger is append-only. Corrections create a superseding entry with reason and approver. Reports retain the original and corrected calculations, including price/allocation/forecast version. Destructive purges are blocked by tenant legal holds.

## 8. Delivery Slices and Acceptance Criteria

| Slice | Deliverables | Exit criteria |
|---|---|---|
| P3.2-A: Visibility | Domain schema, ledger API, price catalog lifecycle, direct cost snapshots, tenant/project dashboards. | Idempotent ingestion; every amount links to a catalog version; no prompt/output data persists. |
| P3.2-B: Forecasting | Seasonal baseline, p50/p90 interval, rolling back-test, insufficient-data and low-confidence outcomes. | Reproducible snapshots; fixture tests cover missing/zero/late/corrected data; no fictional forecast. |
| P3.2-C: Governance | Budgets, anomaly evidence, notification/approval integration, advisory recommendations. | Alert cannot alter runtime behavior; every exception/action is approval-linked and audited. |
| P3.2-D: Reconciliation | Provider adapter contract, signed import evidence, correction workflow, shared-cost rules. | Reconciliation gaps are explicit; allocation coverage and tolerance are measurable. |

## References

[1]: https://www.finops.org/wg/finops-for-ai-overview/ "FinOps for AI Overview"
[2]: https://www.finops.org/framework/capabilities/allocation/ "FinOps Framework: Allocation"
[3]: https://www.finops.org/framework/capabilities/unit-economics/ "FinOps Framework: Unit Economics"
