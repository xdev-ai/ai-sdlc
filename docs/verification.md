# Verification

What has actually been run, as distinct from what is implemented. The end-to-end acceptance suite is the primary artefact; the rest are dated records of specific runs.

- [End-to-End Acceptance: Running the Governed Flow](#end-to-end-acceptance-running-the-governed-flow)
- [P3 Implementation Prototype: Chaos, Cost Ledger, and Runtime AI Decisions](#p3-implementation-prototype-chaos-cost-ledger-and-runtime-ai-decisions)
- [Release Verification Report — 2026-08-16](#release-verification-report-2026-08-16)

## End-to-End Acceptance: Running the Governed Flow

**Status:** Implemented and green locally against the Docker Compose topology; wired into the CI integration job.
**Why it exists:** three production defects survived 175 passing unit tests and a green public-health smoke. All three were found the first time the platform was actually driven through its own flow.

### What it proves

`scripts/end-to-end-acceptance.sh` drives one project through the chain the platform exists to enforce, on a real PostgreSQL, a real Keycloak, and real object storage:

| Step | Assertion |
|---|---|
| 0 | Keycloak administration reachable; CLI service account elevated for the run (test environment only) |
| 1 | Client-credentials token issued, carries the control-plane audience, carries **no** `agent_runtime` role |
| 2 | Organization and project created through the authenticated API |
| 3 | Repository linked |
| 4 | Validation evidence ingested through the CLI contract; a replayed idempotency key returns the same run; digest and model pin are stored |
| 5 | CEL policy bundle activated only after its fixtures pass; evaluates true, false, and does not pass on a missing input; evaluation evidence retained |
| 6 | Approval requested with quorum, decided to `APPROVED`, and a second decision refused |
| 7 | HMAC-signed webhook accepted and correlated; a replayed delivery de-duplicated; a forged signature refused |
| 8 | Release provenance recorded, starts `DECLARED`, and only a human decision moves it to `VERIFIED` while still pinning the artifact digest |
| 9 | **Audit hash chain verifies over the events this run produced**, and the ledger recorded them |
| 10 | The management API is **not** reachable from the host — it stays on the private network |

Step 9 is the one that matters. Verifying an empty ledger proves nothing; verifying a chain built by a real sequence of governed mutations is the platform's core guarantee.

### The three defects it found

Each was invisible to the unit suite because nothing there reaches a real database.

#### 1. Every `jsonb` column was bound as `varchar`

```
ERROR: column "payload" is of type jsonb but expression is of type character varying
  insert into audit_events (... payload ...)
```

Nine entities used `@Column(columnDefinition = "jsonb")`. That attribute only shapes generated DDL, and `ddl-auto` is `validate`, so at runtime Hibernate bound a `String` as `varchar` and PostgreSQL refused the implicit cast. **No audit event could ever be inserted.** Fixed with `@JdbcTypeCode(SqlTypes.JSON)` on all nine.

#### 2. `audit_events.tenant_id` was NOT NULL with no entity field

V11 added the column and made it mandatory; `AuditEvent` never carried it. Every append violated the constraint. `AuditService` now resolves the tenant from the organization it already locks.

#### 3. The audit hash chain could never verify

With the first two fixed, verification still failed: `Hash chain mismatch at sequence 1` on a ledger of three valid events.

The append hashed the payload string as written, `{"slug":"acc-1"}`. PostgreSQL normalises `jsonb`, so verification read back `{"slug": "acc-1"}` and hashed a different string. Both paths now hash a canonical form derived from the parsed value, so the printed representation cannot affect the chain. `AuditPayloadCanonicalizer` and its test pin the property.

### Realm defects found alongside

The documented local flow — copy `.env`, `docker compose up`, sign in — could not work:

- The realm import declared no client secrets, so Keycloak generated random ones and no consumer could authenticate. `${VAR}` placeholders now bind them, and Compose passes the values through. (`$(env:VAR)` is `keycloak.conf` syntax; realm import stores it verbatim, which fails silently.)
- Service accounts had no platform realm role, so every `/api/**` call was refused. The CLI service account now holds `developer`, and the agent-runtime account holds only `agent_runtime`.
- The realm shipped with **no human user at all**, so nobody could sign in to the portal. A development-only `platform-admin` is now imported with a password from the environment.

### Running it

```sh
cp .env.example .env      # replace every placeholder
docker compose up -d --build
bash scripts/end-to-end-acceptance.sh
```

In CI it runs inside `scripts/integration-smoke.sh`, which owns the topology lifecycle — a separate job step would find the stack already torn down.

### Two further defects found when the flow was extended

#### 4. Every endpoint carrying a `JsonNode` body was unreachable

```
HttpMessageConversionException: Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]
```

Spring Boot 4 registers a Jackson 3 converter by default, and the controllers model JSON with Jackson 2's `JsonNode`. Six controllers were affected — policy bundles and evaluation, runtime AI governance, the provider proxy, the tool broker, and SCM repository registration. Every request or response carrying a `JsonNode` failed with HTTP 400. Unit tests call controller methods directly and never cross a message converter, so none of them could see it.

`JacksonWebConfiguration` registers the application's Jackson 2 mapper for the web layer.

#### 5. The first version of that fix broke webhook signature verification

Registering the Jackson 2 converter at index 0 made it claim `application/json` for `byte[]` as well. The GitHub webhook endpoint takes the raw body as `byte[]` precisely so it can verify an HMAC over the exact bytes received, so signature verification began failing. The converter is now inserted immediately before the Jackson 3 converter, leaving `ByteArrayHttpMessageConverter` ahead of it.

Recording this because it is the same lesson: the acceptance run caught a regression that the unit suite passed straight through.

### Limits

This runs against Docker Compose, not a Kubernetes cluster, and against no external provider tenant. The webhook stage signs its own delivery, which exercises verification, correlation, and de-duplication but not a real GitHub App installation.

Still not covered: evidence upload to object storage and SBOM ingestion. The file is structured so each is an added step, not a new harness.

The policy-pack catalog has a genuinely signed release: `v0.1.0` of `xdev-ai/ai-sdlc-policies` carries a keyless Cosign signature over its checksum manifest, and `cosign verify-blob` against the downloaded assets reports `Verified OK` — verified with a cosign binary outside the workflow that produced it, not merely a green job.

Step 0 grants the CLI service account the `admin` role through the Keycloak admin API. That is a test-environment convenience and must never be pointed at a shared or production realm. The committed realm still grants that account only `developer`.

---

## P3 Implementation Prototype: Chaos, Cost Ledger, and Runtime AI Decisions

**Status:** Foundation implementation; not approved for production enforcement.

### Safety Boundary

`ChaosFaultRegistry` is only registered when Spring runs with the explicit `chaos` profile. It has no bean in default, production, or shared integration profiles. The registry is a deterministic fault seam for test adapters; it does not execute network, database, storage, or process-level destructive actions.

| Component scope | Injected outcomes | Required production behavior |
|---|---|---|
| `POLICY_ENGINE` | timeout, unavailable | Deny the governed action and retain failure evidence. |
| `NOTIFICATION_PROVIDER` | timeout, unavailable | Preserve decision; enqueue a bounded retry without changing approval outcome. |
| `EVIDENCE_STORAGE` | timeout, unavailable | Do not allow an action that requires evidence finalization. |
| `AUTHENTICATION` | timeout, unavailable | Reject new workload authorization; never reuse another identity. |
| `SCM_INGRESS` | timeout, unavailable | Retry only idempotent inbound processing. |
| `RUNTIME_AI_PROVIDER` | timeout, unavailable | Fail closed for governed delivery actions. |

### P3.2 Provider-Neutral Cost Foundation

Flyway V14 introduces `inference_usage_events`, `inference_cost_allocations`, and `inference_cost_forecasts`. All monetary values use integer minor units and ISO currency codes; the ledger does not use floating-point money. Usage ingestion is project-scoped, keyed idempotently by an immutable provider source-event key, and stores a SHA-256 source claim. The initial allocation is `SOURCE_COST_EXACT` to the owning project, preventing unreviewed cross-project allocation logic.

The initial forecast is intentionally explainable: a trailing daily-cost mean is applied to a bounded 1–90-day horizon. Fewer than seven observed active days creates an `INSUFFICIENT_DATA` forecast with no numeric recommendation. Forecasts never change a provider, model, budget, or routing decision.

### P3.3 Runtime AI Governance Foundation

`RuntimeAiGovernanceService` evaluates an active CEL policy through the existing side-effect-free `PolicyEvaluationService` and persists a `runtime_ai_decision`. Only an explicit Boolean `PASS` becomes `ALLOW`; compile, context, evaluation, non-Boolean, or policy-fail outcomes become `DENY`. Decision rows are idempotent by project, stage, and request fingerprint. They contain no raw prompt or response content, only a canonical context digest and policy-evaluation linkage.

The exposed project-scoped APIs are intentionally limited to prototype operations:

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/projects/{projectId}/inference-costs/usage` | Ingest a validated, idempotent inference usage claim. |
| `POST /api/v1/projects/{projectId}/inference-costs/forecasts` | Produce an advisory baseline cost forecast. |
| `POST /api/v1/projects/{projectId}/runtime-ai-governance/decisions` | Evaluate CEL and return an auditable fail-closed decision. |

Workload client credentials, provider proxying, post-flight approval mutation, external tool brokering, pricing-catalog governance, budget enforcement, and production chaos game days remain explicitly out of scope for this foundation and must retain their P3 backlog status.

### Next Foundation Increment: Budget and Authorization Broker

The follow-on V15 foundation adds immutable project budget policies, decisions, human-approved expiry-bound exceptions, registered workload identities, provider/model allowlist profiles, and tool capability profiles. `BudgetEnforcementService` evaluates the current calendar-month allocation before provider pre-flight authorization. `RuntimeAiBrokerService` is deliberately authorization-only: it cannot send a provider request, execute a tool, or access provider credentials. It only returns a fail-closed authorization decision with policy and budget evidence linkage. See [`cost-governance.md#p3-budget-enforcement-and-runtime-broker-foundation`](cost-governance.md#p3-budget-enforcement-and-runtime-broker-foundation) for the configuration and rollout contract.

---

## Release Verification Report — 2026-08-16

### Scope

The active delivery worktree is `/home/ubuntu/ai-sdlc-new` on `xdev-ai/ai-sdlc` `main` at commit `b7860a9`. This report verifies every independently buildable artifact shipped by that repository.

`/home/ubuntu/ai-sdlc` is an older local clone of the same remote at `14e368b`; it was not treated as a separate product release because it is behind the active `main` worktree. `/home/ubuntu/ai-sdlc-rebuild` is not a Git repository. `/home/ubuntu/ai-sdlc-platform` is a separate Manus web application repository and is not a module of `xdev-ai/ai-sdlc`.

### Results

| Artifact | Verification command | Result |
|---|---|---|
| Java reactor: management server, SSR portal, Java SDK | `mvn --batch-mode --no-transfer-progress verify` | Passed |
| Deterministic Go CLI | `go test ./... && go build ./cmd/aisdlc` | Passed |
| Terraform provider | `gofmt -d .` and `go test ./...` | Passed |
| TypeScript SDK | `npm run build && npm test` | Passed |
| VS Code extension | `npm test` | Passed |
| React Islands frontend | `npm run build` | Passed |
| Production/security guardrails | `scripts/verify-production.sh`, Trivy ignore expiry and SARIF policy tests | Passed |

No build or test failure was found in any independently buildable artifact of the active `xdev-ai/ai-sdlc` release. The repository-specific instructions, configuration, build commands, and verification steps are documented in [`integrations-and-sdks.md#module-usage-and-verification-guide`](integrations-and-sdks.md#module-usage-and-verification-guide).

### UI Evidence

[`screenshots/portal-landing-en.png`](screenshots/portal-landing-en.png) was captured from the running SSR portal at 1440×1100. It verifies the public landing surface, English localisation, governance model, and control-plane entry point.

Authenticated control-plane pages are intentionally not represented by fabricated static captures. They require an active Keycloak OIDC session plus the management-server API and data services. Their functional evidence is the verified reactor, targeted controller/service tests, and Docker Compose smoke gate. The screenshot scope and runtime prerequisites are stated in [`screenshots/README.md`](screenshots/README.md).
