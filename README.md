# AI-SDLC — Governed AI-Assisted Delivery

[![Organization](https://img.shields.io/badge/Org-xDev%20AI-123450)](https://github.com/xdev-ai)

AI-SDLC is a **governance, traceability, supply-chain evidence, and quality-intelligence platform** for software-development teams that use AI. It separates deterministic validation on developer machines from a centralized control plane, where policy, approval, evidence, and release provenance are authorized and verified.

## Build 1 components

| Component | Responsibility | Platform |
|---|---|---|
| `management-server` | REST control plane, policy, validation evidence, review, audit chain, and metrics | Java 25.0.3, Spring Boot 4.1.0 |
| `portal` | Secure **SSR** operational portal, not an SPA | Java 25.0.3, Spring Boot 4.1.0, Thymeleaf |
| `cli` | Deterministic offline validator and evidence client | Go 1.22+ |
| `sdk` | Versioned Java/OpenAPI and TypeScript integration clients | Java 25.0.3, TypeScript |
| `infra/terraform-provider` | Terraform provider for governed notification channels and risk snapshots | Go 1.24, Terraform Plugin Framework |
| `ide/vscode` | VS Code manifest for documented deterministic CLI and portal workflows | VS Code Extension API |
| `keycloak` | OAuth2/OIDC identity provider and realm-role authority | Keycloak 26.7.1 |
| `postgres` | Durable transactional storage for the control plane and Keycloak | PostgreSQL 18.6 |

> **Security invariants.** The CLI does not call AI to make governance decisions, requires a pinned model, rejects `--bare`, and only synchronizes evidence. Phase gates and review decisions still require human approval.

## P0–P2 governance capabilities

| Delivery scope | Implemented capability | Primary evidence/control |
|---|---|---|
| P0 SCM/CI | GitHub App webhook ledger, HMAC verification, replay-safe idempotency, repository linking, PR/commit/workflow/release correlation, and policy Check Runs | [`docs/github-scm-integration.md`](docs/github-scm-integration.md) |
| P0 approvals | Encrypted notification channels, signed generic webhooks, immutable delivery receipts, quorum/delegation/SLA escalation and reminders | [`docs/approval-notification-orchestration.md`](docs/approval-notification-orchestration.md) |
| P0 supply chain | CycloneDX SBOMs, evidence-linked provenance, release attestations, optional Cosign signing, and human provenance verification | [`docs/supply-chain-security.md`](docs/supply-chain-security.md) |
| P1 Policy-as-Code | Versioned CEL bundles, typed and Boolean-only evaluation, dry runs, fixtures, lifecycle and retained evaluation evidence | [`docs/policy-as-code.md`](docs/policy-as-code.md) |
| P1 agent governance | Prompt fingerprints, agent sessions, tool/context digests, generated-change provenance, policy gate and human approval linkage | [`docs/ai-agent-governance.md`](docs/ai-agent-governance.md) |
| P1 risk intelligence | Explainable `risk.v1` score, components, historical snapshots, SSR fallback and interactive Risk Cockpit | [`docs/risk-intelligence.md`](docs/risk-intelligence.md) |
| P2 enterprise | Tenant scope, custom permissions, SCIM provisioning, tenant federation metadata, legal holds and e-discovery manifests | [`docs/enterprise-multi-tenancy.md`](docs/enterprise-multi-tenancy.md) |
| P2 ecosystem | Versioned signed webhook envelopes, Java/TypeScript SDKs, Terraform provider and VS Code integration | [`docs/sdk-reference.md`](docs/sdk-reference.md) |

## Security scanning

Five independent gates run on every pull request. Four of them block a merge; the counts below are what they
currently report, not a claim that the repository is clean.

| Gate | Scope | Blocks a merge |
|---|---|---|
| **OSV-Scanner** | Maven, npm, and Go dependencies — new findings on the pull request, plus a full-repository sweep | yes |
| **Trivy** | Repository dependencies, secrets, Dockerfile and Compose configuration, and **both production images** | yes, on HIGH and CRITICAL |
| **CodeQL** | Java/Kotlin, JavaScript/TypeScript, Go, and GitHub Actions, security-and-quality queries | yes |
| **Dependency review** | Dependency changes introduced by the pull request | yes |
| **Dependabot** | Maven, npm, Go modules, Actions, and Docker base images | raises pull requests |

### What the gates have actually caught

These are findings the gates blocked on this repository, not hypotheticals:

| Finding | Severity | Caught by |
|---|---|---|
| `opentelemetry-javaagent` 2.16.0 — CVE-2026-33701 | **CRITICAL** | Trivy, on both production images |
| `opentelemetry-api` 1.51.0 — GHSA-rcgg-9c38-7xpx | Medium | OSV, on the pull request |
| `httpcore5` 5.4.2 — GHSA-hf6x-8p5f-cgmf | High | OSV, via the AWS SDK BOM |
| `httpclient5` 5.6.1 — GHSA-hjcp-jmpx-g3qm | Medium | OSV, via the AWS SDK BOM |

Each was introduced by a dependency change that passed the full unit suite. The suite is not a security control.

### Current alert state — 2026-08-17

59 open code-scanning alerts: **49 CodeQL**, **10 Trivy**. By security severity: 2 high, 14 medium, 2 low; the
remainder are CodeQL quality findings (`note` and `warning`) that carry no security severity. No open Dependabot
alerts.

Both remaining high-severity alerts are assessed, and neither is a live defect:

| Alert | Rule | Assessment |
|---|---|---|
| #13 | `java/spring-disabled-csrf-protection` | By design. The management API is a stateless bearer-token resource server, where CSRF does not apply. |
| #135 | `java/user-controlled-bypass` | False positive. Every branch that short-circuits `authorizeScim` throws 401, so `ScimController.authorize` is fail-closed; the token is resolved by SHA-256 index lookup, never string-compared. |

Both should be dismissed with a recorded reason rather than left open, so the count means something.

**What the count does not mean.** Alerts are not closed automatically when a fix lands, and two distinct staleness
mechanisms have already been observed here:

- **A fix landing does not close the alert until the scanner re-runs on the default branch.** `java/tainted-arithmetic`
  on `PageResponse` and `ScimController` stayed open until the post-merge CodeQL analysis on `main` reported them
  fixed — roughly two minutes after the merge, not at merge time.
- **An alert whose analysis category stops being uploaded stays open forever.** Trivy originally uploaded under the
  default category `.github/workflows/ci.yml:trivy`; it was later split into `trivy-filesystem`,
  `trivy-management-server`, and `trivy-portal`. `CVE-2026-54291` on `org.postgresql:postgresql` was frozen open under
  the abandoned category, still reporting version 42.7.11, while the repository had moved to **42.7.13** — above the
  42.7.12 fix. No live analysis could ever close it, because nothing uploads to that category any more. It was
  resolved by deleting the orphaned analysis, not by changing any dependency. **When splitting or renaming a scanner
  category, delete the analyses under the old one, or its alerts become permanent.**

The live view is [Security → Code scanning](https://github.com/xdev-ai/ai-sdlc/security/code-scanning). Suppressions
require a reviewed, time-bounded `.trivyignore.yaml` entry with advisory ID, rationale, owner, and expiry; see
[`docs/security-scanning.md`](docs/security-scanning.md).

## Access architecture

The SSR portal is the public browser entry point. The Management API runs on the Compose topology's private service network; Keycloak is the authentication authority behind the portal/API boundary. In production, the portal and Keycloak must sit behind TLS reverse proxies with dedicated hostnames; never expose the Management API port directly.

## Local development

1. Copy `.env.example` to `.env`, then replace all development-only secrets.
2. Run `docker compose up --build` to start the local topology.
3. Open `http://localhost:8080`; Keycloak is behind the identity gateway at `http://auth.localhost:8180` (modern browsers map `*.localhost` hostnames to loopback).
4. Run `mvn test` at the repository root to test server, portal, and Java SDK; run `cd cli && go test ./...` to test the validator.
5. Run `cd sdk/typescript && npm ci --ignore-scripts && npm run build && npm test` to verify the TypeScript SDK.
6. Run `cd infra/terraform-provider && go test ./... && go build ./...` to verify the Terraform provider, then `cd ide/vscode && node --test test/extension.test.mjs` to check the VS Code integration manifest.

For module-by-module startup, integration, P3 foundation APIs, and the complete release-verification command set, see the [Module Usage and Verification Guide](docs/module-usage-and-verification.md).

## Language policy

Repository-facing material—README, documentation, issue and pull-request content, release notes, API descriptions, and developer-facing code comments—uses English. The portal supports English and Vietnamese; Vietnamese text is deliberately confined to the localization resource that is rendered to end users. See [`docs/localization.md`](docs/localization.md).

## Version policy

The stack is pinned to **Java 25.0.3 LTS**, **Spring Boot 4.1.0**, **PostgreSQL 18.6**, and **Keycloak 26.7.1**. Every version upgrade is a governed change: OAuth2, migrations, authorization, and CLI evidence flows must be revalidated before promotion.

## Repository layout

```text
management-server/    Spring Boot REST control plane
portal/               Spring Boot MVC + Thymeleaf SSR portal
cli/                  Deterministic Go validator and evidence client
sdk/                  Generated Java SDK and TypeScript integration client
infra/terraform-provider/ Terraform provider source
ide/vscode/            VS Code integration manifest and tests
infra/keycloak/       Realm import and identity configuration
docker-compose.yml    Local production-like topology
docs/                 Architecture, API, and operating decisions
```

## References

[1] [Spring Boot project page — Spring Boot 4.1.0](https://spring.io/projects/spring-boot)

[2] [Oracle Java downloads — JDK 25 LTS](https://www.oracle.com/java/technologies/downloads/)

[3] [PostgreSQL 18.6 release announcement](https://www.postgresql.org/about/news/postgresql-186-1711-1615-1519-1424-and-19-beta-3-released-3365/)

[4] [Keycloak 26.7.1 release notes](https://www.keycloak.org/docs/latest/release_notes/index.html)
