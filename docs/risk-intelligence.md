# Quality and Risk Intelligence

## Scope

The Risk Cockpit turns persisted AI-SDLC governance evidence into an explainable, bounded prioritization signal. It is not a machine-learning model, a prediction of delivery failure, a compliance verdict, or an automated merge/release gate. A human remains responsible for interpreting the score and approving a delivery.

Every calculated result is a retained `risk_scores` snapshot with a formula version, component map, source-count map, actor, timestamp, and immutable audit event. This makes a score reproducible from the same evidence population.

## `risk.v1` Formula

The score is an integer between 0 and 100. Its components are capped independently, then summed.

| Component | Maximum | Persisted inputs |
|---|---:|---|
| Finding risk | 25 | Critical and high validation findings completed in the past 90 days. |
| Policy risk | 20 | `FAIL` or `ERROR` enforcement policy evaluations in the past 30 days. |
| Exception risk | 15 | Expired and next-14-day expiring security exceptions. |
| Evidence risk | 10 | Completed 30-day validation runs that have no `validation_evidences` record. |
| Workflow risk | 10 | Pending review items and overdue approval requests. |
| Quality risk | 10 | Latest retained quality snapshot: failure, rework, alignment, queue health, lead/review time, and security debt. |
| Provenance risk | 10 | Release provenance records still in `DECLARED` verification state. |

The band is derived only from the calculated score:

| Score | Band |
|---:|---|
| 0–24 | `LOW` |
| 25–49 | `MODERATE` |
| 50–74 | `HIGH` |
| 75–100 | `CRITICAL` |

The source summary also records agent-session volume, but it does not increase `risk.v1` by itself. This distinction prevents a team from being penalized simply for recording governed AI assistance.

## API and Access Model

| Endpoint | Authority | Result |
|---|---|---|
| `POST /api/v1/projects/{projectId}/risk-intelligence/recompute` | Project owner or reviewer | Recomputes and retains an audited snapshot. |
| `GET /api/v1/projects/{projectId}/risk-intelligence/latest` | Any project member | Returns the latest snapshot. |
| `GET /api/v1/projects/{projectId}/risk-intelligence/trend` | Any project member | Returns descending, paginated snapshot history. |

The SSR portal exposes the same workflow at **Risk cockpit**. The interactive React Island is an enhancement only: the server-rendered snapshot history, formula version, and evidence summary are available without JavaScript.

## Interpretation and Response

A high score should trigger evidence review, not automatic action. Review the associated source counts in the snapshot, then triage each underlying record through its native workflow: validation findings, policy bundle, security exception, approval request, evidence repository, or provenance verification.

When a formula changes, add a new named formula version rather than changing `risk.v1` in place. Preserve previous scores exactly, document the formula migration, and compare trend lines only within the same formula version unless an analyst explicitly normalizes them.

## Data Quality Safeguards

The service reads only project-scoped database rows and fails closed when it cannot persist the resulting snapshot. It never fabricates a missing metric, calls an AI model, writes decisions into review workflows, or alters the lifecycle of its inputs. A project with no quality metrics receives zero quality-risk points rather than an invented estimate.
