# Changelog

All notable changes to the AI-SDLC platform are documented in this file. The repository is currently pre-release; version identifiers become immutable only through the tag-triggered GitHub release workflow.

## [Unreleased]

### Added

- P0 GitHub SCM governance: signed GitHub App webhook ingestion, idempotent delivery ledger, repository links, pull-request/commit/workflow/release correlation, policy Check Run publication, SSR administration workflow, and `aisdlc link-pr`.
- P0 notification and approval orchestration: encrypted email/Slack/Teams/generic-webhook channels, signed versioned generic webhook delivery, immutable receipts, bounded retry/reconciliation, quorum, delegation, SLA reminders, escalation, and retained security-exception expiry notices.
- P0 supply-chain controls: aggregate CycloneDX SBOM generation, SBOM evidence ingest, release provenance ledger, human verification workflow, GitHub artifact attestations, and optional keyless Cosign signature for checksum manifests.
- P1 Policy-as-Code: immutable semantic-versioned CEL bundles, typed side-effect-free Boolean evaluation, dry-run mode, fixture runner, policy lifecycle, retained evaluation evidence, REST API, and SSR workspace.
- P1 AI-agent governance: versioned prompt fingerprints, agent session and tool/context digest provenance, policy-gated generated-change declarations, idempotency, validation/evidence linkage, and mandatory human approval requests.
- P1 Risk Intelligence: auditable `risk.v1` snapshots, component/source lineage, trend API, SSR ledger fallback, and ECharts Risk Cockpit using persisted governance data.
- P2 enterprise tenancy: tenant boundaries, encrypted federation metadata, custom permission sets, SCIM provisioning service principals, tenant audit records, legal holds, and tenant-scoped e-discovery export manifests.
- P2 developer integrations: Java/OpenAPI SDK module, TypeScript client, Terraform provider for notification channels/risk snapshots, VS Code integration manifest, versioned signed outbound webhook contracts, and CI coverage for each integration.
- Evidence & Governance Data Repository with project-scoped `evidence_assets` metadata, SHA-256 provenance, content classification, bounded multipart upload, pagination, soft delete, optional validation-evidence linkage and private presigned downloads.
- S3-compatible `ObjectStoragePort` and AWS SDK for Java 2.x adapter, with MinIO Object Lock topology, deterministic bucket bootstrap, retention extension controls and compensating cleanup on metadata rollback.
- Upload idempotency at the database boundary, audit-backed evidence lifecycle events, SSR portal workflows, and `aisdlc upload` with streaming multipart, digest verification, deterministic retry key and bounded retry/backoff.
- Module integration guide, storage configuration/backup guidance, API documentation and unit tests for evidence service, controller and CLI transport.
- English/Vietnamese portal localization with an English default, persisted preference, allowed-locale validation, safe fallback, SSR coverage and React Islands locale synchronization.

### Security

- Enforced HMAC validation and replay-resistant idempotency for inbound GitHub events, encrypted at-rest notification and federation secrets, signed outbound generic webhooks, SCIM service-principal token hashing, tenant-aware e-discovery access controls, and explicit human verification of release provenance.
- Replaced NVD-dependent OWASP Dependency-Check with OSV-Scanner, Trivy, CodeQL, Dependabot and GitHub dependency review.
- Added fail-closed HIGH/CRITICAL Trivy scanning for repository dependencies, secrets, Docker/Compose configuration and production images, with SARIF evidence retention.
- Added CodeQL security-and-quality analysis for Java, JavaScript/TypeScript, Go and GitHub Actions, plus a weekly advisory-refresh schedule.

## [0.1.0] — Production hardening baseline

### Added

- Organization, project, membership and viewer-role governance with scope-aware authorization.
- Versioned Spec Kit lifecycle, policy/constitution activation, capability grants, exception decisioning and audit-chain verification.
- Paged, filtered REST resources with OpenAPI documentation, rate limiting, correlation IDs, structured logging, liveness/readiness and RFC 9457 error responses.
- Validation finding triage and evidence-retention lifecycle, including audit events and database integrity constraints.
- Server-rendered administrative workflows with CSRF protection, responsive pagination, evidence drill-down, accessible no-JavaScript fallback and React Islands for quality/traceability/evidence exploration.
- Deterministic Go CLI configuration, client-credential login, `status`, JUnit/SARIF output, bounded resilient sync and additional validation rules.
- Hardened container topology, Keycloak gateway headers, production compose profile, CI smoke topology and GitHub Actions build/security/release workflows.

### Security

- Enforced non-root runtime images, read-only container filesystems, dropped Linux capabilities and bounded writable temporary storage.
- Enforced role and project-membership checks, CSRF, CSP/HSTS, CORS allowlists, API throttling and PostgreSQL-backed append-only audit records.

### Verification

- Maven reactor verification, portal/React production build, Go format/test/build and static production-topology checks pass in the development environment.
- The Docker Compose smoke test is executed by GitHub Actions because this sandbox does not include a Docker daemon or CLI.
