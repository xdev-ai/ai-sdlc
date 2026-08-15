# Production Hardening Baseline

**Status:** Baseline fixed on `main` at commit `9c13fd2`.

This document records the implementation audit performed before the production-hardening workstream. It is intentionally concrete: each gap is tied to an existing platform boundary, and every later implementation must preserve the three platform invariants.

> **Platform invariants:** the validator never invokes AI; every validation run is model-pinned and rejects `--bare`; and a human makes every approval or rejection decision.

## Audit Summary

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

## Hardening Design Decisions

The management server remains the sole authorization and integrity boundary. Portal forms will submit through server-side OAuth2 clients and will never expose Keycloak access tokens to browser JavaScript. Browser-side enhancements are optional; the server-rendered workflow and CSRF protection remain functional without JavaScript.

The control plane will use stable, page-oriented API envelopes, bounded query parameters and field-level validation responses. Stateful governance transitions will be conditional updates that fail if a record is no longer in the expected state, protecting against duplicate approvals and concurrent edits. Every successful state mutation will append an audit event in the same transaction.

New schema support will be delivered only by forward Flyway migrations. Existing audit events remain immutable at the PostgreSQL layer; audit verification will recompute the stored hash chain without changing historical records.

## Definition of Done

The production-hardening release is complete only when all API mutations enforce role and scope checks, all list endpoints are paged or safely bounded, the SSR portal covers the administrative workflow, CLI output supports local and CI usage, audit integrity can be verified, and Java/Go/frontend builds plus relevant tests pass in clean environments. Documentation and GitHub automation are release deliverables, not post-release work.
