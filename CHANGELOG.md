# Changelog

All notable changes to the AI-SDLC platform are documented in this file. The repository is currently pre-release; version identifiers become immutable only through the tag-triggered GitHub release workflow.

## [Unreleased]

### Added

- Evidence & Governance Data Repository with project-scoped `evidence_assets` metadata, SHA-256 provenance, content classification, bounded multipart upload, pagination, soft delete, optional validation-evidence linkage and private presigned downloads.
- S3-compatible `ObjectStoragePort` and AWS SDK for Java 2.x adapter, with MinIO Object Lock topology, deterministic bucket bootstrap, retention extension controls and compensating cleanup on metadata rollback.
- Upload idempotency at the database boundary, audit-backed evidence lifecycle events, SSR portal workflows, and `aisdlc upload` with streaming multipart, digest verification, deterministic retry key and bounded retry/backoff.
- Module integration guide, storage configuration/backup guidance, API documentation and unit tests for evidence service, controller and CLI transport.
- English/Vietnamese portal localization with an English default, persisted preference, allowed-locale validation, safe fallback, SSR coverage and React Islands locale synchronization.

### Security

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
