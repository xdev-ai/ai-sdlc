# Policy-as-Code

## Design Basis

AI-SDLC policy bundles use Common Expression Language (CEL) expressions for bounded, deterministic policy decisions. CEL is selected because it is non-Turing complete and supports compile-once/evaluate-many operation; the platform does not embed a general-purpose scripting runtime.

Policy authors supply an expression and a fixture collection. The service owns the entire CEL environment: it exposes only a single JSON-like `context` map and no custom host functions. Compilation happens on create/update or explicit dry-run, not on a latency-sensitive enforcement path. Expressions must evaluate to a Boolean value; an error, an unknown value, a non-Boolean result, or a resource-limit violation is a deterministic failed evaluation rather than a pass.

| Control | AI-SDLC behavior |
|---|---|
| Language | CEL only; no JavaScript, shell, Python, Rego runtime, or dynamically loaded functions. |
| Authoring boundary | Maximum source and fixture size, explicit semantic version, immutable version records, and project scope. |
| Runtime boundary | Declared `context` variable only, bounded JSON depth/node count, no host functions, and a per-evaluation timeout. |
| Lifecycle | `DRAFT` → `ACTIVE` → `RETIRED`; only owners can activate or retire a bundle. |
| Dry run | Evaluation is recorded without being treated as an enforcement decision. |
| Verification | Fixtures state expected Boolean outcomes and must pass before activation. |
| Evidence | Every evaluation produces an audit event and retained result record, including context digest rather than raw sensitive context. |

## References

[1] [CEL for Java: installation, type-checking and evaluation](https://github.com/cel-expr/cel-java)

[2] [CEL overview: environment declaration and compile-once/evaluate-many model](https://cel.dev/overview/cel-overview)
