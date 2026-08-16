# AI-SDLC Build 1 — Operations Guide

## Purpose

Build 1 provides a **control plane for AI-assisted software delivery**. The system never makes decisions in place of people: the Go CLI performs deterministic validation, Spring Boot applies policy and authorization, PostgreSQL stores evidence and audit data, and the SSR portal presents state to authorized users.

> **Operating invariants:** the CLI never calls AI; `--bare` is prohibited; `--model` must be pinned to a model revision; a review or phase gate completes only after a human decision.

## Local topology

| Component | Technology | Public port | Responsibility |
|---|---:|---:|---|
| Portal | Spring MVC + Thymeleaf SSR | `8080` | User interaction entry point |
| Identity gateway | Nginx | `8180` | Public Keycloak edge at `auth.localhost` |
| Keycloak | 26.7.1 | Not directly public | OpenID Connect and realm roles |
| Management server | Spring Boot REST API | Not directly public | Policy, evidence, review, and audit |
| PostgreSQL | 18.6 | Not directly public | Transactional control-plane store |
| MinIO | S3-compatible object storage | Not directly public | Evidence Repository bytes and versioned Object Lock bucket |
| CLI | Go | N/A | Local validation and evidence synchronization |

Keycloak sits **behind the identity gateway**. In Docker Compose, internal services must not publish ports indiscriminately: the portal is the application entry point and the identity gateway is the OIDC entry point.

## Local startup

Copy the environment file and replace every placeholder secret with local secrets. Never commit `.env`.

```bash
cp .env.example .env
docker compose up --build
```

After health checks complete, open `http://localhost:8080`. The portal redirects users to Keycloak through `http://auth.localhost:8180`. The callback is fixed at `/login/oauth2/code/keycloak`.

## Roles

| Keycloak role | Build 1 permission |
|---|---|
| `admin` | Manage organizations/projects, register or pin kits, manage policy/constitutions and capability grants, and view audit data |
| `developer` | Submit evidence from the CLI and view validation, traceability, and quality in projects where membership exists |
| `reviewer` | View project scope and make APPROVED/REJECTED decisions for review and phase gates |

A realm role is insufficient to access project data: the management server always also verifies **project membership**. This protects against a developer or reviewer with organizational access reading an out-of-scope project.

## Developer workflow

Create a `spec-kit` containing at least `constitution.md`, `spec.md`, and `tasks.md`. Initialize `.aisdlc.yml` once, commit governance configuration without secrets, then run validation with a clearly pinned model revision. Validation never calls the model—the model pin is stored only as required provenance.

```bash
cd cli
go run ./cmd/aisdlc init \
  --project <project-uuid> \
  --api-url http://localhost:8081 \
  --spec-dir ../my-project/spec-kit \
  --kit-version core@1.0.0 \
  --model provider/model@revision
go run ./cmd/aisdlc validate --config .aisdlc.yml --format json --out validation-result.json

AISDLC_ACCESS_TOKEN="$TOKEN" go run ./cmd/aisdlc sync \
  --config .aisdlc.yml \
  --result validation-result.json \
  --idempotency-key <ci-run-key>
```

`sync` calls `POST /api/v1/cli/projects/{projectId}/validation-runs`. Its idempotency key lets CI retry without duplicating evidence or audit events. The API stores the validation run, findings, evidence, and audit event in one unit of work.

To store large evidence or governance artifacts, use `upload`. The CLI calculates SHA-256 locally, submits the digest for management-server verification, and **does not** receive or store MinIO/S3 credentials.

```bash
AISDLC_ACCESS_TOKEN="$TOKEN" go run ./cmd/aisdlc upload ./validation-result.json \
  --config .aisdlc.yml \
  --asset-type VALIDATION \
  --access-level PROJECT \
  --json
```

MinIO exists only on the private Compose network. `evidence-bucket-init` creates the `AISDLC_EVIDENCE_S3_BUCKET` bucket with Object Lock idempotently before the management server starts. Local `.env` must contain `AISDLC_EVIDENCE_S3_ACCESS_KEY` and `AISDLC_EVIDENCE_S3_SECRET_KEY`; replace both example values with your own secrets and never commit the file.

## REST resource map

| Resource | Purpose |
|---|---|
| `/api/v1/organizations/{organizationId}/projects` | Project portfolio and controlled project creation |
| `/api/v1/organizations/{organizationId}/spec-kits` | Core/extension/preset/override registry with version pinning |
| `/api/v1/projects/{projectId}/validation-runs` | Dashboard of synchronized evidence |
| `/api/v1/projects/{projectId}/traceability` | Requirement → specification → task → test → evidence nodes and edges |
| `/api/v1/projects/{projectId}/policies` and `/constitutions` | Versioned governance-as-data |
| `/api/v1/projects/{projectId}/review-items` | Human review and phase-gate decisions |
| `/api/v1/projects/{projectId}/quality-metrics` | DORA counter-metrics and specification alignment |
| `/api/v1/projects/{projectId}/evidence-assets` | Upload/list evidence metadata; details return an authorized presigned download URL; retention and soft deletion are audit-backed |
| `/api/v1/organizations/{organizationId}/audit-events` | Append-only audit-ledger hash chain |

## Audit integrity

Every event receives `sequence`, `previous_hash`, and `event_hash`. A database migration installs a trigger that prohibits both `UPDATE` and `DELETE` on `audit_events`; the application also offers no endpoint to edit or remove audit data. Every supported validation, policy, review, exception, or agent-launch action must go through `AuditService`.

## Pre-merge checks

```bash
mvn test
mvn -DskipTests package
cd cli && go test ./... && go build ./cmd/aisdlc
bash ../scripts/verify-production.sh
```

The build passes Java unit tests, Go tests, and Maven packaging. Full integration regression requires a Docker daemon to start PostgreSQL, Keycloak, the identity gateway, and MinIO. When a development environment has no Docker daemon, that verification remains explicitly pending and must not be treated as complete; GitHub Actions runs the Compose smoke test on a Docker-capable runner. See [`cli.md`](cli.md), [`control-plane-api.md`](control-plane-api.md), [`portal-workflows.md`](portal-workflows.md), [`continuous-delivery.md`](continuous-delivery.md), and [`production-operations.md`](production-operations.md) for detailed production contracts.
