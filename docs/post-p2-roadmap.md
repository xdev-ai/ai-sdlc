# Post-P2 Product Roadmap

## Current Baseline

Build 1 and the P0–P2 roadmap are complete. The repository backlog records **123 completed** implementation and verification items. The remaining eight unchecked items are intentionally scoped as the next roadmap, rather than known defects in the delivered P0–P2 control plane.

The next investment should strengthen production operability, make AI governance enforceable at execution boundaries, improve portability across enterprise toolchains, and convert the existing evidence and supply-chain controls into independently auditable assurance.

## P3 — Enterprise Operationalization

| Workstream | Primary outcome | Dependencies | Exit criteria |
|---|---|---|---|
| Observability and reliability | OpenTelemetry telemetry, SLO/error-budget definitions, alert routing, and immutable operational audit dashboards | Existing structured logs, health endpoints, and risk data | Service, delivery, governance-gate, and evidence-storage SLIs have documented SLOs; alerts are routed and tested; dashboards are tenant-safe |
| Cost optimization and inference forecasting | Tenant-safe usage attribution, reproducible model pricing, explainable p50/p90 cost forecasts, budgets, and human-approved recommendations | Agent/session provenance, tenant authorization, notification/approval orchestration, and P3.3 runtime metering | Every reported amount is traceable to a usage entry and price version; sparse data is explicit; budgets and anomalies create evidence-linked human decisions only |
| Resilience and recovery | Repeatable PostgreSQL and evidence-object backup, restore, disaster-recovery runbooks, and recovery drills | Object-lock evidence repository and retention model | Encrypted backups, restore verification, RPO/RTO targets, and signed drill evidence are available |
| Enterprise deployment | Helm chart, GitOps reference implementation, hardened defaults, upgrade/rollback guides | Container images, migration safety checks, Keycloak topology | A clean cluster deployment is reproducible; upgrades and rollbacks are validated against a supported version matrix |
| Policy-pack lifecycle | Signed and versioned policy-pack catalog with compatibility and promotion controls | CEL engine, policy bundle evidence, approval orchestration | Packs are tested, signed, promoted across environments, reversible, and associated with evaluation evidence |
| Connector expansion | GitLab, Bitbucket, Azure DevOps, and Jira connectors using one versioned contract | Current GitHub SCM and outbound webhook contracts | Idempotent ingress, signature verification, link correlation, policy feedback, contract tests, and operator documentation are provided per connector |
| AI execution gateway | Agent identity, model allowlist, data/prompt classification, tool restrictions, decision evidence, and human approval gates | Agent-session governance, policy bundles, notifications, tenant authorization | Every governed agent invocation is attributable, policy-evaluated, evidence-linked, and cannot bypass required human decisions |

The P3 AI gateway work is the highest product-risk reduction item because it moves governance from post-hoc recording to enforceable runtime controls. It should align agent controls to the NIST AI RMF functions **Govern, Map, Measure, and Manage**, while keeping the platform’s existing invariant that deterministic validators never call AI.[1]

The implementation-ready design for **P3.1 Reliability** and **P3.3 Runtime AI Governance** is maintained in [`p3-reliability-and-runtime-ai-governance-design.md`](p3-reliability-and-runtime-ai-governance-design.md). The associated [P3.1 resilience/chaos plan](p3-1-resilience-chaos-test-plan.md) defines isolated fault-injection test cases, game-day safety controls, and evidence requirements. [`p3-2-cost-optimization-and-inference-forecasting-design.md`](p3-2-cost-optimization-and-inference-forecasting-design.md) defines the provider-neutral P3.2 ledger, price catalog, forecast, budget, and approval architecture.

The policy-pack catalog should be implemented before broad connector expansion. A shared, signed policy distribution model ensures that every CI/SCM connector consumes the same reviewed controls rather than embedding vendor-specific policy logic. The supply-chain work should add consumer-side provenance verification and progressively measure its coverage against SLSA requirements; SLSA frames provenance and progressively stronger integrity controls as a practical path for hardening software supply chains.[2]

## P4 — Assurance, Scale, and Ecosystem

| Workstream | Primary outcome | Exit criteria |
|---|---|---|
| Enterprise assurance | Tenant isolation testing, performance/capacity test suites, and reusable compliance evidence packs | Isolation, load, failure, and recovery scenarios run continuously; evidence is exportable for audit |
| Extension ecosystem | Stable integration marketplace and connector certification | Version compatibility rules, test fixtures, security review, signing, deprecation policy, and publication workflow are in place |

P4 starts only after P3 produces operational baselines. Capacity targets should be tied to user-facing and governance-critical service-level indicators instead of generic infrastructure thresholds. The SRE model distinguishes an SLI as a measurable service behavior and an SLO as its target, which is appropriate for availability, latency, ingestion reliability, policy evaluation, and evidence durability.[3]

## Recommended Delivery Sequence

| Milestone | Scope | Rationale |
|---|---|---|
| P3.1 | Observability and recovery | Establish measurable reliability and a safe operational fallback before increasing platform adoption |
| P3.2 | Cost optimization and inference forecasting | Make AI operating cost attributable, explainable, and human-governed before scaling agent usage |
| P3.3 | AI execution gateway | Enforce existing agent-governance rules where AI actions occur |
| P3.4 | Helm/GitOps deployment package and policy-pack catalog | Make the platform repeatably deployable and its governance controls consistently distributable |
| P3.5 | SCM/work-management connector expansion | Reuse the stable deployment, policy, and contract foundations across more customer toolchains |
| P4.1 | Assurance and scale verification | Produce evidence that the multi-tenant platform behaves correctly under isolation, load, and recovery scenarios |
| P4.2 | Marketplace and certification | Open extension delivery only after module contracts and assurance criteria are stable |

## Guardrails

All post-P2 work must preserve these platform invariants:

1. The deterministic validator never calls AI.
2. `--bare` remains prohibited and `--model` remains mandatory where the CLI declares a model.
3. Human approval remains mandatory at decision points.
4. Every external connector stays versioned, authenticated, idempotent, tenant-aware, and evidence-producing.
5. All new user-visible functionality has English repository documentation and English/Vietnamese portal support.

## References

[1]: https://www.nist.gov/itl/ai-risk-management-framework "NIST AI Risk Management Framework"
[2]: https://slsa.dev/ "SLSA: Supply-chain Levels for Software Artifacts"
[3]: https://sre.google/sre-book/service-level-objectives/ "Google SRE Book: Service Level Objectives"
