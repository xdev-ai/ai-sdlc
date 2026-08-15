# Production Operations Runbook

This runbook covers a Docker-capable production environment for the AI-SDLC monorepo. It supplements the local bootstrap guide in [`operations.md`](operations.md). The platform contains a stateful PostgreSQL database and identity provider, so deployment is a controlled operational change rather than a blind container restart.

## Deployment Posture

| Component | Exposure and runtime rule |
|---|---|
| Portal | The only application UI exposed to end users; authenticate through the identity gateway and serve only HTTPS at the infrastructure edge. |
| Identity gateway | The public Keycloak reverse proxy. It has bounded request rate, conservative proxy timeouts and anti-framing/content-sniffing headers. |
| Keycloak | Private network only. Use a production hostname and TLS termination outside the `start-dev` local configuration before an internet-facing release. |
| Management server | Private network only. It validates JWTs, project scope and policy transitions; never expose its internal port directly to untrusted clients. |
| PostgreSQL | Private network and persistent encrypted storage only. Never expose port `5432` to the internet. |
| MinIO / S3-compatible store | Private network and persistent encrypted storage only. The bucket is created with Object Lock before management-server starts; expose neither S3 API nor console to untrusted networks. |

The portal and management server images now run as UID/GID `10001`, with read-only root filesystems and an ephemeral writable `/tmp`. They may not depend on local runtime persistence. The JVM caps its heap as a percentage of the container limit, leaving memory for native/JVM overhead and preventing a single instance from consuming the host allocation.

## Pre-deployment Gate

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

## Secret Management and Rotation

Secrets must enter containers only through the runtime secret manager or environment injection. `.env` is for local development only and must never be committed. At minimum, rotate `POSTGRES_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `PORTAL_CLIENT_SECRET`, `CLI_CLIENT_SECRET`, `AISDLC_EVIDENCE_S3_ACCESS_KEY`, `AISDLC_EVIDENCE_S3_SECRET_KEY`, the JWT/identity signing material managed by Keycloak, and any NVD API key used by CI.

| Rotation sequence | Safe procedure |
|---|---|
| Portal / CLI OAuth clients | Create a replacement credential in Keycloak, deploy consumers with the replacement, validate an authentication/sync flow, then revoke the old credential. |
| Database password | Create a new database credential or rotate the role password during a maintenance window, update the secret injection source, restart dependent services one at a time, then validate readiness and a transactional write. |
| Keycloak bootstrap administrator | Replace the bootstrap secret in the secret manager, use a separately managed administrator account for normal operations, and test a privileged realm operation before revoking old access. |
| CI scan key | Replace `NVD_API_KEY` in GitHub repository secrets, dispatch the CI workflow, verify scan data download succeeds, then delete the old key. |
| Object-storage access key | Create a replacement least-privilege service credential, update the runtime secret source and bootstrap configuration, restart management-server, perform an authorized upload/download test, then revoke the old credential. Never rotate a key by editing a committed `.env` file. |

Never record secret values, bearer tokens, JDBC URLs with credentials, or OAuth client secrets in tickets, audit messages, CLI configuration, or application logs.

## PostgreSQL Backup and Restore

Take encrypted, access-controlled backups on a schedule appropriate to the organization’s recovery objectives. Before a release containing a Flyway migration, take a verified logical backup and test restoration on an isolated PostgreSQL instance.

```bash
# Backup from an authorized administrative environment.
pg_dump --format=custom --no-owner --file="aisdlc-$(date -u +%Y%m%dT%H%M%SZ).dump" "$DATABASE_URL"

# Restore only into an isolated target after an explicit recovery decision.
createdb aisdlc_restore
pg_restore --clean --if-exists --no-owner --dbname=aisdlc_restore aisdlc-YYYYMMDDTHHMMSSZ.dump
```

The audit table is intentionally append-only. Never repair audit evidence with `UPDATE`, `DELETE`, or direct SQL. If hash verification fails, stop governance mutations, preserve database and application logs, establish the affected sequence interval, and open an incident before any recovery action.

## Evidence Object Storage Backup and Restore

Back up PostgreSQL metadata and the evidence bucket as a coordinated recovery set. A database restore without the corresponding bucket loses retrievability; a bucket restore without the corresponding metadata produces private, unreferenced objects. Store a signed manifest containing the UTC time, database backup identifier, bucket backup identifier and a SHA-256 for each exported artefact.

For MinIO, use an administrative environment with a separate backup identity. First pause evidence mutations or use a provider-supported point-in-time/versioning mechanism, then mirror the complete versioned bucket to encrypted, access-controlled backup storage. Test the mirror and record its manifest before declaring the backup successful. Object Lock protects retained versions; it is not a substitute for disaster recovery backups.[4]

Restoration requires an approved incident/recovery decision. Restore the database to an isolated target, restore the bucket and object versions to a non-public target bucket, run audit-chain verification, sample SHA-256 evidence metadata against retrieved objects, and validate authorization before switching application traffic. Do not use a normal application credential to weaken, shorten or bypass a compliance retention mode.[5]

## Migration, Rollout and Rollback

Flyway migrations are forward-only. Test them on a database restored from production-like data before rollout. First deploy the management server and wait for readiness; then deploy the portal. Keep a versioned application artifact available for rollback.

Application rollback is permissible only when the earlier binary remains compatible with the current database schema. A destructive database rollback requires an approved recovery plan and a tested backup restore; it is not a routine deployment operation. The write-once audit trigger must remain active throughout all migrations.

## Monitoring and Incident Response

Monitor the public HTTPS endpoint, portal sign-in success, `/actuator/health/liveness`, `/actuator/health/readiness`, database connection readiness, 429 rate-limit volume, 5xx rate, audit-chain verification, error logs by correlation ID, and validation-sync conflict counts. Retain logs under the organization’s privacy and incident-response policy.

Keycloak readiness is distinct from process startup. With `KC_HEALTH_ENABLED=true`, Keycloak exposes `/health/ready` on its private management port `9000` by default. The container image intentionally omits HTTP client binaries, so its healthcheck uses the documented Bash TCP socket probe rather than adding `curl` to the runtime image. The public identity gateway should receive traffic only after that private readiness probe succeeds.[3]

When investigating an incident, record the correlation ID, request timestamp, principal subject, project/organization scope, deployment version and audit event sequence. This establishes an evidence trail without putting access tokens or sensitive artifact content into support records.

## References

[1] [OWASP Dependency-Check — official project documentation](https://owasp.org/www-project-dependency-check/)

[2] [OWASP Dependency-Check GitHub Actions cache guidance](https://dependency-check.github.io/DependencyCheck/data/cache-action.html)

[3] [Keycloak — Tracking instance status with health checks](https://www.keycloak.org/observability/health)

[4] [MinIO AIStor — `mc mirror` replication and synchronization](https://docs.min.io/community/minio-object-store/reference/minio-mc/mc-mirror.html)

[5] [Amazon S3 Object Lock — retention modes and legal holds](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)
