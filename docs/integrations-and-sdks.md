# Integrations and SDKs

Everything that talks to the control plane from outside: the CLI, the language SDKs, the Terraform provider, the IDE manifest, and the module contracts that let a package be adopted on its own.

- [AI-SDLC CLI](#ai-sdlc-cli)
- [MCP server: the knowledge base inside an AI assistant](#mcp-server-the-knowledge-base-inside-an-ai-assistant)
- [SDK Reference](#sdk-reference)
- [Terraform Provider](#terraform-provider)
- [IDE Integration](#ide-integration)
- [AI-SDLC module integration guide](#ai-sdlc-module-integration-guide)
- [Module Usage and Verification Guide](#module-usage-and-verification-guide)

## AI-SDLC CLI

`aisdlc` is a local, deterministic governance validator. It performs filesystem inspection, hashing, serialization and HTTPS calls only. It **does not invoke an AI model**, transmit source files for model inference, or permit a bare/governance-bypass mode.

### Invariants

| Invariant | Enforcement |
|---|---|
| Pinned model provenance | `validate` requires `--model provider/model@immutable-revision`; floating aliases and `latest` are rejected. |
| No bare execution | `--bare` always returns an error, even when all artifacts are otherwise valid. |
| Human decisions remain human | The CLI can create evidence and review requests but cannot submit a final review or exception decision. |
| Retry-safe evidence sync | Every `sync` requires an explicit idempotency key and resubmits the same payload/key when retrying transient failures. |

### Configuration and Authentication

Initialize a repository-scoped configuration file and review it with the same care as any governance artifact:

```bash
aisdlc init \
  --project "<project-uuid>" \
  --api-url "https://control.example.com" \
  --spec-dir "." \
  --kit-version "core@1.2.0" \
  --model "provider/model@immutable-revision"
```

This generates `.aisdlc.yml`, which contains project routing and governance provenance but **never credentials**. A build uses this file by default, or a different path via `--config`.

For developers and CI identities, the Keycloak CLI client uses OAuth 2.0 client credentials. The client realm intentionally does not enable password grants. Obtain a token with an injected secret rather than committing credentials:

```bash
export AISDLC_CLIENT_SECRET="…"
aisdlc login \
  --token-url "https://auth.example.com/realms/ai-sdlc/protocol/openid-connect/token"
```

The command stores a short-lived token in the platform configuration directory using `0600` file permissions. At sync time the lookup order is `--token`, `AISDLC_ACCESS_TOKEN`, then the stored token. CI should prefer the environment variable and avoid persisting credentials on runners.

### Deterministic Validation

```bash
aisdlc validate --config .aisdlc.yml --format json --out validation-result.json
```

The validator checks for `constitution.md`, `spec.md` and `tasks.md`; rejects empty artifacts; requires a version/revision declaration in the constitution; requires Markdown structure in the specification; and requires checkbox tasks in the task list. It produces a stable SHA-256 digest over a sorted Spec Kit file tree as evidence. A non-passing result exits with code `1`; invocation/configuration errors exit with code `2`.

### CI Output and Sync

The `--format` option produces JSON (the sync payload), JUnit XML, or SARIF 2.1.0:

```bash
aisdlc validate --format junit --out aisdlc.junit.xml || true
aisdlc validate --format sarif --out aisdlc.sarif || true
aisdlc validate --format json --out validation-result.json
aisdlc sync --result validation-result.json --idempotency-key "${GITHUB_RUN_ID}-${GITHUB_SHA}"
```

`sync` retries transport failures, HTTP `429`, and 5xx responses with bounded exponential backoff and respects a bounded numeric `Retry-After` response. Validation failures other than those transient responses do not retry. HTTP `409` is reported as a likely idempotency conflict with the server response body to support operator diagnosis.

### Upload Evidence Assets

`upload` sends one file as a streaming multipart request to the project-scoped Evidence Repository. The command computes the SHA-256 locally, transmits it in `X-Content-SHA256`, and derives an idempotency key from the file digest and governance metadata when `--idempotency-key` is omitted. Consequently, a retry of the same content and classification cannot create a second asset or audit event.

```bash
aisdlc upload ./validation-result.json \
  --project "<project-uuid>" \
  --asset-type VALIDATION \
  --access-level PROJECT \
  --json
```

The supported asset types are `VALIDATION`, `SPECIFICATION`, `REVIEW`, `GOVERNANCE`, `DELIVERY`, and `OTHER`; access levels are `PROJECT`, `REVIEWERS`, and `OWNERS`. Pass `--validation-evidence <uuid>` only when linking to an existing validation evidence record in the same project. The CLI uses the same token resolution order as `sync`, retries transport failures/HTTP `429`/5xx with bounded exponential backoff, and never writes object-store credentials to `.aisdlc.yml` or logs.

Use `aisdlc status --json` for a local, non-network diagnostic of config and the last JSON validation result.

---

## MCP server: the knowledge base inside an AI assistant

An AI assistant on a developer's machine cannot use governed documentation it has no way to read. `aisdlc mcp` is a
Model Context Protocol server on stdio, so any assistant that speaks MCP — Claude Code, Claude Desktop, Cursor,
Windsurf, and others — can retrieve this project's documentation with citations and read the rules it must work under.

### Why it lives in the CLI binary

One install, one credential path, one thing to update. `aisdlc` is already installed to validate and sync, already
authenticates, and already stores a token at `~/.config/aisdlc/token.json`. A separate binary or an npm package would
mean a second install, a second login, and a second place for a bearer token to leak.

The protocol is implemented against the Go standard library. The CLI module has **no external dependencies and no
`go.sum`**, which is worth keeping for a process that runs on every developer machine and holds a credential: the
supply chain is the standard library. MCP is JSON-RPC 2.0 over newline-delimited stdio, and a tools-only server needs
a small enough subset that a dependency would cost more than it saves.

### Tools it exposes

| Tool | Returns |
|---|---|
| `aisdlc_get_rules` | The governing bundle as Markdown: active constitution, active policies, pinned Spec Kits, available documentation, and the platform invariants. |
| `aisdlc_search_docs` | Matching sections with the heading path that cites each one. Accent-insensitive: `tiep nhan` finds `tiếp nhận`. |
| `aisdlc_get_context` | A prompt-sized bundle for a question, every section carrying a citation, with the character budget stated. |
| `aisdlc_read_page` | One page in full at its current version. |

Failures arrive as tool content flagged `isError`, not as transport errors, so the model reads the message and can
correct itself. An expired credential says `run aisdlc login again` rather than returning nothing — an MCP server that
fails quietly presents inside an editor as tools that mysteriously return empty.

An empty search result is worded deliberately: *no wording matched, which is not evidence that the documentation omits
the subject.* Retrieval is lexical, and an agent that conflates "no match" with "not documented" will confidently
report that a requirement does not exist.

### Install

Log in once, then register the server with the assistant.

```bash
aisdlc login --token-url https://auth.example/realms/ai-sdlc/protocol/openid-connect/token --client-secret <secret>
```

Claude Code:

```bash
claude mcp add aisdlc -- aisdlc mcp --api-url https://control.example.com --project <project-uuid>
```

Anything that reads a JSON config (Claude Desktop, Cursor, Windsurf):

```json
{
  "mcpServers": {
    "aisdlc": {
      "command": "aisdlc",
      "args": ["mcp", "--api-url", "https://control.example.com", "--project", "<project-uuid>"]
    }
  }
}
```

The organization is **not** configured: the server resolves it from the project through the rules endpoint and
remembers it. A value nobody can derive is a value people paste wrongly.

`--api-url`, `--project` and `--token` each fall back to `.aisdlc.yml`, then to `AISDLC_API_URL`, `AISDLC_PROJECT` and
`AISDLC_ACCESS_TOKEN`. Diagnostics go to stderr, never stdout: stdout is the protocol transport, and one stray log line
there corrupts the stream into an unhelpful "server disconnected".

### The rules bundle is composed by the server

`GET /api/v1/projects/{projectId}/agent-rules` returns the bundle as JSON, and `/agent-rules/markdown` returns the text
an agent is handed. Both are assembled server-side on purpose.

Every part of it already exists as its own endpoint, so a client could fetch four things and combine them — and then
two machines running two client versions would disagree about what the rules are. Rules that differ per machine are not
rules. The Markdown is rendered server-side for the same reason: a client that formats the rules can also quietly
soften them.

The bundle reports `completeness` as `COMPLETE`, `PARTIAL` or `UNCONFIGURED` with a `missing` list, because "no rules
apply here" and "nobody has configured the rules yet" are different situations and an agent must not treat the second
as the first. Where a constitution is absent it says so explicitly: *do not invent one.*

`invariants` are statements about how this platform behaves, each enforced somewhere in the codebase — validation never
calls a model; evidence enters through the CLI or a webhook, never the UI; only pinned kits apply; a finding closed as
`FALSE_POSITIVE` needs a rationale; retrieval is lexical; traceability is never inferred. An agent that does not know
these produces confidently wrong work, such as telling a human to look for a button that does not exist.

Verified by `scripts/knowledge-sweep.sh` against the live stack, and by the Go tests in `cli/internal/mcp`, which
exercise the handshake, notification handling, malformed input, argument validation, credential failure, and the
organization resolution being cached rather than repeated.

## SDK Reference

### Versioning

The public integration surface uses stable `/api/v1` routes and RFC 9457 problem responses. The Java and TypeScript SDKs are versioned independently using semantic versioning. A client release never silently changes its generated OpenAPI contract; upgrade the client major version when the API contract makes a breaking change.

### Java

The `sdk` Maven module uses `sdk/openapi/aisdlc-integration-v1.yaml` as its source of truth. Generate the Java client during the Maven build:

```bash
mvn -pl sdk generate-sources package
```

The generated artifact is `ai.xdev:aisdlc-java-sdk`. It targets the developer integration routes for SCM event ledgers, notification channels, approval queues, and risk intelligence. Supply a bearer token per request; do not embed a static token in source code or generated configuration.

### TypeScript

The hand-maintained TypeScript client is in `sdk/typescript` and deliberately exposes a narrow, tested subset of stable v1 routes:

```bash
cd sdk/typescript
npm install
npm run build
npm test
```

```ts
import { AiSdlcClient } from "@xdev-ai/aisdlc-sdk";

const client = new AiSdlcClient({
  baseUrl: "https://control.example",
  accessToken: process.env.AISDLC_TOKEN!
});
const score = await client.getLatestRiskScore("project-uuid");
```

Clients surface unsuccessful responses as `AiSdlcApiError`, which includes the HTTP status and parsed RFC 9457 response body. Never retry authorization, validation, or policy errors automatically; retry only transient transport failures and rate-limit responses with bounded backoff.

---

## Terraform Provider

The provider source resides in `infra/terraform-provider`. It uses Terraform Plugin Framework and exposes a small, auditable configuration surface. It never stores AI-SDLC access tokens in state: `token` is sensitive and may instead be injected using `AISDLC_TOKEN`.

```hcl
terraform {
  required_providers {
    aisdlc = {
      source = "xdev-ai/aisdlc"
      version = "0.1.0"
    }
  }
}

provider "aisdlc" {
  api_url    = var.aisdlc_api_url
  project_id = var.project_id
  # token = var.aisdlc_token # prefer AISDLC_TOKEN in CI
}

resource "aisdlc_notification_channel" "release_governance" {
  type          = "GENERIC_WEBHOOK"
  name          = "release-governance"
  destination   = "https://receiver.example/aisdlc"
  shared_secret = var.webhook_secret
  enabled       = true
}

data "aisdlc_risk_snapshot" "latest" {}
```

`terraform destroy` disables a notification channel instead of deleting it. This preserves delivery/audit evidence and is intentional. Rotation should create a replacement channel, verify the receiving system, then disable the prior channel.

Build and test locally:

```bash
cd infra/terraform-provider
go test ./...
go build ./...
```

---

## IDE Integration

The VS Code extension manifest lives in `ide/vscode`. It provides two commands that call only existing, deterministic interfaces:

| Command | Behavior |
|---|---|
| `AI-SDLC: Validate Workspace` | Runs `aisdlc validate --format junit` in the selected workspace and streams output to the AI-SDLC channel. |
| `AI-SDLC: Open Governance Portal` | Opens the configured `aisdlc.portalUrl` in the default browser. |

Configure `aisdlc.cliPath` when the CLI is not on `PATH`. Configure `aisdlc.portalUrl` with the portal address appropriate to the current project. The extension does not execute an AI model, capture prompt text, make approval decisions, or store access tokens.

Use the supplied validation command as a pre-commit feedback loop. CI and the AI-SDLC control plane remain authoritative for policy gates and human approval requirements.

---

## AI-SDLC module integration guide

### Objective and stable boundaries

AI-SDLC is delivered as a Maven reactor and control-plane service, while its capabilities are separated into bounded modules. The supported integration paths for external systems are the versioned **`/api/v1` API**, protected OpenAPI, OAuth2/JWT, and the Go CLI. Do not integrate by directly accessing the PostgreSQL schema, JPA entities, or internal repositories because those components are not compatibility contracts.

| Need | Supported integration contract | Do not depend on |
|---|---|---|
| Submit deterministic validation | `POST /api/v1/cli/projects/{projectId}/validation-runs` or `aisdlc sync` | `validation_runs` table or `ValidationRun` entity |
| Store an artifact/evidence | `POST /api/v1/projects/{projectId}/evidence-assets` or `aisdlc upload` | Direct bucket/key access or S3/MinIO credentials |
| Retrieve evidence | `GET /api/v1/projects/{projectId}/evidence-assets` and a detail response with a presigned URL | Long-lived object-storage URLs or bucket listings |
| Governance/review | Corresponding REST resources and the audit-verification endpoint | Creating `ReviewDecision` or `AuditEvent` through SQL |
| Embed in the JVM source tree | `ObjectStoragePort` is replaceable; service/repository packages remain internal implementations | AWS SDK, MinIO SDK, or another module's JPA repositories |

> **Integration rule:** Every review or exception decision must still be submitted through the control plane by a human principal with the appropriate role and project membership. An integrator must not replace that decision with an agent or automated job.

### Recommended HTTP integration

An integration creates an OAuth2 client with the minimum required scope or realm role, receives a JWT from Keycloak, and sends it in `Authorization: Bearer`. The project ID must be explicitly selected; a realm role is insufficient because the server always also verifies project membership. The API returns RFC 9457 `application/problem+json` errors; clients retry only transport failures, `429`, and `5xx` responses with bounded backoff. Interactive OpenAPI is available at `/swagger-ui.html`; the raw document is available at `/v3/api-docs` for administrators.

For the Evidence Repository, clients send multipart data containing `file`, `assetType`, `accessLevel`, and an optional `validationEvidenceId`. `X-Content-SHA256` lets the server verify the received bytes. `Idempotency-Key` must remain stable across retries; if omitted, the server derives one from provenance metadata and the digest. Downloads always pass through API authorization and return a short-lived presigned URL, never a public object-storage endpoint.[1]

### Storage extension point

`ObjectStoragePort` is the anti-corruption layer for the evidence module. The default `S3ObjectStorageAdapter` uses AWS SDK for Java 2.x with endpoint override and path-style addressing for MinIO. A deployment that uses another S3-compatible provider replaces only the adapter and configuration; it does not replace the controller, audit behavior, authorization, or persistence metadata. AWS recommends importing the SDK BOM together with the service modules and HTTP client actually used to preserve version alignment.[2]

A replacement adapter must provide four behaviors: write private objects with SHA-256 and project metadata; generate time-limited presigned GET URLs; apply Object Lock retention; and compensate by deleting only when metadata persistence rolls back. It must not decide RBAC, alter the SHA-256 value, or issue public URLs.

| Property | Purpose | Local Compose example |
|---|---|---|
| `AISDLC_EVIDENCE_S3_ENDPOINT` | Private S3-compatible endpoint | `http://minio:9000` |
| `AISDLC_EVIDENCE_S3_REGION` | Signing region | `us-east-1` |
| `AISDLC_EVIDENCE_S3_BUCKET` | Bootstrapped Object Lock bucket | `aisdlc-evidence` |
| `AISDLC_EVIDENCE_S3_ACCESS_KEY` / `...SECRET_KEY` | Control-plane runtime credentials | From a secret manager, never from CLI or browser |
| `AISDLC_EVIDENCE_S3_FORCE_PATH_STYLE` | MinIO/local endpoint compatibility | `true` |

### Versioning, testing, and upgrades

Consumers must pin a release image or binary version, inspect the OpenAPI diff before a minor upgrade, and run a contract smoke test: create/upload twice with the same idempotency key, list by project, verify download authorization for all three access levels, extend retention, and verify the audit chain. Do not assume that non-public Java classes or Flyway schema migrations are compatible APIs.

An independent Java client Maven artifact (`sdk/`) has not yet been published. Until that artifact has its own semantic versioning and compatibility policy, HTTP/OpenAPI and the CLI are the officially supported integration boundaries.

### References

[1] [AWS SDK for Java 2.x — S3 presigning](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)

[2] [AWS SDK for Java 2.x — Maven setup and BOM alignment](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html)

---

## Module Usage and Verification Guide

This guide is the practical entry point for every independently usable AI-SDLC artifact. It complements the architecture and feature documents; it does not weaken the platform invariants: the deterministic validator does not call AI, `--bare` is rejected, a model pin is mandatory, and governance decisions require human approval.

### Repository Scope

The authoritative workspace is the `main` branch of `xdev-ai/ai-sdlc`. Local directories named `ai-sdlc` and `ai-sdlc-xdev` may be historical clones of the same remote and must not be used for new changes. The separate `ai-sdlc-platform` workspace is a Manus web-project scaffold and is not a build artifact of this repository.

### Prerequisites

Use Java 25, Go 1.24 or later, Node.js 22 or later, Docker Compose, and the standard Terraform CLI. Create a local `.env` from `.env.example` and replace every development secret before starting the production-like topology. Never commit `.env` or provider credentials.

### Module Quick Start

| Module | Build and verification | How to use it |
|---|---|---|
| Management Server | `mvn --batch-mode --no-transfer-progress verify` at repository root | Start the Compose topology, authenticate through the portal, and use the versioned project-scoped REST APIs. See [Control Plane API](architecture.md#control-plane-api). |
| SSR Portal | `cd portal/frontend && npm run build` plus root Maven verification | Open `http://localhost:8080` after `docker compose up --build`. Sign in through Keycloak and choose the intended organization/project scope before a governance action. See [Portal Workflows](architecture.md#portal-workflows). |
| Go CLI | `cd cli && go test ./... && go build ./cmd/aisdlc` | Run `aisdlc validate` only with a pinned `--model`; use the documented project and evidence arguments. See [CLI Guide](integrations-and-sdks.md#ai-sdlc-cli). |
| Java SDK | Root Maven verification generates and tests the SDK | Add the generated Java client dependency to an integration application and configure its API base URL plus bearer token through that application's secret store. See [SDK Reference](integrations-and-sdks.md#sdk-reference). |
| TypeScript SDK | `cd sdk/typescript && npm test && npm run build` | Install the package in a Node.js integration, provide the project-scoped API endpoint and bearer token, and handle RFC 9457 errors explicitly. See [SDK Reference](integrations-and-sdks.md#sdk-reference). |
| Terraform Provider | `cd infra/terraform-provider && go test ./... && go build ./...` | Configure provider endpoint/token through Terraform variables or a secure environment, then manage supported notification resources and risk data sources. See [Terraform Provider](integrations-and-sdks.md#terraform-provider). |
| VS Code Integration | `cd ide/vscode && npm test` | Install or package the extension from this module; its commands invoke only the documented deterministic CLI and portal workflows. See [IDE Integration](integrations-and-sdks.md#ide-integration). |
| Observability Configuration | `sh scripts/validate-observability-config.sh` | Review the Collector routing and Prometheus SLO rules before promotion. The script runs `otelcol validate` and `promtool check rules` in digest-pinned, isolated containers. See [Observability README](../infra/observability/README.md). |

### Local Topology and Portal Workflows

Run `docker compose up --build` from the repository root after providing local development secrets. The public browser entry point is `http://localhost:8080`; the portal and identity provider should remain behind the Compose topology boundary. The Management API is not a directly exposed public endpoint.

After authentication, work from a project scope. The portal exposes governance evidence, policy bundles, approval workflows, SCM event correlation, supply-chain provenance, risk intelligence, enterprise tenancy, and the P3 foundations for cost/budget and runtime AI decision evidence. Treat the P3 provider/tool broker as an authorization foundation only: it does not send a provider request or execute a tool.

### P3 Foundation API Workflow

The P3 routes are project-scoped and must be called by an authorized project actor. The intended order is to register an approved budget or broker profile, ingest an immutable usage claim, generate an advisory forecast, then request a runtime decision or authorization pre-flight. A denied result is terminal for that request; do not retry it by changing policy context or identity.

| Capability | Route family | Safety behavior |
|---|---|---|
| Inference usage and forecast | `/api/v1/projects/{projectId}/inference-costs/*` | Monetary values use minor units; forecasts never route a model or spend money. |
| Budget decisions | `/api/v1/projects/{projectId}/budget-enforcement/*` | `ADVISORY` and `HOLD` decisions are retained as evidence; exceptions require explicit approved state and expiry. |
| Runtime CEL decision | `/api/v1/projects/{projectId}/runtime-ai-governance/*` | Evaluation is Boolean-only and fails closed; raw prompt/output content is not persisted. |
| Provider/tool authorization | `/api/v1/projects/{projectId}/runtime-ai-broker/*` | Allowlist, workload identity, capability, approval, and budget checks must all pass; this foundation does not execute the external action. |

### Release Verification Contract

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

### Troubleshooting

If Maven fails during generated SDK compilation, start with `mvn clean verify` and keep the reactor module sequence intact. If a frontend build fails, use the module-local lockfile and do not commit generated output. If the Compose smoke fails, capture service logs and retain database migration evidence before changing mappings. If a budget or broker request is denied, inspect its retained decision evidence rather than attempting to bypass CEL, workload identity, policy, approval, or cost guardrails.

### Related Documentation

The [Module Integration Guide](integrations-and-sdks.md#ai-sdlc-module-integration-guide) describes integration boundaries. The [Production Operations Guide](operations.md#production-operations-runbook) covers operational deployment. The [Security Scanning Guide](security.md#security-scanning) explains the fail-closed OSV/Trivy policy. The [P3 Implementation Prototype](verification.md#p3-implementation-prototype-chaos-cost-ledger-and-runtime-ai-decisions) and [Budget and Runtime Broker Guide](cost-governance.md#p3-budget-enforcement-and-runtime-broker-foundation) define the current P3 foundation scope and remaining rollout work.
