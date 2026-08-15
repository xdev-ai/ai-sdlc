# Portal Workflows

The AI-SDLC portal is a Spring MVC and Thymeleaf application. It is deliberately **server-rendered first**: JavaScript enhances charts, graph exploration and dense evidence views, but governance writes, pagination, read views and human decisions work as ordinary CSRF-protected HTML forms.

## Context and Authorization

Select an organization and, optionally, a project from the persistent context bar. The portal forwards the authenticated Keycloak access token only from the server to the management API. Access tokens are never included in HTML, browser storage or JavaScript props.

Every form result redirects to the relevant scoped page and uses a flash notification. API validation, authorization and conflict errors are rendered as actionable messages rather than being silently discarded.

| Portal workspace | Supported workflow |
|---|---|
| Projects | Create an organization, create a project, invite members, change project role and remove a membership. The server prevents removal or demotion of the final owner. |
| Spec Kits | Register immutable versions, inspect lifecycle, pin by precedence, unpin a resolved assignment and deprecate an active kit with a reason. |
| Governance | Record versioned policies and constitutions, activate/deactivate lifecycle versions, submit exception requests, and create expiring scoped capability grants. |
| Validations | Filter and page validation runs; select a run to inspect deterministic findings and evidence digests/verified URIs. |
| Evidence Repository | Upload a bounded project-scoped artefact with type/access classification, list its SHA-256 provenance, open an authorized short-lived download, extend a retention lock, or soft-delete metadata when holding owner/reviewer authority. The portal sends multipart through its server-side API client; object-store credentials and bearer tokens never reach the browser. |
| Traceability | Inspect the requirement-to-evidence graph in both a no-JavaScript summary and enhanced interactive explorer. |
| Reviews | Submit merge-request, phase-gate or exception review requests; reviewers provide a human final approve/reject decision and rationale. |
| Quality | Record a real metric period in UTC and inspect DORA counter-metrics in tables or an enhanced analytics island. |
| Audit | Filter and page immutable events, then read the hash-chain verification state for the organization. |

## Human Decision Guardrails

Review and exception records are only opened from explicit server-side forms. A pending item can be decided once by an authorized human; the management API rejects stale or duplicate finalizations. Exception approval requires a dated expiry, and each successful mutation writes an immutable audit event.

## Progressive Enhancement and Accessibility

The portal uses semantic tables, labels, visible keyboard focus, `aria-live` for timely page updates and server-side empty/error states. The React Islands are loaded from a locally built Vite asset manifest and consume serialized read models only. The SSR tables remain available for assistive technology, CSP-restricted deployments and JavaScript-disabled browsers.

## Metric Data Contract

The quality workspace stores immutable period snapshots rather than browser-calculated scores. All timestamps must be ISO-8601 UTC instants. The following optional numeric fields form the snapshot: deployment frequency, lead time in hours, change failure rate, PR review-time delta in hours, rework rate, review queue health and specification alignment score. Metric producers should supply the calculation provenance in the API-side ingestion integration before promoting data to organizational reporting.
