# Architecture Decision Records

Decisions that were previously carried as open items with no recorded answer. Each states what was decided, the
evidence behind it, and the condition that reopens it — so the question does not drift back into the backlog.

| ADR | Decision | Revisit when |
|---|---|---|
| [0001](0001-java-runtime-version.md) | Stay on Java 25 LTS; Java 26 revalidates cleanly but is not an LTS | The next Java LTS is generally available |
| [0002](0002-dependency-review-gate.md) | `dependency-review` is advisory; OSV and Trivy block | The repository's Dependency graph SBOM endpoint stops returning 404 |
| [0003](0003-slo-paging-enablement.md) | Paging waits for a 28-day observe-only baseline, with fixed exit criteria | The baseline window completes on a production deployment |
