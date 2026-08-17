# Operations

Running the platform: the local topology, the production hardening baseline, Kubernetes deployment, backup and recovery, and the delivery pipeline.

- [AI-SDLC Build 1 — Operations Guide](#ai-sdlc-build-1-operations-guide)
- [Production Operations Runbook](#production-operations-runbook)
- [Production Hardening Baseline](#production-hardening-baseline)
- [Enterprise Deployment: Helm and GitOps](#enterprise-deployment-helm-and-gitops)
- [Continuous Integration and Release Delivery](#continuous-integration-and-release-delivery)

## AI-SDLC Build 1 — Operations Guide

### Purpose

Build 1 provides a **control plane for AI-assisted software delivery**. The system never makes decisions in place of people: the Go CLI performs deterministic validation, Spring Boot applies policy and authorization, PostgreSQL stores evidence and audit data, and the SSR portal presents state to authorized users.

> **Operating invariants:** the CLI never calls AI; `--bare` is prohibited; `--model` must be pinned to a model revision; a review or phase gate completes only after a human decision.

### Local topology

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

### Local startup

Copy the environment file and replace every placeholder secret with local secrets. Never commit `.env`.

```bash
cp .env.example .env
docker compose up --build
```

After health checks complete, open `http://localhost:8080`. The portal redirects users to Keycloak through `http://auth.localhost:8180`. The callback is fixed at `/login/oauth2/code/keycloak`.

### Roles

| Keycloak role | Build 1 permission |
|---|---|
| `admin` | Manage organizations/projects, register or pin kits, manage policy/constitutions and capability grants, and view audit data |
| `developer` | Submit evidence from the CLI and view validation, traceability, and quality in projects where membership exists |
| `reviewer` | View project scope and make APPROVED/REJECTED decisions for review and phase gates |

A realm role is insufficient to access project data: the management server always also verifies **project membership**. This protects against a developer or reviewer with organizational access reading an out-of-scope project.

### Developer workflow

Create a `spec-kit` containing at least `constitution.md`, `spec.md`, and `tasks.md`. Initialize `.aisdlc.yml` once, commit governance configuration without secrets, then run validation with a clearly pinned model revision. Validation never calls the model—the model pin is stored only as required provenance.

The management API is **not published to the host**. That is deliberate — the portal is the application entry point and the identity gateway is the OIDC entry point, as stated above — and `end-to-end-acceptance.sh` asserts it, failing if `http://localhost:8081` ever answers. So the CLI has to reach the API on the private network. Run it inside that network:

```bash
docker run --rm --network ai-sdlc_platform -v "$PWD:/w" -w /w/cli golang:1.24 \
  go run ./cmd/aisdlc init --project <project-uuid> --api-url http://management-server:8081 ...
```

If you would rather run the CLI from the host, open a forward yourself, bound to loopback only, and close it when you are done. It is a local convenience, never a deployment pattern:

```bash
docker run --rm -d --name aisdlc-api-forward --network ai-sdlc_platform \
  -p 127.0.0.1:8081:8081 alpine/socat \
  tcp-listen:8081,fork,reuseaddr tcp-connect:management-server:8081
```

The commands below assume one of those two is in place; substitute the address you chose for `--api-url`.

```bash
cd cli
go run ./cmd/aisdlc init \
  --project <project-uuid> \
  --api-url http://management-server:8081 \
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

### REST resource map

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

### Audit integrity

Every event receives `sequence`, `previous_hash`, and `event_hash`. A database migration installs a trigger that prohibits both `UPDATE` and `DELETE` on `audit_events`; the application also offers no endpoint to edit or remove audit data. Every supported validation, policy, review, exception, or agent-launch action must go through `AuditService`.

### Pre-merge checks

```bash
mvn test
mvn -DskipTests package
cd cli && go test ./... && go build ./cmd/aisdlc
bash ../scripts/verify-production.sh
```

The build passes Java unit tests, Go tests, and Maven packaging. Full integration regression requires a Docker daemon to start PostgreSQL, Keycloak, the identity gateway, and MinIO. When a development environment has no Docker daemon, that verification remains explicitly pending and must not be treated as complete; GitHub Actions runs the Compose smoke test on a Docker-capable runner. See [`integrations-and-sdks.md#ai-sdlc-cli`](integrations-and-sdks.md#ai-sdlc-cli), [`architecture.md#control-plane-api`](architecture.md#control-plane-api), [`architecture.md#portal-workflows`](architecture.md#portal-workflows), [`operations.md#continuous-integration-and-release-delivery`](operations.md#continuous-integration-and-release-delivery), and [`operations.md#production-operations-runbook`](operations.md#production-operations-runbook) for detailed production contracts.

---

## Production Operations Runbook

This runbook covers a Docker-capable production environment for the AI-SDLC monorepo. It supplements the local bootstrap guide in [`operations.md`](operations.md). The platform contains a stateful PostgreSQL database and identity provider, so deployment is a controlled operational change rather than a blind container restart.

### Deployment Posture

| Component | Exposure and runtime rule |
|---|---|
| Portal | The only application UI exposed to end users; authenticate through the identity gateway and serve only HTTPS at the infrastructure edge. |
| Identity gateway | The public Keycloak reverse proxy. It has bounded request rate, conservative proxy timeouts and anti-framing/content-sniffing headers. |
| Keycloak | Private network only. Use a production hostname and TLS termination outside the `start-dev` local configuration before an internet-facing release. |
| Management server | Private network only. It validates JWTs, project scope and policy transitions; never expose its internal port directly to untrusted clients. |
| PostgreSQL | Private network and persistent encrypted storage only. Never expose port `5432` to the internet. |
| MinIO / S3-compatible store | Private network and persistent encrypted storage only. The bucket is created with Object Lock before management-server starts; expose neither S3 API nor console to untrusted networks. |

The portal and management server images now run as UID/GID `10001`, with read-only root filesystems and an ephemeral writable `/tmp`. They may not depend on local runtime persistence. The JVM caps its heap as a percentage of the container limit, leaving memory for native/JVM overhead and preventing a single instance from consuming the host allocation.

### Pre-deployment Gate

Run the following against the exact commit proposed for release. A live topology test requires a Docker-capable runner; the static production test remains useful in restricted development environments.

```bash
mvn --batch-mode --no-transfer-progress verify
(cd cli && go test ./... && go build ./cmd/aisdlc)
bash scripts/verify-production.sh
docker compose --env-file .env config
KEYCLOAK_PUBLIC_HOST=auth.example.com \
  docker compose --env-file .env -f docker-compose.yml -f docker-compose.production.yml config
KEYCLOAK_PUBLIC_HOST=auth.example.com \
  docker compose --env-file .env -f docker-compose.yml -f docker-compose.production.yml up --build --wait
```

After startup, check the management API readiness group, the portal OIDC redirect, a project-scoped read as each role, a rejected out-of-scope read, CLI evidence sync with the same idempotency key twice, and audit-chain verification. `docker-compose.production.yml` changes Keycloak from local `start-dev` mode to optimized production startup and requires an HTTPS hostname at the external TLS edge. Do not consider HTTP localhost routes production ready.

### Secret Management and Rotation

Secrets must enter containers only through the runtime secret manager or environment injection. `.env` is for local development only and must never be committed. At minimum, rotate `POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `PORTAL_CLIENT_SECRET`, `CLI_CLIENT_SECRET`, `AISDLC_EVIDENCE_S3_ACCESS_KEY`, `AISDLC_EVIDENCE_S3_SECRET_KEY`, and the JWT/identity signing material managed by Keycloak. The security pipeline does not require an NVD API key.

| Rotation sequence | Safe procedure |
|---|---|
| Portal / CLI OAuth clients | Create a replacement credential in Keycloak, deploy consumers with the replacement, validate an authentication/sync flow, then revoke the old credential. |
| Database password | Create a new database credential or rotate the role password during a maintenance window, update the secret injection source, restart dependent services one at a time, then validate readiness and a transactional write. |
| Keycloak bootstrap administrator | Replace the bootstrap secret in the secret manager, use a separately managed administrator account for normal operations, and test a privileged realm operation before revoking old access. |
| CI security data and remediation controls | No NVD API key is required. Review OSV, Trivy, and CodeQL alerts in GitHub Security; remediate vulnerable dependencies or code, then rerun CI. A Trivy suppression requires a reviewed, time-bounded `.trivyignore.yaml` entry with advisory ID, rationale, owner, and expiry. |
| Object-storage access key | Create a replacement least-privilege service credential, update the runtime secret source and bootstrap configuration, restart management-server, perform an authorized upload/download test, then revoke the old credential. Never rotate a key by editing a committed `.env` file. |
| Runtime AI provider credential and mTLS identity | Write the replacement into the read-only secret mount referenced by `AISDLC_RUNTIME_AI_CREDENTIAL_MOUNT_PATH` with owner-only permissions, restart management-server, dispatch one authorized invocation against the isolated provider, then revoke the previous provider credential. The control plane stores only the opaque `mount:<name>` reference, so no database change is involved. See [`runtime-ai-governance.md#runtime-ai-workload-identity-and-provider-proxy-rollout`](runtime-ai-governance.md#runtime-ai-workload-identity-and-provider-proxy-rollout). |
| Agent runtime workload client | Rotate the `aisdlc-agent-runtime` service-account secret in Keycloak, redeploy the workload, confirm one authorized invocation, then revoke the old secret. A workload that must be retired is deactivated through the broker workload registry so its subject stops authorizing. |

Never record secret values, bearer tokens, JDBC URLs with credentials, or OAuth client secrets in tickets, audit messages, CLI configuration, or application logs.

### PostgreSQL Backup and Restore

Take encrypted, access-controlled backups on a schedule appropriate to the organization’s recovery objectives. Before a release containing a Flyway migration, take a verified logical backup and test restoration on an isolated PostgreSQL instance.

```bash
# Backup from an authorized administrative environment.
pg_dump --format=custom --no-owner --file="aisdlc-$(date -u +%Y%m%dT%H%M%SZ).dump" "$DATABASE_URL"

# Restore only into an isolated target after an explicit recovery decision.
createdb aisdlc_restore
pg_restore --clean --if-exists --no-owner --dbname=aisdlc_restore aisdlc-YYYYMMDDTHHMMSSZ.dump
```

The audit table is intentionally append-only. Never repair audit evidence with `UPDATE`, `DELETE`, or direct SQL. If hash verification fails, stop governance mutations, preserve database and application logs, establish the affected sequence interval, and open an incident before any recovery action.

### Evidence Object Storage Backup and Restore

Back up PostgreSQL metadata and the evidence bucket as a coordinated recovery set. A database restore without the corresponding bucket loses retrievability; a bucket restore without the corresponding metadata produces private, unreferenced objects. Store a signed manifest containing the UTC time, database backup identifier, bucket backup identifier and a SHA-256 for each exported artefact.

For MinIO, use an administrative environment with a separate backup identity. First pause evidence mutations or use a provider-supported point-in-time/versioning mechanism, then mirror the complete versioned bucket to encrypted, access-controlled backup storage. Test the mirror and record its manifest before declaring the backup successful. Object Lock protects retained versions; it is not a substitute for disaster recovery backups.[4]

Restoration requires an approved incident/recovery decision. Restore the database to an isolated target, restore the bucket and object versions to a non-public target bucket, run audit-chain verification, sample SHA-256 evidence metadata against retrieved objects, and validate authorization before switching application traffic. Do not use a normal application credential to weaken, shorten or bypass a compliance retention mode.[5]

### Migration, Rollout and Rollback

Flyway migrations are forward-only. Test them on a database restored from production-like data before rollout. First deploy the management server and wait for readiness; then deploy the portal. Keep a versioned application artifact available for rollback.

Application rollback is permissible only when the earlier binary remains compatible with the current database schema. A destructive database rollback requires an approved recovery plan and a tested backup restore; it is not a routine deployment operation. The write-once audit trigger must remain active throughout all migrations.

### Monitoring and Incident Response

Monitor the public HTTPS endpoint, portal sign-in success, `/actuator/health/liveness`, `/actuator/health/readiness`, database connection readiness, 429 rate-limit volume, 5xx rate, audit-chain verification, error logs by correlation ID, and validation-sync conflict counts. Retain logs under the organization’s privacy and incident-response policy.

Keycloak readiness is distinct from process startup. With `KC_HEALTH_ENABLED=true`, Keycloak exposes `/health/ready` on its private management port `9000` by default. The container image intentionally omits HTTP client binaries, so its healthcheck uses the documented Bash TCP socket probe rather than adding `curl` to the runtime image. The public identity gateway should receive traffic only after that private readiness probe succeeds.[3]

When investigating an incident, record the correlation ID, request timestamp, principal subject, project/organization scope, deployment version and audit event sequence. This establishes an evidence trail without putting access tokens or sensitive artifact content into support records.

### References

[1] [OSV-Scanner GitHub Action](https://google.github.io/osv-scanner/github-action/)

[2] [Trivy GitHub Actions integration](https://trivy.dev/docs/latest/tutorials/integrations/github-actions/)

[3] [Keycloak — Tracking instance status with health checks](https://www.keycloak.org/observability/health)

[4] [MinIO AIStor — `mc mirror` replication and synchronization](https://docs.min.io/community/minio-object-store/reference/minio-mc/mc-mirror.html)

[5] [Amazon S3 Object Lock — retention modes and legal holds](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)

---

## Production Hardening Baseline

**Status:** Baseline fixed on `main` at commit `9c13fd2`.

This document records the implementation audit performed before the production-hardening workstream. It is intentionally concrete: each gap is tied to an existing platform boundary, and every later implementation must preserve the three platform invariants.

> **Platform invariants:** the validator never invokes AI; every validation run is model-pinned and rejects `--bare`; and a human makes every approval or rejection decision.

### Audit Summary

| Capability area | Baseline present | Production gap to close |
|---|---|---|
| Organization and project administration | Organization/project creation and project-owner bootstrap exist | No organization discovery, pageable project lists, membership invite/change/remove operations, or administration UI |
| Spec Kit lifecycle | Register, list and pin are available | No project-kit read model, compatibility assessment, deprecation lifecycle, duplicate pin protection, or portal write workflow |
| Governance-as-data | Policies, constitutions, grants and exception submission exist | No explicit policy/constitution lifecycle, version history, exception decision workflow, expiry checks, or governance administration UI |
| Validation and evidence | Idempotent ingest, findings and evidence persistence exist | No pageable filtered queries, run/finding/evidence detail API, retention metadata, triage lifecycle, or portal drill-down |
| Traceability and reviews | Nodes, edges, review request and final review decision exist | No detail-oriented data contract, edge ownership validation, review mutation guards, phase-gate controls, or richer queue operations |
| Quality and audit | Metric snapshots and immutable append-only audit records exist | No formal quality data contract validation, pagination/filtering, audit-chain verification endpoint, or audit UI filters |
| API protection | OAuth2 JWT, service-level membership checks and input validation exist | No OpenAPI publication, consistent RFC 9457 error model, API rate limiting, CORS allowlist, security headers, or viewer role mapping |
| Observability | Health/info actuator configuration exists | No readiness/liveness grouping, structured JSON logs, correlation identifiers, operational metrics exposure, or alert-ready runbook |
| Portal SSR and React Islands | Read-first authenticated portal, review decision form and islands exist | Missing write workflows, feedback/error handling, server-side pagination and mobile-optimized administration workflows |
| CLI and delivery | Deterministic validation/sync baseline and unit tests exist | Missing configuration initialization, credential store, retry/backoff, status, CI output adapters and release/CI automation |

### Hardening Design Decisions

The management server remains the sole authorization and integrity boundary. Portal forms will submit through server-side OAuth2 clients and will never expose Keycloak access tokens to browser JavaScript. Browser-side enhancements are optional; the server-rendered workflow and CSRF protection remain functional without JavaScript.

The control plane will use stable, page-oriented API envelopes, bounded query parameters and field-level validation responses. Stateful governance transitions will be conditional updates that fail if a record is no longer in the expected state, protecting against duplicate approvals and concurrent edits. Every successful state mutation will append an audit event in the same transaction.

New schema support will be delivered only by forward Flyway migrations. Existing audit events remain immutable at the PostgreSQL layer; audit verification will recompute the stored hash chain without changing historical records.

### Definition of Done

The production-hardening release is complete only when all API mutations enforce role and scope checks, all list endpoints are paged or safely bounded, the SSR portal covers the administrative workflow, CLI output supports local and CI usage, audit integrity can be verified, and Java/Go/frontend builds plus relevant tests pass in clean environments. Documentation and GitHub automation are release deliverables, not post-release work.

---

## Enterprise Deployment: Helm and GitOps

**Status:** Chart and GitOps references published. Not yet installed against a real cluster by this repository's CI.
**Scope:** `todo.md:128` — Helm chart, GitOps reference configuration, hardened defaults, and upgrade/rollback guidance.

### What the chart does and does not own

The chart deploys the two stateless workloads: `management-server` and `portal`.

PostgreSQL, Keycloak, and object storage are **deliberately not subcharts**. They are stateful, security-critical dependencies whose lifecycle must not be coupled to an application upgrade — a `helm rollback` must never be able to roll back a database. Point the chart at existing instances through `config` and `existingSecrets`.

The chart also creates **no Secret of its own**, and the contract test asserts that. Credentials come from Secrets the platform team manages out of band, referenced by name. A chart that templates a secret value puts that value in release history and in every `helm get values` output.

### Hardened defaults

Everything below is the default. `scripts/test-helm-hardening.sh` fails the build if any of it is weakened.

| Control | Default |
|---|---|
| User | Non-root, UID/GID 10001, `fsGroup` 10001 |
| Root filesystem | Read-only, with a memory-backed `/tmp` bounded at 256Mi |
| Capabilities | All dropped, no privilege escalation, `seccompProfile: RuntimeDefault` |
| Service account | Created, token **not** mounted — the control plane calls no Kubernetes API |
| Network | Default-deny ingress and egress; egress opened only to DNS, PostgreSQL, Keycloak, object storage, and OTLP when telemetry is on |
| Management API | `ClusterIP` only, reachable solely from the portal pods. It has no Ingress by design |
| Ingress | Disabled; when enabled, TLS cannot be turned off |
| Images | Digests only. A mutable tag fails the render |
| Telemetry | Disabled, matching the application default. Enabling it without an exporter endpoint fails the render |
| Runtime AI proxy and tool broker | Disabled, matching the application defaults |
| Availability | 2 replicas each, PodDisruptionBudget, resource requests and limits |

Two guards are worth calling out because they refuse to install rather than warn:

- **A mutable image tag is rejected.** `latest` makes a rollback unreproducible and lets a rebuild silently change what runs.
- **Telemetry without an exporter endpoint is rejected**, matching the container entrypoint. A deployment must not believe it is observed when it is not.

### Installing

```sh
helm upgrade --install ai-sdlc infra/helm/ai-sdlc \
  --namespace ai-sdlc \
  --values environments/production/values.yaml \
  --atomic --timeout 10m
```

`--atomic` matters: without it a failed upgrade leaves a partially applied control plane, which is worse than the previous release still running.

Required values with no default: both image digests, `config.database.host`, both Keycloak URIs, and the `existingSecrets` references. The chart fails to render when any is missing, rather than installing something broken that reports success.

### GitOps

Two equivalent references in `infra/gitops/`; use one, not both against the same namespace.

| File | Notes |
|---|---|
| `argocd-application.yaml` | Pinned to a tag, server-side apply, `prune` and `selfHeal` together so an in-cluster edit is reverted rather than retained. `Secret.data` is in `ignoreDifferences` so a sync never fights the platform team's credential rotation. |
| `flux-helmrelease.yaml` | Pinned to a tag, with `upgrade.remediation.strategy: rollback` so a failed upgrade reverts on its own. |

Both point at a **tag or commit, never a branch**. A branch lets an unreviewed push reach production.

### Upgrading

1. Read the changelog entry and check for a Flyway migration in the release. A migration is forward-only; see the rollback limits below.
2. Update the image digests and the chart revision in the config repository. Do not edit the release in the cluster.
3. Apply with `--atomic`, or let the GitOps controller sync.
4. Watch readiness, not just rollout status. Readiness includes the database and the audit ledger, so a pod that cannot record governance evidence is kept out of the Service.
5. Confirm the audit chain verifies after the upgrade before treating it as complete.

### Rolling back

```sh
helm rollback ai-sdlc <revision> --wait --timeout 10m
```

**A rollback returns the application, not the database.** Flyway migrations are forward-only, and the audit ledger is append-only at the database level. Before rolling back across a release that carried a migration:

- Confirm the previous application version tolerates the current schema. Additive migrations usually allow this; a migration that drops or narrows a column does not.
- If it does not, restore from a verified backup and accept the data loss window, following the PostgreSQL restore procedure in [`operations.md#production-operations-runbook`](operations.md#production-operations-runbook). Do not attempt to reverse a migration by hand against a live audit ledger.
- Never delete or edit audit rows to make an older version start. That is a governance incident in itself.

Rolling back the portal alone is always safe; it holds no state.

### Verification

```sh
helm lint infra/helm/ai-sdlc
sh scripts/test-helm-hardening.sh
```

The hardening test asserts the values-level defaults with no tooling, then renders the chart with Helm to check the manifest-level properties and to prove both refusals actually refuse. CI runs it on every pull request.

### Verified on a real cluster

The chart has been installed on a live Kubernetes cluster (kind, v1.36.1) against a local registry so image digests are genuine manifest digests rather than local tags.

Confirmed by the API server and by the running pods, not by the templates:

| Check | Result |
|---|---|
| Both workloads admitted and Ready | `management-server` 1/1, `portal` 1/1 |
| Images resolved **by digest** | `…/management-server@sha256:aa708c…`, `…/portal@sha256:e920d8…` |
| `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities: [ALL]` dropped, `seccompProfile: RuntimeDefault` | all applied |
| `automountServiceAccountToken` | `false` |
| Resource limits | applied |
| Services | all `ClusterIP`; no Ingress, no NodePort |
| Flyway against in-cluster PostgreSQL | 18 migrations applied, latest `V18` |
| Readiness including `db` and `auditLedger` | `{"status":"UP"}` |

#### Two chart defects the install found

Neither was visible to `helm lint` or `helm template`.

1. **`volumes` was nested inside `containers`** in the portal deployment. Server-side apply rejected it: `field not declared in schema`. Rendering produced valid YAML, just not a valid Deployment.
2. **The portal referenced the management-server's digest.** The chart rendered and linted cleanly and would have deployed the management server under the portal's name. Only an actual image pull exposed it. `scripts/test-helm-hardening.sh` now asserts each workload references its own digest.

Still not exercised: an ingress controller, a real CNI enforcing the NetworkPolicies (kind's default CNI does not), and Keycloak or object storage in-cluster.

---

## Continuous Integration and Release Delivery

The repository contains two independent GitHub Actions workflows. They execute on GitHub-hosted runners, while production deployment remains an operator-controlled promotion step after the quality gates pass.

| Workflow | Trigger | Required verification | Output |
|---|---|---|---|
| `CI` | Push to `main`, pull request to `main`, or manual dispatch | Maven verification on Java 25, Go 1.24 test/build and format, Vite production build, dependency review, OSV dependency scan, and Trivy source/production-image scan | Maven, Trivy, and SARIF reports when applicable |
| `CodeQL` | Push/PR to `main`, manual dispatch, and weekly schedule | Security-extended and security-and-quality analysis for Java, JavaScript/TypeScript, Go, and GitHub Actions | Code-scanning alerts and SARIF evidence |
| `Release` | Signed release-tag push (`v*`) or manual dispatch of an existing tag | OSV and Trivy release-security gates, Maven verification, and static Go cross-compilation | Management server JAR, portal JAR, Linux/Darwin CLI binaries, security reports, and `SHA256SUMS` |

The security pipeline does not require an NVD API key. OSV-Scanner provides dependency-vulnerability scanning and compares newly introduced vulnerabilities on pull requests while running a full scan on `main` and release workflows. Trivy scans dependencies, secrets, Dockerfiles, Compose/IaC configuration, and the two production images; HIGH and CRITICAL findings fail the gate. CodeQL analyzes Java, JavaScript/TypeScript, Go, and GitHub Actions source. Dependabot provides recurring update pull requests for Maven, npm, Go modules, and GitHub Actions. See [`security.md#security-scanning`](security.md#security-scanning) for remediation, suppression, evidence, and source-policy details.[1] [2] [3]

Release artifacts contain SHA-256 checksums. Before an artifact is introduced to any deployment registry, operators must verify its checksum against the `SHA256SUMS` file published with the GitHub release.

```bash
sha256sum --check SHA256SUMS
```

The Trivy security gate intentionally fails on HIGH and CRITICAL actionable findings. OSV blocks newly introduced dependency vulnerabilities in pull requests and known vulnerabilities on protected release paths. A lower-severity finding remains visible in uploaded SARIF evidence and requires a documented risk decision where it affects the release posture.

### References

[1] [OSV-Scanner GitHub Action](https://google.github.io/osv-scanner/github-action/)

[2] [Trivy GitHub Actions integration](https://trivy.dev/docs/latest/tutorials/integrations/github-actions/)

[3] [GitHub CodeQL code scanning](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql)
