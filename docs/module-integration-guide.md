# AI-SDLC module integration guide

## Objective and stable boundaries

AI-SDLC is delivered as a Maven reactor and control-plane service, while its capabilities are separated into bounded modules. The supported integration paths for external systems are the versioned **`/api/v1` API**, protected OpenAPI, OAuth2/JWT, and the Go CLI. Do not integrate by directly accessing the PostgreSQL schema, JPA entities, or internal repositories because those components are not compatibility contracts.

| Need | Supported integration contract | Do not depend on |
|---|---|---|
| Submit deterministic validation | `POST /api/v1/cli/projects/{projectId}/validation-runs` or `aisdlc sync` | `validation_runs` table or `ValidationRun` entity |
| Store an artifact/evidence | `POST /api/v1/projects/{projectId}/evidence-assets` or `aisdlc upload` | Direct bucket/key access or S3/MinIO credentials |
| Retrieve evidence | `GET /api/v1/projects/{projectId}/evidence-assets` and a detail response with a presigned URL | Long-lived object-storage URLs or bucket listings |
| Governance/review | Corresponding REST resources and the audit-verification endpoint | Creating `ReviewDecision` or `AuditEvent` through SQL |
| Embed in the JVM source tree | `ObjectStoragePort` is replaceable; service/repository packages remain internal implementations | AWS SDK, MinIO SDK, or another module's JPA repositories |

> **Integration rule:** Every review or exception decision must still be submitted through the control plane by a human principal with the appropriate role and project membership. An integrator must not replace that decision with an agent or automated job.

## Recommended HTTP integration

An integration creates an OAuth2 client with the minimum required scope or realm role, receives a JWT from Keycloak, and sends it in `Authorization: Bearer`. The project ID must be explicitly selected; a realm role is insufficient because the server always also verifies project membership. The API returns RFC 9457 `application/problem+json` errors; clients retry only transport failures, `429`, and `5xx` responses with bounded backoff. Interactive OpenAPI is available at `/swagger-ui.html`; the raw document is available at `/v3/api-docs` for administrators.

For the Evidence Repository, clients send multipart data containing `file`, `assetType`, `accessLevel`, and an optional `validationEvidenceId`. `X-Content-SHA256` lets the server verify the received bytes. `Idempotency-Key` must remain stable across retries; if omitted, the server derives one from provenance metadata and the digest. Downloads always pass through API authorization and return a short-lived presigned URL, never a public object-storage endpoint.[1]

## Storage extension point

`ObjectStoragePort` is the anti-corruption layer for the evidence module. The default `S3ObjectStorageAdapter` uses AWS SDK for Java 2.x with endpoint override and path-style addressing for MinIO. A deployment that uses another S3-compatible provider replaces only the adapter and configuration; it does not replace the controller, audit behavior, authorization, or persistence metadata. AWS recommends importing the SDK BOM together with the service modules and HTTP client actually used to preserve version alignment.[2]

A replacement adapter must provide four behaviors: write private objects with SHA-256 and project metadata; generate time-limited presigned GET URLs; apply Object Lock retention; and compensate by deleting only when metadata persistence rolls back. It must not decide RBAC, alter the SHA-256 value, or issue public URLs.

| Property | Purpose | Local Compose example |
|---|---|---|
| `AISDLC_EVIDENCE_S3_ENDPOINT` | Private S3-compatible endpoint | `http://minio:9000` |
| `AISDLC_EVIDENCE_S3_REGION` | Signing region | `us-east-1` |
| `AISDLC_EVIDENCE_S3_BUCKET` | Bootstrapped Object Lock bucket | `aisdlc-evidence` |
| `AISDLC_EVIDENCE_S3_ACCESS_KEY` / `...SECRET_KEY` | Control-plane runtime credentials | From a secret manager, never from CLI or browser |
| `AISDLC_EVIDENCE_S3_FORCE_PATH_STYLE` | MinIO/local endpoint compatibility | `true` |

## Versioning, testing, and upgrades

Consumers must pin a release image or binary version, inspect the OpenAPI diff before a minor upgrade, and run a contract smoke test: create/upload twice with the same idempotency key, list by project, verify download authorization for all three access levels, extend retention, and verify the audit chain. Do not assume that non-public Java classes or Flyway schema migrations are compatible APIs.

An independent Java client Maven artifact (`sdk/`) has not yet been published. Until that artifact has its own semantic versioning and compatibility policy, HTTP/OpenAPI and the CLI are the officially supported integration boundaries.

## References

[1] [AWS SDK for Java 2.x — S3 presigning](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)

[2] [AWS SDK for Java 2.x — Maven setup and BOM alignment](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html)
