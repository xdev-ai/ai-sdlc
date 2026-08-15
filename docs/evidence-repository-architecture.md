# Evidence & Governance Data Repository Architecture

**Status:** Implemented in the current pre-release. This document defines the storage extension that makes AI-SDLC packages independently integrable while preserving the platform’s audit, authorization and human-decision invariants.

> **Design principle:** PostgreSQL is the source of truth for governance metadata and authorization; S3-compatible object storage is the source of truth for large immutable bytes. A storage object is never made public merely because it exists.

## Module Boundary Contract

The monorepo remains deployable as one control-plane service, but each bounded module exposes a small Java contract package and owns its persistence adapter. A module may depend on `platform-contracts` and published APIs of another module; it must not reach into another module’s JPA repository or entity internals.

| Module | Stable public contract | Owns | May depend on |
|---|---|---|---|
| `identity-access` | `ProjectAuthorization`, `PrincipalContext` | Role and project-scope decisions | platform contracts |
| `audit-ledger` | `AuditAppender`, `AuditVerifier` | Append-only audit records and verification | platform contracts |
| `governance-catalog` | `PolicyCatalog`, `SpecKitRegistry` | Kits, policies, constitutions, grants and exceptions | access, audit |
| `validation` | `ValidationIngestor`, `ValidationQuery` | Runs, findings, deterministic evidence links | access, audit, repository |
| `review-workflow` | `ReviewQueue`, `HumanDecision` | Review requests and final human decisions | access, audit, repository |
| `quality-insights` | `QualityMetricStore` | Metric snapshots and read models | access |
| `evidence-repository` | `EvidenceRepository`, `EvidenceObjectStore` | Asset metadata, upload/finalize/download/hold lifecycle | access, audit |

Each module follows the same package shape: `api` for stable records/interfaces, `application` for transactions and use cases, `domain` for policy/state, and `infrastructure` for JPA, S3 and web adapters. This lets a future deployment split a module behind an internal API without rewriting consumers.

## Implemented Scope

The first production-capable slice is deliberately direct: an authenticated client sends one bounded multipart asset to the control plane; the server independently computes SHA-256, stores the bytes through the S3-compatible port, writes project-scoped metadata, and appends an immutable audit event in its database transaction. The metadata migration is `V4__evidence_repository.sql`. If the metadata transaction rolls back after the object upload, the service attempts compensating object deletion; it never exposes a public bucket URL.

| Implemented concern | Behaviour |
|---|---|
| Storage boundary | `ObjectStoragePort` isolates application code from AWS SDK, MinIO and provider-specific SDK classes. `S3ObjectStorageAdapter` is the default adapter. |
| Metadata | `evidence_assets` stores project, optional linked validation evidence, typed classification, filename/content type, byte size, bucket/key, SHA-256, idempotency key, actor, access level, retention and soft-delete timestamp. |
| Idempotency | `project_id + idempotency_key` is unique. A supplied key must be 8–120 URL-safe characters; an omitted key is deterministically derived from project, type, classification, linked validation evidence and digest. |
| Access | Upload is available to owner/developer/reviewer. List is project-member scoped. Download honours `PROJECT`, `REVIEWERS` and `OWNERS` classification. Retention and soft delete require owner or reviewer. |
| Immutability | Retention can only extend. A `COMPLIANCE` lock cannot be downgraded. The S3 adapter maps the chosen mode and timestamp to provider object-lock retention. |

## Repository Data Model

| Table / concept | Key fields | Integrity rule |
|---|---|---|
| `evidence_assets` | project, optional `validation_evidence_id`, type, filename, MIME type, byte length, bucket/key, SHA-256, idempotency key, access level, retention, soft-delete timestamp | One asset belongs to exactly one project. `(project_id, idempotency_key)` and `(s3_bucket, s3_key)` are unique. |
| S3-compatible object | private opaque key `projects/{project-id}/evidence-assets/{uuid}/{sanitized-name}` with SHA-256/project/actor metadata | Object bytes do not reside in PostgreSQL and object names are never client supplied paths. |
| Append-only audit ledger | actor, project, event type, asset ID and digest/retention metadata | Upload, retention lock and soft deletion always append a governance event in the metadata transaction. |

The database stores no file bytes. A user filename is display metadata only; it is sanitized before use as the tail of an opaque server-generated object key.

## Upload, Verification and Download

