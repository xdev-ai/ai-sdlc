# Module Usage and Verification Guide

This guide is the practical entry point for every independently usable AI-SDLC artifact. It complements the architecture and feature documents; it does not weaken the platform invariants: the deterministic validator does not call AI, `--bare` is rejected, a model pin is mandatory, and governance decisions require human approval.

## Repository Scope

The authoritative workspace is the `main` branch of `xdev-ai/ai-sdlc`. Local directories named `ai-sdlc` and `ai-sdlc-xdev` may be historical clones of the same remote and must not be used for new changes. The separate `ai-sdlc-platform` workspace is a Manus web-project scaffold and is not a build artifact of this repository.

## Prerequisites

Use Java 25, Go 1.24 or later, Node.js 22 or later, Docker Compose, and the standard Terraform CLI. Create a local `.env` from `.env.example` and replace every development secret before starting the production-like topology. Never commit `.env` or provider credentials.

## Module Quick Start

| Module | Build and verification | How to use it |
|---|---|---|
| Management Server | `mvn --batch-mode --no-transfer-progress verify` at repository root | Start the Compose topology, authenticate through the portal, and use the versioned project-scoped REST APIs. See [Control Plane API](control-plane-api.md). |
| SSR Portal | `cd portal/frontend && npm run build` plus root Maven verification | Open `http://localhost:8080` after `docker compose up --build`. Sign in through Keycloak and choose the intended organization/project scope before a governance action. See [Portal Workflows](portal-workflows.md). |
| Go CLI | `cd cli && go test ./... && go build ./cmd/aisdlc` | Run `aisdlc validate` only with a pinned `--model`; use the documented project and evidence arguments. See [CLI Guide](cli.md). |
| Java SDK | Root Maven verification generates and tests the SDK | Add the generated Java client dependency to an integration application and configure its API base URL plus bearer token through that application's secret store. See [SDK Reference](sdk-reference.md). |
| TypeScript SDK | `cd sdk/typescript && npm test && npm run build` | Install the package in a Node.js integration, provide the project-scoped API endpoint and bearer token, and handle RFC 9457 errors explicitly. See [SDK Reference](sdk-reference.md). |
| Terraform Provider | `cd infra/terraform-provider && go test ./... && go build ./...` | Configure provider endpoint/token through Terraform variables or a secure environment, then manage supported notification resources and risk data sources. See [Terraform Provider](terraform-provider.md). |
| VS Code Integration | `cd ide/vscode && npm test` | Install or package the extension from this module; its commands invoke only the documented deterministic CLI and portal workflows. See [IDE Integration](ide-integration.md). |
| Observability Configuration | `sh scripts/validate-observability-config.sh` | Review the Collector routing and Prometheus SLO rules before promotion. The script runs `otelcol validate` and `promtool check rules` in digest-pinned, isolated containers. See [Observability README](../infra/observability/README.md). |

## Local Topology and Portal Workflows

Run `docker compose up --build` from the repository root after providing local development secrets. The public browser entry point is `http://localhost:8080`; the portal and identity provider should remain behind the Compose topology boundary. The Management API is not a directly exposed public endpoint.

After authentication, work from a project scope. The portal exposes governance evidence, policy bundles, approval workflows, SCM event correlation, supply-chain provenance, risk intelligence, enterprise tenancy, and the P3 foundations for cost/budget and runtime AI decision evidence. Treat the P3 provider/tool broker as an authorization foundation only: it does not send a provider request or execute a tool.

## P3 Foundation API Workflow

The P3 routes are project-scoped and must be called by an authorized project actor. The intended order is to register an approved budget or broker profile, ingest an immutable usage claim, generate an advisory forecast, then request a runtime decision or authorization pre-flight. A denied result is terminal for that request; do not retry it by changing policy context or identity.

| Capability | Route family | Safety behavior |
|---|---|---|
| Inference usage and forecast | `/api/v1/projects/{projectId}/inference-costs/*` | Monetary values use minor units; forecasts never route a model or spend money. |
| Budget decisions | `/api/v1/projects/{projectId}/budget-enforcement/*` | `ADVISORY` and `HOLD` decisions are retained as evidence; exceptions require explicit approved state and expiry. |
| Runtime CEL decision | `/api/v1/projects/{projectId}/runtime-ai-governance/*` | Evaluation is Boolean-only and fails closed; raw prompt/output content is not persisted. |
| Provider/tool authorization | `/api/v1/projects/{projectId}/runtime-ai-broker/*` | Allowlist, workload identity, capability, approval, and budget checks must all pass; this foundation does not execute the external action. |

## Release Verification Contract

Run the following commands before proposing a release. Each command exits non-zero when its artifact fails verification.

```bash
mvn --batch-mode --no-transfer-progress verify
(cd cli && go test ./... && go build ./cmd/aisdlc)
(cd infra/terraform-provider && go test ./... && go build ./...)
(cd sdk/typescript && npm test && npm run build)
(cd ide/vscode && npm test)
(cd portal/frontend && npm run build)
bash scripts/verify-production.sh
sh scripts/test-trivy-ignore-expiry.sh
sh scripts/test-trivy-sarif-policy.sh
sh scripts/test-validate-observability-config.sh
```

Use GitHub Actions as the authoritative environment for the Docker Compose integration smoke, OSV, Trivy, CodeQL, and digest-pinned observability checks. Investigate a failed job from its uploaded diagnostics; do not add a suppression unless it has governance metadata and a valid expiry.

## Troubleshooting

If Maven fails during generated SDK compilation, start with `mvn clean verify` and keep the reactor module sequence intact. If a frontend build fails, use the module-local lockfile and do not commit generated output. If the Compose smoke fails, capture service logs and retain database migration evidence before changing mappings. If a budget or broker request is denied, inspect its retained decision evidence rather than attempting to bypass CEL, workload identity, policy, approval, or cost guardrails.

## Related Documentation

The [Module Integration Guide](module-integration-guide.md) describes integration boundaries. The [Production Operations Guide](production-operations.md) covers operational deployment. The [Security Scanning Guide](security-scanning.md) explains the fail-closed OSV/Trivy policy. The [P3 Implementation Prototype](p3-implementation-prototype.md) and [Budget and Runtime Broker Guide](p3-budget-enforcement-and-runtime-broker.md) define the current P3 foundation scope and remaining rollout work.
