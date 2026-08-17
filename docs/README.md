# AI-SDLC documentation

Twelve documents, grouped by what you are trying to do. Each one is self-contained and opens with its own table of contents.

## Start here

| Document | Covers |
|---|---|
| [huong-dan-su-dung.md](huong-dan-su-dung.md) | Hướng dẫn tiếng Việt cho người quản trị: mở portal lần đầu thì làm gì, tám bước cấu hình theo đúng thứ tự, kho tài liệu context và cách nạp file Excel, và những chỗ giao diện không làm được. |

## Understand the system

| Document | Covers |
|---|---|
| [architecture.md](architecture.md) | The four planes and their trust boundaries, the `/api/v1` control plane and its authorization model, portal workflows, the session-recovery experience, the frontend library strategy and React Islands, and the localization contract. |
| [ai-sdlc-architecture-slides.md](ai-sdlc-architecture-slides.md) | The same architecture as a presentation deck. |
| [post-p2-roadmap.md](post-p2-roadmap.md) | What is planned beyond P2, and why. |

## The governed flow

| Document | Covers |
|---|---|
| [governance-platform.md](governance-platform.md) | Validation findings and evidence lifecycle, CEL policy-as-code, approval orchestration and notification delivery contracts, the evidence repository and its object-lock model, risk intelligence, enterprise multi-tenancy with SCIM, and the knowledge base that holds project documentation an AI can retrieve and cite. |
| [runtime-ai-governance.md](runtime-ai-governance.md) | The runtime AI threat model, agent workload identity, model and provider allowlists, single-use tool capability grants, and provider proxy execution. |
| [scm-integration.md](scm-integration.md) | GitHub App integration, the `scm.inbound.v1` connector contract for GitLab/Bitbucket/Azure DevOps/Jira, the `scm.outbound.v1` policy-feedback contract, and outbound webhook events. |
| [cost-governance.md](cost-governance.md) | The inference usage ledger, the seasonal forecast with rolling-origin back-testing, and budget policies that warn or hold without ever routing a model. |

## Run and operate it

| Document | Covers |
|---|---|
| [operations.md](operations.md) | Local topology, production runbook, PostgreSQL and evidence-storage backup and restore, the hardening baseline, Helm/GitOps deployment, and the delivery pipeline. |
| [observability-and-resilience.md](observability-and-resilience.md) | Telemetry configuration and W3C trace context, the OpenTelemetry agent and Collector gateway, SLO runbooks and burn-rate alerting, the fault-injection component matrix, and the chaos test plan. |
| [security.md](security.md) | The five scanning gates and what they have caught, supply-chain provenance and signing, dependency decisions, and the dated scan report. |

## Build against it

| Document | Covers |
|---|---|
| [integrations-and-sdks.md](integrations-and-sdks.md) | The Go CLI, Java and TypeScript SDKs, the Terraform provider, the IDE manifest, and the module contracts that let a single package be adopted independently. |
| [verification.md](verification.md) | The end-to-end acceptance suite, the P3 prototype record, and the dated release-verification report. |

## Decisions

Architecture decision records live in [decisions/](decisions/). They record closed questions — what was decided, on what evidence, and what would reopen it.

| ADR | Decision |
|---|---|
| [0001](decisions/0001-java-runtime-version.md) | Stay on Java 25 LTS until the next LTS. Java 26 was revalidated and works; it is not an LTS, which is the reason. |
| [0002](decisions/0002-dependency-review-gate.md) | Dependency review was advisory while Dependency graph was off; superseded the same day it was enabled. Records the wrong probe that produced three false negatives. |
| [0003](decisions/0003-slo-paging-enablement.md) | Paging stays disabled until a 28-day observe-only baseline completes. The exit criteria are fixed; only elapsed time remains. |

## Conventions

- Documentation, code comments, and API descriptions are **English**. The portal ships an English and Vietnamese UI; see the localization section of [architecture.md](architecture.md).
- A document states what is **implemented** and separately what is **not**. Where a control has never been exercised outside CI, that is written down rather than implied.
- Dated documents (`*-2026-08-16`) are records of a specific run and are not updated in place.