An authorized project member uploads one bounded multipart file to the control plane. The server computes SHA-256 from the received bytes and rejects any mismatch with the optional `X-Content-SHA256`. It then persists the private object using server-held credentials, saves provenance metadata and appends an audit event. The current implementation uses a bounded in-memory multipart representation; the configured upload limit must remain appropriate for the management-server memory allocation.

An authorized reader retrieves asset detail. The control plane verifies project membership and classification policy first, then issues a short-lived presigned `GET`; the object store remains private. Presigned URL support is provided by the S3-compatible Java client API.[1]

## Retention, Immutability and Legal Hold

Production buckets use S3 versioning and Object Lock. Object Lock operates per version and requires versioning; a retention period or legal hold protects an individual version, while a simple delete can create a delete marker rather than erase a locked prior version.[2] The implemented retention endpoint applies either `GOVERNANCE` or `COMPLIANCE`; it accepts only a future timestamp that extends the existing retention. Compliance cannot be downgraded. Legal holds are not implemented in this release.

The application-level `retentionUntil` field records the successfully requested storage retention. Object storage credentials are held only by the control plane and bucket policies must deny anonymous listing and reads.

## Configuration and Deployment

The repository uses an `ObjectStoragePort` and configuration keys rather than a MinIO-specific API. Local/integration topology supplies MinIO; production may use MinIO with TLS, AWS S3, or another compatible provider. The required settings are endpoint, region, bucket, access key, secret key, TLS mode, retention mode and default retention duration. No storage credential may enter `.aisdlc.yml`, portal HTML, browser storage or a CLI log.

The Java infrastructure adapter uses the AWS SDK for Java 2.x `bom`, `s3` and `url-connection-client` artifacts. AWS documents the BOM as the version-alignment mechanism and recommends importing only the service modules and HTTP client that an application actually needs.[4] This keeps the repository adapter compatible with Amazon S3 and S3-compatible endpoints without leaking provider classes across module boundaries.

Before enabling a bucket for evidence, bootstrap validates bucket privacy, versioning and Object Lock. MinIO documentation confirms that object locking requires versioning and supports retention and legal holds on object versions.[3] The bootstrap operation is idempotent and refuses to silently weaken an existing bucket configuration.

## API and Event Contracts

The versioned API surface is intentionally narrow:

| Operation | Path shape | Authorization and audit |
|---|---|---|
| Upload bounded multipart asset | `POST /api/v1/projects/{projectId}/evidence-assets` | Owner/developer/reviewer; `file`, `assetType`, optional `accessLevel`/`validationEvidenceId`; accepts `X-Content-SHA256` and `Idempotency-Key`; records `evidence.asset.uploaded`. |
| List/search metadata | `GET /api/v1/projects/{projectId}/evidence-assets` | Any scoped reader; page/filter bounded. |
| Retrieve metadata and short-lived download URL | `GET /api/v1/projects/{projectId}/evidence-assets/{assetId}` | Scoped reader plus access-level check. The returned `downloadUrl` is a presigned GET, not an object-store public endpoint. |
| Extend retention lock | `PUT /api/v1/projects/{projectId}/evidence-assets/{assetId}/retention` | Owner/reviewer; body includes `GOVERNANCE` or `COMPLIANCE` and a future timestamp; writes `evidence.asset.retention.locked`. |
| Soft delete metadata | `DELETE /api/v1/projects/{projectId}/evidence-assets/{assetId}` | Owner/reviewer; object bytes are not force-deleted, preserving storage retention guarantees; writes `evidence.asset.soft_deleted`. |

Every upload supports idempotency. Domain events `evidence.asset.uploaded`, `evidence.asset.retention.locked`, and `evidence.asset.soft_deleted` are appended to the immutable audit ledger in the same database transaction as metadata state changes. Multipart direct upload is the implemented contract; resumable upload sessions, object version links, legal holds and independent access-event records remain an explicit evolution path rather than implied functionality.

## References

[1] [MinIO AIStor Java Client API — presigned object URL operations](https://docs.min.io/aistor/developers/sdk/java/api/)

[2] [Amazon S3 Object Lock — retention, governance/compliance modes and legal holds](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)

[3] [MinIO AIStor Object Locking and Immutability](https://docs.min.io/aistor/administration/object-locking-and-immutability/)

[4] [AWS SDK for Java 2.x — Maven setup and service-module dependencies](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html)
