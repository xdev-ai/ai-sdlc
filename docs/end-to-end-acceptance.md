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
| 4 | **Audit hash chain verifies over the events this run produced**, and the ledger recorded them |
| 5 | The management API is **not** reachable from the host — it stays on the private network |

Step 4 is the one that matters. Verifying an empty ledger proves nothing; verifying a chain built by a real sequence of governed mutations is the platform's core guarantee.

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

## Limits

This runs against Docker Compose, not a Kubernetes cluster, and against no external provider. It does not yet cover policy evaluation, approval quorum, evidence upload to object storage, SBOM provenance, or a real SCM webhook. Those steps belong in this script as the corresponding surfaces become reachable without a real provider tenant; the file is structured so each is an added step, not a new harness.

Step 0 grants the CLI service account the `admin` role through the Keycloak admin API. That is a test-environment convenience and must never be pointed at a shared or production realm. The committed realm still grants that account only `developer`.
