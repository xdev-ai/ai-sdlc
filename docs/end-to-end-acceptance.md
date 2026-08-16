# End-to-End Acceptance: Running the Governed Flow

**Status:** Implemented and green locally against the Docker Compose topology; wired into the CI integration job.
**Why it exists:** three production defects survived 175 passing unit tests and a green public-health smoke. All three were found the first time the platform was actually driven through its own flow.

## What it proves

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
| 8 | **Audit hash chain verifies over the events this run produced**, and the ledger recorded them |
| 9 | The management API is **not** reachable from the host — it stays on the private network |

Step 8 is the one that matters. Verifying an empty ledger proves nothing; verifying a chain built by a real sequence of governed mutations is the platform's core guarantee.

## The three defects it found

Each was invisible to the unit suite because nothing there reaches a real database.

### 1. Every `jsonb` column was bound as `varchar`

```
ERROR: column "payload" is of type jsonb but expression is of type character varying
  insert into audit_events (... payload ...)
```

Nine entities used `@Column(columnDefinition = "jsonb")`. That attribute only shapes generated DDL, and `ddl-auto` is `validate`, so at runtime Hibernate bound a `String` as `varchar` and PostgreSQL refused the implicit cast. **No audit event could ever be inserted.** Fixed with `@JdbcTypeCode(SqlTypes.JSON)` on all nine.

### 2. `audit_events.tenant_id` was NOT NULL with no entity field

V11 added the column and made it mandatory; `AuditEvent` never carried it. Every append violated the constraint. `AuditService` now resolves the tenant from the organization it already locks.

### 3. The audit hash chain could never verify

With the first two fixed, verification still failed: `Hash chain mismatch at sequence 1` on a ledger of three valid events.

The append hashed the payload string as written, `{"slug":"acc-1"}`. PostgreSQL normalises `jsonb`, so verification read back `{"slug": "acc-1"}` and hashed a different string. Both paths now hash a canonical form derived from the parsed value, so the printed representation cannot affect the chain. `AuditPayloadCanonicalizer` and its test pin the property.

## Realm defects found alongside

The documented local flow — copy `.env`, `docker compose up`, sign in — could not work:

- The realm import declared no client secrets, so Keycloak generated random ones and no consumer could authenticate. `${VAR}` placeholders now bind them, and Compose passes the values through. (`$(env:VAR)` is `keycloak.conf` syntax; realm import stores it verbatim, which fails silently.)
- Service accounts had no platform realm role, so every `/api/**` call was refused. The CLI service account now holds `developer`, and the agent-runtime account holds only `agent_runtime`.
- The realm shipped with **no human user at all**, so nobody could sign in to the portal. A development-only `platform-admin` is now imported with a password from the environment.

## Running it

```sh
cp .env.example .env      # replace every placeholder
docker compose up -d --build
bash scripts/end-to-end-acceptance.sh
```

In CI it runs inside `scripts/integration-smoke.sh`, which owns the topology lifecycle — a separate job step would find the stack already torn down.

## Two further defects found when the flow was extended

### 4. Every endpoint carrying a `JsonNode` body was unreachable

```
HttpMessageConversionException: Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]
```

Spring Boot 4 registers a Jackson 3 converter by default, and the controllers model JSON with Jackson 2's `JsonNode`. Six controllers were affected — policy bundles and evaluation, runtime AI governance, the provider proxy, the tool broker, and SCM repository registration. Every request or response carrying a `JsonNode` failed with HTTP 400. Unit tests call controller methods directly and never cross a message converter, so none of them could see it.

`JacksonWebConfiguration` registers the application's Jackson 2 mapper for the web layer.

### 5. The first version of that fix broke webhook signature verification

Registering the Jackson 2 converter at index 0 made it claim `application/json` for `byte[]` as well. The GitHub webhook endpoint takes the raw body as `byte[]` precisely so it can verify an HMAC over the exact bytes received, so signature verification began failing. The converter is now inserted immediately before the Jackson 3 converter, leaving `ByteArrayHttpMessageConverter` ahead of it.

Recording this because it is the same lesson: the acceptance run caught a regression that the unit suite passed straight through.

## Limits

This runs against Docker Compose, not a Kubernetes cluster, and against no external provider tenant. The webhook stage signs its own delivery, which exercises verification, correlation, and de-duplication but not a real GitHub App installation.

Still not covered: evidence upload to object storage, SBOM ingestion, and signed release provenance. The file is structured so each is an added step, not a new harness.

Step 0 grants the CLI service account the `admin` role through the Keycloak admin API. That is a test-environment convenience and must never be pointed at a shared or production realm. The committed realm still grants that account only `developer`.
