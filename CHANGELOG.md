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
- P3.1 OpenTelemetry agent packaging and governance instrumentation: a digest-pinned agent in both runtime images, an entrypoint that attaches it only when telemetry is explicitly enabled and fails startup rather than downgrading silently, and manual spans, duration histograms, and `aisdlc_sli_events_total` events for policy evaluation, approval orchestration, evidence writes, SCM ingestion, notification dispatch, the audit ledger, and the audit health dependency — all no-ops without an attached agent.
- P3.1 resilience fault-injection adapters and scenario tests: profile-isolated chaos seams for the policy engine, evidence storage, identity decoder, SCM ingress, notification dispatch, and runtime AI provider, each placed before its side effect; a fail-open trace-context filter that degrades observability without failing a valid request; and deterministic scenarios asserting no partial commit, no principal fallback, idempotent webhook replay, retryable notification delivery, and a fail-closed provider dispatch.
- P3.3 tool broker: tenant-scoped single-use tool capability grants bound to a canonical SHA-256 argument fingerprint, a one-time grant secret stored only as a digest, atomic single-redemption with replay/expiry/subject/argument-mismatch reason codes, receipt digests linking execution to authorization, triple-enforced approval linkage for high-impact tools, owner-only revocation of unredeemed grants, and digest-only persistence with no raw prompt, output, or argument retention.
- P3.3 runtime workload identity and provider-proxy rollout controls: an `agent_runtime` Keycloak realm role and service-account client, resource-server runtime/control-plane audience and authorized-party validation, rejection of tokens claiming both workload and human identity, human-role-only access to `/api/**`, a feature-gated internal `/internal/runtime-ai/**` provider-invocation endpoint bound to the validated token subject, and a read-only secret-manager mount resolver for provider credentials with the fail-closed resolver retained as the default.
- P3.1 Sprint 1 telemetry foundation: versioned `aisdlc.telemetry` configuration model that is disabled and exporterless by default, fail-closed startup validation of exporter endpoints and deployment environments, an allowlisted resource/span/metric attribute contract, W3C trace-context propagation that continues an acceptable inbound trace and creates a root only otherwise, deterministic ratio sampling, trace identifiers in JSON logs, and privacy/cardinality contract tests.

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
