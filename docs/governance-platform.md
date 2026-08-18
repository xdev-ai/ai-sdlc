# Governance platform

The governed flow itself: what a validation run becomes, how policy is expressed, who decides, where evidence lives, and how a tenant is bounded.

- [Validation Finding and Evidence Lifecycle](#validation-finding-and-evidence-lifecycle)
- [Policy-as-Code](#policy-as-code)
- [Approval and Notification Orchestration](#approval-and-notification-orchestration)
- [Notification Delivery Contracts](#notification-delivery-contracts)
- [Evidence & Governance Data Repository Architecture](#evidence-governance-data-repository-architecture)
- [Quality and Risk Intelligence](#quality-and-risk-intelligence)
- [Enterprise Multi-Tenancy and Identity Integration](#enterprise-multi-tenancy-and-identity-integration)
- [Knowledge Base: Project Documentation an AI Can Read](#knowledge-base-project-documentation-an-ai-can-read)

## Validation Finding and Evidence Lifecycle

Validation results originate with the deterministic CLI, but their operational handling is a governed human workflow in the control plane. The lifecycle deliberately separates immutable execution evidence from mutable operational metadata.

### Immutable and Mutable Fields

| Record | Immutable data | Governed lifecycle data |
|---|---|---|
| Validation run | Idempotency key, CLI version, Spec Kit version, model pin, status and ingest timestamp | None; a run is a historical execution fact. |
| Finding | Rule code, message, severity, source path and line | Triage status, note, actor and timestamp. |
| Evidence | Type, URI and SHA-256 digest | Retention deadline. |

The evidence URI is displayed as an external reference; its digest remains the verification anchor. Adjusting a retention deadline does not change the referenced object or digest.

### Finding Triage

Authorized project members transition a finding from `OPEN` to one of `ACKNOWLEDGED`, `RESOLVED`, `FALSE_POSITIVE`, or `ACCEPTED_RISK`. A rationale is required for `FALSE_POSITIVE` and `ACCEPTED_RISK`, so non-remediation remains explicit and auditable. Each successful transition writes `VALIDATION_FINDING_TRIAGED` to the organization audit ledger with actor, target and outcome metadata.

The API uses a project- and run-scoped resource path and validates that the finding belongs to the supplied run. This prevents a caller from updating a finding through a different project context. The SSR portal presents the same workflow from a selected immutable validation run and applies normal CSRF protection.

### Evidence Retention

Evidence retention is set as a future UTC instant. The database checks that it falls after the evidence creation time and indexes the deadline to support a future controlled cleanup worker. No automatic delete process is included in the application: deletion policy must be configured as a separately authorized retention operation, preserving the audit and legal-hold review boundary.

Every retention update writes `VALIDATION_EVIDENCE_RETENTION_SET` to the audit ledger. Operators should preserve audit-event rows even when evidence objects expire, retaining the digest and lifecycle record needed to explain historical validation.

### Operational Use

1. Open **Validations** in the SSR portal, filter runs and select **Inspect**.
2. Review the immutable code, severity, source location and evidence digest.
3. Record a human triage decision with a concise rationale where required.
4. Set or correct the evidence retention deadline according to the organization’s approved retention schedule.
5. Verify both changes through the organization audit ledger and its hash-chain verification endpoint.

---

## Policy-as-Code

### Design Basis

AI-SDLC policy bundles use Common Expression Language (CEL) expressions for bounded, deterministic policy decisions. CEL is selected because it is non-Turing complete and supports compile-once/evaluate-many operation; the platform does not embed a general-purpose scripting runtime.

Policy authors supply an expression and a fixture collection. The service owns the entire CEL environment: it exposes only a single JSON-like `context` map and no custom host functions. Compilation happens on create/update or explicit dry-run, not on a latency-sensitive enforcement path. Expressions must evaluate to a Boolean value; an error, an unknown value, a non-Boolean result, or a resource-limit violation is a deterministic failed evaluation rather than a pass.

| Control | AI-SDLC behavior |
|---|---|
| Language | CEL only; no JavaScript, shell, Python, Rego runtime, or dynamically loaded functions. |
| Authoring boundary | Maximum source and fixture size, explicit semantic version, immutable version records, and project scope. |
| Runtime boundary | Declared `context` variable only, bounded JSON depth/node count, no host functions, and a per-evaluation timeout. |
| Lifecycle | `DRAFT` → `ACTIVE` → `RETIRED`; only owners can activate or retire a bundle. |
| Dry run | Evaluation is recorded without being treated as an enforcement decision. |
| Verification | Fixtures state expected Boolean outcomes and must pass before activation. |
| Evidence | Every evaluation produces an audit event and retained result record, including context digest rather than raw sensitive context. |

### References

[1] [CEL for Java: installation, type-checking and evaluation](https://github.com/cel-expr/cel-java)

[2] [CEL overview: environment declaration and compile-once/evaluate-many model](https://cel.dev/overview/cel-overview)

---

## Approval and Notification Orchestration

### Purpose

The AI-SDLC control plane uses a project-scoped orchestration service to deliver governance notifications and collect **human** decisions. It does not replace any human decision point with automation. Notifications make a decision observable; they never approve, reject, merge, or release software.

### Notification Channels

Project owners can configure `EMAIL`, `SLACK_WEBHOOK`, `TEAMS_WEBHOOK`, or `GENERIC_WEBHOOK` channels through the API or SSR portal. Webhook destinations must use HTTPS. Generic webhooks require a channel-specific signing secret. Email destinations must be syntactically email-like and require a configured Spring Mail sender at runtime.

Destinations and generic-webhook signing secrets are encrypted with AES-256-GCM before persistence. Set `AISDLC_NOTIFICATION_ENCRYPTION_KEY` to a 32-byte base64url value. A channel list response returns only a SHA-256 destination fingerprint; it never returns a destination, encrypted ciphertext, or signing secret.

| Channel | Delivery form | Additional control |
|---|---|---|
| `EMAIL` | Spring Mail plaintext message | Requires configured sender and `from-address`. |
| `SLACK_WEBHOOK` | JSON message to an HTTPS incoming webhook | Retry on network failure, HTTP 429, and HTTP 5xx. |
| `TEAMS_WEBHOOK` | JSON message to an HTTPS incoming webhook | Retry on network failure, HTTP 429, and HTTP 5xx. |
| `GENERIC_WEBHOOK` | Versioned JSON delivery envelope | Includes `X-AISDLC-Delivery`, `X-AISDLC-Timestamp`, and `X-AISDLC-Signature-256`. |

For generic webhooks, verify the signature by recomputing `HMAC-SHA256(timestamp + "." + raw JSON body, channel secret)` and compare it in constant time. Reject delivery timestamps outside the receiving service's replay window.

### Delivery Ledger and Retry Behavior

Each enabled channel gets one `notification_deliveries` entry per idempotency key. The immutable `notification_delivery_receipts` table captures every completed attempt, its payload SHA-256, HTTP status when available, and terminal/error code.

The dispatcher uses short database transactions to claim and complete a delivery. It deliberately performs network I/O outside the database transaction. It retries only network errors, HTTP `429`, and HTTP `5xx`, with capped exponential backoff. Configuration errors, invalid responses, disabled channels, and non-retryable client errors become terminal states. A stale `SENDING` claim is eligible for reconciliation after ten minutes.

`GovernanceAutomationScheduler` invokes delivery dispatch and approval SLA processing through configurable cron expressions. These deterministic tasks are application-native; no LLM or external agent is involved.

### Approval Lifecycle

An approval request is bounded by a project, source reference, quorum from 1 to 50, due timestamp, optional assigned approver, and immutable decisions. Only project owners and reviewers can decide or delegate. If an approver is assigned, only that subject, its explicit delegate, or the organization break-glass owner identity may decide.

| State | Transition | Evidence |
|---|---|---|
| `PENDING` | Created with a future SLA | `APPROVAL_REQUEST_CREATED` audit event and `approval.requested` notification. |
| `PENDING` / `ESCALATED` | Approve quorum is met | Immutable approval decisions, `APPROVAL_DECISION_RECORDED`, and `approval.decided` notification. |
| `PENDING` / `ESCALATED` | Any authorized rejection | Immutable rejection and terminal `REJECTED` state. |
| `PENDING` | Due timestamp passes | `ESCALATED` status and idempotent `approval.escalated` notification. |
| `PENDING` / `ESCALATED` | Due soon | Bounded periodic `approval.reminder` notification. |

Duplicate decisions by the same actor are rejected. Delegation never alters an earlier decision, and delegation is recorded in the audit ledger. Automation can issue reminders and escalation notices only; it cannot manufacture an approval.

### Security Exception Expiry

Security exceptions are persisted as project-scoped records rather than inferred from a mutable runtime file. They must have a future expiry and owner/reviewer authorization. The SLA processor emits expiring and expired notices with idempotency keys and changes an expired exception's lifecycle state. The CI `.trivyignore.yaml` expiry validation remains an independent fail-closed control.

### API Surface

| Endpoint | Use |
|---|---|
| `POST /api/v1/projects/{projectId}/notification-channels` | Create an encrypted channel. |
| `GET /api/v1/projects/{projectId}/notification-channels` | List safe channel metadata. |
| `PATCH /api/v1/projects/{projectId}/notification-channels/{channelId}` | Enable or disable a channel. |
| `GET /api/v1/projects/{projectId}/notification-deliveries` | Read the delivery ledger. |
| `POST /api/v1/projects/{projectId}/approvals` | Request a governed human approval. |
| `GET /api/v1/projects/{projectId}/approvals` | Read project-scoped approval queue. |
| `POST /api/v1/approvals/{approvalId}/decisions` | Record an immutable approval or rejection. |
| `POST /api/v1/approvals/{approvalId}/delegation` | Delegate a pending approval. |
| `POST /api/v1/projects/{projectId}/security-exceptions` | Record a time-bounded security exception. |

### Operations

Set the notification encryption key before enabling any channel. Configure optional email sender settings only when email delivery is required. Review failed delivery receipts, rotate generic-webhook secrets by creating a replacement channel, and disable the old channel after downstream verification. Treat each completed approval decision and delivery receipt as audit evidence subject to the platform's retention policy.

---

## Notification Delivery Contracts

### Purpose

AI-SDLC sends governance notifications only through explicitly configured and enabled channels. Every attempt creates an immutable delivery receipt that records the channel, message identity, destination fingerprint, outcome, HTTP status when applicable, and retry disposition. Channel secrets are never returned by APIs, portal pages, audit event payloads, or delivery receipts.

### Slack Incoming Webhooks

Slack incoming webhooks accept an HTTPS `POST` with a JSON body containing at minimum a `text` value. The webhook URL is itself a secret and must not be committed or exposed. AI-SDLC sends concise plain-text summaries with a stable delivery identifier, treats 2xx as delivered, retries only rate-limit and transient server failures, and marks configuration/authentication failures as terminal. Slack reports actionable errors for malformed payloads, disabled hooks, archived channels, and invalid tokens, so those errors require operator remediation rather than blind retries. [1]

### Microsoft Teams Webhook Workflows

Microsoft recommends Teams Workflows/Power Automate webhook URLs for new deployments because legacy Microsoft 365 connectors are approaching deprecation. A workflow receives an HTTPS `POST` with a JSON payload and can post a message or Adaptive Card. AI-SDLC uses the transport-neutral text payload that Workflow templates accept, with a 28 KB maximum message size. Teams documents a throughput threshold of four requests per second and recommends exponential backoff for HTTP 429 responses; the notification dispatcher therefore applies bounded retry with backoff and never performs unbounded fan-out. [2]

### Security and Operational Rules

| Rule | Requirement |
|---|---|
| Destination protection | Store the destination URL encrypted at rest; show only a redacted fingerprint after creation. |
| Outbound authentication | Every generic outbound webhook receives HMAC SHA-256 headers containing timestamp, delivery ID, and payload signature. |
| Replay resistance | Receivers must reject timestamps outside their accepted skew window and deduplicate the delivery ID. |
| Retry discipline | Retry network errors, HTTP 429, and HTTP 5xx only; respect `Retry-After` when present. |
| Terminal failure | Do not retry malformed payload, authentication, authorization, invalid endpoint, or disabled channel responses. |
| Audit evidence | Preserve message digest and receipt metadata, never the raw notification secret. |

### References

[1]: https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks "Slack Developer Docs: Sending messages using incoming webhooks"
[2]: https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook "Microsoft Learn: Create Incoming Webhooks"

---

## Evidence & Governance Data Repository Architecture

**Status:** Implemented in the current pre-release. This document defines the storage extension that makes AI-SDLC packages independently integrable while preserving the platform’s audit, authorization and human-decision invariants.

> **Design principle:** PostgreSQL is the source of truth for governance metadata and authorization; S3-compatible object storage is the source of truth for large immutable bytes. A storage object is never made public merely because it exists.

### Module Boundary Contract

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

### Implemented Scope

The first production-capable slice is deliberately direct: an authenticated client sends one bounded multipart asset to the control plane; the server independently computes SHA-256, stores the bytes through the S3-compatible port, writes project-scoped metadata, and appends an immutable audit event in its database transaction. The metadata migration is `V4__evidence_repository.sql`. If the metadata transaction rolls back after the object upload, the service attempts compensating object deletion; it never exposes a public bucket URL.

| Implemented concern | Behaviour |
|---|---|
| Storage boundary | `ObjectStoragePort` isolates application code from AWS SDK, MinIO and provider-specific SDK classes. `S3ObjectStorageAdapter` is the default adapter. |
| Metadata | `evidence_assets` stores project, optional linked validation evidence, typed classification, filename/content type, byte size, bucket/key, SHA-256, idempotency key, actor, access level, retention and soft-delete timestamp. |
| Idempotency | `project_id + idempotency_key` is unique. A supplied key must be 8–120 URL-safe characters; an omitted key is deterministically derived from project, type, classification, linked validation evidence and digest. |
| Access | Upload is available to owner/developer/reviewer. List is project-member scoped. Download honours `PROJECT`, `REVIEWERS` and `OWNERS` classification. Retention and soft delete require owner or reviewer. |
| Immutability | Retention can only extend. A `COMPLIANCE` lock cannot be downgraded. The S3 adapter maps the chosen mode and timestamp to provider object-lock retention. |

### Repository Data Model

| Table / concept | Key fields | Integrity rule |
|---|---|---|
| `evidence_assets` | project, optional `validation_evidence_id`, type, filename, MIME type, byte length, bucket/key, SHA-256, idempotency key, access level, retention, soft-delete timestamp | One asset belongs to exactly one project. `(project_id, idempotency_key)` and `(s3_bucket, s3_key)` are unique. |
| S3-compatible object | private opaque key `projects/{project-id}/evidence-assets/{uuid}/{sanitized-name}` with SHA-256/project/actor metadata | Object bytes do not reside in PostgreSQL and object names are never client supplied paths. |
| Append-only audit ledger | actor, project, event type, asset ID and digest/retention metadata | Upload, retention lock and soft deletion always append a governance event in the metadata transaction. |

The database stores no file bytes. A user filename is display metadata only; it is sanitized before use as the tail of an opaque server-generated object key.

### Upload, Verification and Download

An authorized project member uploads one bounded multipart file to the control plane. The server computes SHA-256 from the received bytes and rejects any mismatch with the optional `X-Content-SHA256`. It then persists the private object using server-held credentials, saves provenance metadata and appends an audit event. The current implementation uses a bounded in-memory multipart representation; the configured upload limit must remain appropriate for the management-server memory allocation.

An authorized reader retrieves asset detail. The control plane verifies project membership and classification policy first, then issues a short-lived presigned `GET`; the object store remains private. Presigned URL support is provided by the S3-compatible Java client API.[1]

### Retention, Immutability and Legal Hold

Production buckets use S3 versioning and Object Lock. Object Lock operates per version and requires versioning; a retention period or legal hold protects an individual version, while a simple delete can create a delete marker rather than erase a locked prior version.[2] The implemented retention endpoint applies either `GOVERNANCE` or `COMPLIANCE`; it accepts only a future timestamp that extends the existing retention. Compliance cannot be downgraded. Legal holds are not implemented in this release.

The application-level `retentionUntil` field records the successfully requested storage retention. Object storage credentials are held only by the control plane and bucket policies must deny anonymous listing and reads.

### Configuration and Deployment

The repository uses an `ObjectStoragePort` and configuration keys rather than a MinIO-specific API. Local/integration topology supplies MinIO; production may use MinIO with TLS, AWS S3, or another compatible provider. The required settings are endpoint, region, bucket, access key, secret key, TLS mode, retention mode and default retention duration. No storage credential may enter `.aisdlc.yml`, portal HTML, browser storage or a CLI log.

The Java infrastructure adapter uses the AWS SDK for Java 2.x `bom`, `s3` and `url-connection-client` artifacts. AWS documents the BOM as the version-alignment mechanism and recommends importing only the service modules and HTTP client that an application actually needs.[4] This keeps the repository adapter compatible with Amazon S3 and S3-compatible endpoints without leaking provider classes across module boundaries.

Before enabling a bucket for evidence, bootstrap validates bucket privacy, versioning and Object Lock. The pinned MinIO server image intentionally omits `curl` and `wget`, so a container-local HTTP healthcheck would permanently report an unavailable executable instead of storage health. The pinned MinIO Client uses `mc ready` to retry the official quorum readiness endpoint, after which it creates the Object Lock bucket before the management server starts.[5] MinIO documentation confirms that object locking requires versioning and supports retention and legal holds on object versions.[3] The bootstrap operation is idempotent and refuses to silently weaken an existing bucket configuration. The integration smoke runner separately probes `/minio/health/ready` over HTTP.

### API and Event Contracts

The versioned API surface is intentionally narrow:

| Operation | Path shape | Authorization and audit |
|---|---|---|
| Upload bounded multipart asset | `POST /api/v1/projects/{projectId}/evidence-assets` | Owner/developer/reviewer; `file`, `assetType`, optional `accessLevel`/`validationEvidenceId`; accepts `X-Content-SHA256` and `Idempotency-Key`; records `evidence.asset.uploaded`. |
| List/search metadata | `GET /api/v1/projects/{projectId}/evidence-assets` | Any scoped reader; page/filter bounded. |
| Retrieve metadata and short-lived download URL | `GET /api/v1/projects/{projectId}/evidence-assets/{assetId}` | Scoped reader plus access-level check. The returned `downloadUrl` is a presigned GET, not an object-store public endpoint. |
| Extend retention lock | `PUT /api/v1/projects/{projectId}/evidence-assets/{assetId}/retention` | Owner/reviewer; body includes `GOVERNANCE` or `COMPLIANCE` and a future timestamp; writes `evidence.asset.retention.locked`. |
| Soft delete metadata | `DELETE /api/v1/projects/{projectId}/evidence-assets/{assetId}` | Owner/reviewer; object bytes are not force-deleted, preserving storage retention guarantees; writes `evidence.asset.soft_deleted`. |

Every upload supports idempotency. Domain events `evidence.asset.uploaded`, `evidence.asset.retention.locked`, and `evidence.asset.soft_deleted` are appended to the immutable audit ledger in the same database transaction as metadata state changes. Multipart direct upload is the implemented contract; resumable upload sessions, object version links, legal holds and independent access-event records remain an explicit evolution path rather than implied functionality.

### References

[1] [MinIO AIStor Java Client API — presigned object URL operations](https://docs.min.io/aistor/developers/sdk/java/api/)

[2] [Amazon S3 Object Lock — retention, governance/compliance modes and legal holds](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)

[3] [MinIO AIStor Object Locking and Immutability](https://docs.min.io/aistor/administration/object-locking-and-immutability/)

[4] [AWS SDK for Java 2.x — Maven setup and service-module dependencies](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html)

[5] [MinIO Client — `mc ready` reference](https://docs.min.io/aistor/reference/cli/mc-ready/)

---

## Quality and Risk Intelligence

### Scope

The Risk Cockpit turns persisted AI-SDLC governance evidence into an explainable, bounded prioritization signal. It is not a machine-learning model, a prediction of delivery failure, a compliance verdict, or an automated merge/release gate. A human remains responsible for interpreting the score and approving a delivery.

Every calculated result is a retained `risk_scores` snapshot with a formula version, component map, source-count map, actor, timestamp, and immutable audit event. This makes a score reproducible from the same evidence population.

### `risk.v1` Formula

The score is an integer between 0 and 100. Its components are capped independently, then summed.

| Component | Maximum | Persisted inputs |
|---|---:|---|
| Finding risk | 25 | Critical and high validation findings completed in the past 90 days. |
| Policy risk | 20 | `FAIL` or `ERROR` enforcement policy evaluations in the past 30 days. |
| Exception risk | 15 | Expired and next-14-day expiring security exceptions. |
| Evidence risk | 10 | Completed 30-day validation runs that have no `validation_evidences` record. |
| Workflow risk | 10 | Pending review items and overdue approval requests. |
| Quality risk | 10 | Latest retained quality snapshot: failure, rework, alignment, queue health, lead/review time, and security debt. |
| Provenance risk | 10 | Release provenance records still in `DECLARED` verification state. |

The band is derived only from the calculated score:

| Score | Band |
|---:|---|
| 0–24 | `LOW` |
| 25–49 | `MODERATE` |
| 50–74 | `HIGH` |
| 75–100 | `CRITICAL` |

The source summary also records agent-session volume, but it does not increase `risk.v1` by itself. This distinction prevents a team from being penalized simply for recording governed AI assistance.

### API and Access Model

| Endpoint | Authority | Result |
|---|---|---|
| `POST /api/v1/projects/{projectId}/risk-intelligence/recompute` | Project owner or reviewer | Recomputes and retains an audited snapshot. |
| `GET /api/v1/projects/{projectId}/risk-intelligence/latest` | Any project member | Returns the latest snapshot. |
| `GET /api/v1/projects/{projectId}/risk-intelligence/trend` | Any project member | Returns descending, paginated snapshot history. |

The SSR portal exposes the same workflow at **Risk cockpit**. The interactive React Island is an enhancement only: the server-rendered snapshot history, formula version, and evidence summary are available without JavaScript.

### Interpretation and Response

A high score should trigger evidence review, not automatic action. Review the associated source counts in the snapshot, then triage each underlying record through its native workflow: validation findings, policy bundle, security exception, approval request, evidence repository, or provenance verification.

When a formula changes, add a new named formula version rather than changing `risk.v1` in place. Preserve previous scores exactly, document the formula migration, and compare trend lines only within the same formula version unless an analyst explicitly normalizes them.

### Data Quality Safeguards

The service reads only project-scoped database rows and fails closed when it cannot persist the resulting snapshot. It never fabricates a missing metric, calls an AI model, writes decisions into review workflows, or alters the lifecycle of its inputs. A project with no quality metrics receives zero quality-risk points rather than an invented estimate.

---

## Enterprise Multi-Tenancy and Identity Integration

### Design Sources

The implementation follows the SCIM protocol and core schema defined by IETF RFC 7644 and RFC 7643. SCIM is an HTTP protocol for provisioning and managing cross-domain identity resources such as users and groups; RFC 7644 specifies resource endpoints, retrieval, mutation, errors, resource versioning, service-provider configuration, multi-tenancy, TLS, token, and privacy considerations. [1] [2]

Keycloak 26.7.1 supports OpenID Connect, OAuth 2.0, SAML, identity brokering with external OIDC or SAML identity providers, groups, composite roles, and token/claim protocol mappers. The platform therefore stores tenant-specific federation intent and permission mappings, while a Keycloak administrator applies confidential provider credentials and activates the corresponding broker configuration in the identity plane. [3]

### Boundary Model

An AI-SDLC tenant is an enterprise data and governance boundary. A tenant has an immutable external key, display name, lifecycle status, residency code, encryption-key reference, and a legal-hold state. Tenant scope is enforced at the application service boundary and attached to organization/project resources. A tenant must not be inferred from user-controlled request data.

The management server never persists a private key, SAML signing key, raw SCIM bearer token, or raw evidence export secret. SCIM bearer tokens are generated once and stored only as SHA-256 hashes. When a tenant administrator elects to retain an OIDC client secret for a declared federation record, the server encrypts it with the existing AES-256-GCM deployment encryption boundary and never returns it through the API. Key material, signing keys, and Keycloak broker activation remain deployment/identity-plane responsibilities.

### Identity Contracts

The initial SCIM surface is tenant-scoped at `/scim/v2/tenants/{tenantId}/Users`, where `tenantId` is a platform UUID. It requires a tenant-bound provisioning principal whose raw bearer token matches a stored SHA-256 hash. The server supports SCIM `Users` list and create/upsert, with `application/scim+json` envelopes and core User schema URN. Group provisioning, ServiceProviderConfig discovery, bulk operations, password attributes, PATCH, and delete/deactivate operations are intentionally not exposed until their authorization and reconciliation contracts are separately implemented.

SCIM responses use `application/scim+json`, core schema URNs, and list pagination envelopes. Externally supplied subject identifiers are idempotent only inside their tenant. Every mutation emits a tenant-scoped immutable audit event. Clients must use HTTPS and retain the one-time provisioning token in an external secret manager.

Tenant federation configurations support OIDC and SAML metadata declarations. An active federation configuration is a policy record, not an automatic credential rotation or Keycloak mutation. The documented administration runbook requires issuer/entity-ID verification, HTTPS metadata retrieval, certificate pin/fingerprint review, claim-to-subject mapping, approved domain restrictions, explicit group mapping, and a tested break-glass local administrator path before activation.

### Authorization and Legal Hold

Custom permission sets are additive metadata for tenant members and mapped IdP groups. Built-in platform RBAC remains the enforcement authority for current project APIs; future endpoints must explicitly consult mapped tenant permissions before treating a declared permission as an authorization grant. Tenant-admin authority is required for role configuration, federation policy, provisioning, legal hold, and e-discovery export.

Legal hold is a tenant-scoped, auditable control that prevents its own release by an unauthorized actor and records the tenant's active hold state. E-discovery export is permission-gated and writes a JSON manifest to object storage with a SHA-256 digest, a one-year compliance retention lock, and a short-lived presigned download URL. The manifest contains bounded tenant audit and organization audit chain metadata, never secrets, access tokens, client secrets, raw notification destinations, or object bytes.

### Operational API Summary

| Capability | API path | Required tenant role |
|---|---|---|
| Tenant bootstrap | `POST /api/v1/tenants` | Platform `admin`; creator becomes `TENANT_ADMIN`. |
| Membership and custom permission metadata | `/api/v1/tenants/{tenantId}/memberships`, `/permission-sets` | `TENANT_ADMIN`. |
| OIDC/SAML declaration | `/api/v1/tenants/{tenantId}/federation-configs` | `TENANT_ADMIN` or `IDENTITY_ADMIN`. |
| One-time SCIM credential | `POST /api/v1/tenants/{tenantId}/scim-service-principals` | `TENANT_ADMIN` or `IDENTITY_ADMIN`. |
| SCIM User list/upsert | `/scim/v2/tenants/{tenantId}/Users` | Valid tenant SCIM bearer token. |
| Legal hold | `/api/v1/tenants/{tenantId}/legal-holds` | `TENANT_ADMIN` or `COMPLIANCE_OFFICER`. |
| E-discovery manifest | `/api/v1/tenants/{tenantId}/e-discovery-exports` | `TENANT_ADMIN`, `COMPLIANCE_OFFICER`, or `AUDITOR`. |

### References

[1] [RFC 7644: System for Cross-domain Identity Management Protocol](https://datatracker.ietf.org/doc/html/rfc7644)

[2] [RFC 7643: System for Cross-domain Identity Management Core Schema](https://datatracker.ietf.org/doc/html/rfc7643)

[3] [Keycloak 26.7.1 Server Administration Guide](https://www.keycloak.org/docs/26.7.1/server_admin/)

## Requirement Specifications: Which Document Version Governs a Requirement

The traceability graph records requirement → spec → task → test → evidence, and `spec_kits` records immutable document
versions, but until now nothing connected the two. A requirement could not answer "which version of which analysis
document specifies me" — the column an external requirement sheet carries as its analysis-document column.

### Why a table and not a column

A `spec_kit_id` column on `trace_nodes` would be overwritten every time a requirement is re-specified by a newer
document, which destroys the one fact this exists for: when the governing document changed, who changed it, and what it
was before. Document change management *is* the history, so the history is the schema. Links are append-only; a database
trigger refuses both `DELETE` and any `UPDATE` that touches a column other than the supersede columns.

A partial unique index permits exactly one **open** link per requirement, so superseding is close-then-insert in one
transaction. The service does both statements itself rather than letting a client sequence them: a close without an
insert leaves a requirement unspecified, and an insert without a close violates the index, so one failed request could
otherwise leave the ledger in either state.

### API Surface

All endpoints are project scoped. Writes require owner or developer; reads accept viewer.

| Endpoint | Method | Use |
|---|---|---|
| `/api/v1/projects/{projectId}/requirement-specifications` | `POST` | Link a requirement to a document version, superseding whatever governed it before. |
| `/api/v1/projects/{projectId}/requirement-specifications` | `GET` | Paged list of the current specification of every requirement. |
| `/api/v1/projects/{projectId}/requirement-specifications/close` | `POST` | Close the current link without opening another, for a withdrawn document. |
| `/api/v1/projects/{projectId}/requirement-specifications/history/{traceNodeId}` | `GET` | Every link a requirement has ever had, current first, with the reason each was replaced. |
| `/api/v1/projects/{projectId}/requirement-specifications/unspecified` | `GET` | Requirements nothing currently specifies. |
| `/api/v1/projects/{projectId}/requirement-specifications/by-document/{specKitId}` | `GET` | Which requirements a document version currently governs, for impact analysis before revising it. |

`sourceDocumentCode` is deliberately unconstrained beyond a length bound: it carries the document code exactly as the
issuing system writes it. `spec_kits.slug` is restricted to `[a-z0-9-]`, so a code like `SPEC-042_v1.0` cannot
round-trip through it, and losing the original reference breaks the link back to the authority that issued the document.

### Refusals

A **deprecated** document version cannot be assigned as a current specification (`409`), though superseded links keep
pointing at it — history must stay readable. Superseding an existing link without a stated reason is refused (`400`), as
is closing a link with a blank reason, closing a requirement that has no current link, and linking a requirement or a
document that belongs elsewhere.

### Verification

Covered by 24 assertions in `scripts/feature-sweep.sh`, which runs in CI: the document code surviving verbatim, a
revision opening a new link rather than editing the old one, the closed row still pointing at the version that used to
govern along with the reason it stopped, the gap report flipping as links open and close, and a second close being
refused rather than silently accepted. `RequirementSpecificationServiceTest` covers the refusals at the unit level.

The five schema invariants were verified by attacking the live database directly, not only through the service: the
rewrite trigger, the delete trigger, the partial unique index on open links, the constraint requiring the supersede
columns to move together, and the constraint requiring `superseded_at >= linked_at`. All five refused.

## Knowledge Base: Project Documentation an AI Can Read

Governance artifacts describe what was released. They do not hold the prose that explains a system — the analysis documents, process descriptions and operating procedures a team actually writes. Without somewhere to put that, an AI asked a question about the project has nothing to ground an answer in, and the documentation lives in a shared drive where no version, author or reason is recorded.

The knowledge base is that place, shaped like Confluence because that shape is already understood: a space holds a tree of pages, each page has an immutable version history, and pages carry labels.

### Data model

| Table | Holds | Why it is separate |
|---|---|---|
| `knowledge_spaces` | The top-level container, scoped to an organization and optionally one project. | A space is what someone browses and what search is scoped to. |
| `knowledge_pages` | Identity and position in the tree; which version is current. | Page identity outlives any particular wording. |
| `knowledge_page_versions` | Title, Markdown body, digest, change note, author. Append-only. | The previous wording must stay retrievable and attributable. |
| `knowledge_page_labels` | Flat labels for cross-tree grouping. | A tree has one path per page; a topic does not. |
| `knowledge_page_references` | A citation from a page to a Spec Kit, trace node, or evidence asset. Exactly one target per row. | Documentation and governed evidence otherwise drift apart. |
| `knowledge_chunks` | One section of one page version, with its heading path and a generated `tsvector`. | This is the unit handed to a model, and the unit an answer cites. |

Pages are not stored as Spec Kits. A Spec Kit is a released artifact — one immutable manifest, registered and pinned, deliberately hard to change. Documentation is authored prose, edited continuously and read a paragraph at a time. Forcing pages into Spec Kits would make every typo a release; forcing releases into pages would lose the pinning contract. They are linked instead.

### What the database refuses

These are enforced by constraints and triggers, not by application code, and each was verified by attempting it against a live database:

- `UPDATE` or `DELETE` on a page version — an edited version is not a version.
- A page parented under one of its own descendants, or under a page in a different space. The walk is bounded at 100 hops.
- A reference row with zero, two, or three targets.
- A duplicate version number on one page.

### Chunking

A page body is split at Markdown headings, and each chunk carries the heading path leading to it, so an answer cites `Intake > Insurance check` rather than a file name. Two rules exist because of specific failure modes:

- A `#` inside a fenced code block is a shell comment, not a heading. Fenced blocks are indivisible, so no chunk can end with an unclosed fence. The cost is documented: a fenced block larger than the chunk limit is emitted whole rather than cut, because half a code sample is worthless, and a block beyond 40,000 characters is refused outright.
- Oversized sections are divided between paragraphs, then between lines, and only cut mid-line when nothing else is left to split on.

A page whose body opens with its own title as an `h1` cites as `Intake`, not `Intake > Intake`.

### Retrieval, and what it is not

`GET /api/v1/organizations/{organizationId}/knowledge/search` returns ranked chunks. `GET .../knowledge/context` returns a bundle sized to a character budget, each chunk with a citation naming space, page, version and section.

**This is lexical retrieval, not semantic search.** pgvector is not available in this deployment, so there are no embeddings. What exists is accent-folded keyword matching over a `simple` tsvector — someone searching `tiep nhan` finds `tiếp nhận` — plus one trigram pass for typos when keyword matching returns nothing, reported as `matchedBy: "trigram"` so a fuzzy match is not mistaken for an exact one. A question worded differently from the document will not match. That caveat is returned in the response body rather than left in this document, because the caller assembling a prompt is the one who needs it.

PostgreSQL also ships no Vietnamese text-search configuration, so Vietnamese content gets `simple`: exact and prefix matching after accent folding, with no stemming. `unaccent()` is `STABLE`, not `IMMUTABLE`, so a generated column cannot call it directly; the migration wraps the two-argument form in an `IMMUTABLE` function, which means existing `search_vector` values would need rebuilding if the unaccent dictionary were ever modified.

Search reads the **current version only**. Superseded wording stays in the table for audit but is excluded from retrieval, because a model that retrieves the paragraph a document deliberately replaced will answer with the version someone decided was wrong.

### Endpoints

| Operation | Endpoint | Authorization |
|---|---|---|
| Create/list/archive a space | `/api/v1/organizations/{organizationId}/knowledge/spaces` | Read: any control-plane role. Write: `admin` or `developer`. |
| Create a page, list the tree | `.../knowledge/spaces/{spaceId}/pages` | As above. |
| Read a page, author a version | `.../knowledge/pages/{pageId}` | `PUT` appends version *n+1*; nothing is overwritten. |
| Version history, one version | `.../knowledge/pages/{pageId}/versions[/{version}]` | Read. |
| Move, publish, label, cite | `.../knowledge/pages/{pageId}/{parent,status,labels,references}` | `admin` or `developer`. |
| Search, assemble context | `.../knowledge/{search,context}` | Read. |

Archiving is the only removal. There is no delete: removing documentation would also remove the record that an AI answer was once grounded in it.

### Importing a spreadsheet

Requirement registers, screen inventories and test matrices usually arrive as a workbook: one sheet per module, one row per item. That is a fine format for a person with a mouse and a hopeless one for a model, which receives a wall of values with no indication of what any column means.

Import is deliberately two commands, not one:

```bash
python3 scripts/workbook-to-pages.py <workbook.xlsx> --space-key DOCS \
  --parent-slug workbook-index --out pages.json     # host only, no network
bash scripts/import-pages.sh --payload pages.json --org <organization-uuid> --note "why"
```

Conversion runs on the host with no network access and writes a JSON payload a person can read before anything is transmitted. These workbooks are frequently confidential; doing both steps in one command removes the opportunity to check what is about to leave the machine.

Each sheet becomes a page, and **each row becomes its own subsection** headed by its first non-empty cell, listing `**Column**: value` pairs. A sheet could have become one Markdown table instead, and should not have: a table contains no blank lines, so the chunker sees a single enormous block and divides it between arbitrary rows, leaving every chunk after the first with values and no column names. The cost of the chosen shape is verbosity — the column name repeats on every row — and that repetition is exactly what makes one row interpretable on its own.

Re-running the import is the intended way to refresh. A page that already exists gets a new version rather than a duplicate, so the history shows what the workbook said before, what it says now, and the reason given. An unchanged page reports `same` and writes nothing.

Reading `.xlsx` uses only the Python standard library — the format is a zip of XML — because requiring a `pip install` to read a file the operator already has is a poor trade. Shared strings, inline strings and numbers are all handled. Sheets that cannot be converted (no row with two or more values, so no header can be identified) are **reported on stderr**, never dropped silently.

`scripts/test-workbook-to-pages.sh` builds a synthetic workbook and asserts 16 conversion rules, including the one that matters most: every data row in becomes exactly one section out. A converter that quietly drops rows still prints a success line, and the omission surfaces only when someone searches for a requirement that was never imported and concludes the documentation does not cover it.

### Verification

`scripts/knowledge-sweep.sh` runs 47 assertions against the live stack and is wired into `scripts/integration-smoke.sh`. It covers the properties no unit test can reach: that an unaccented query finds accented content, that wording removed by a later version stops being retrievable, that version 1 is still readable after being superseded, that two concurrent authors both get a version, and that a query consisting of bare tsquery operators returns `200` rather than `500`.

Its first run found a query referencing a column that does not exist — after the code compiled and after the schema itself had been verified directly against PostgreSQL. `MarkdownChunkerTest` covers the chunker as a pure function, including the fence cases.
