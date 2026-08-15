# Control Plane API

The management server exposes a versioned REST control plane at `/api/v1`. All non-health endpoints require a Keycloak-issued JWT. The API never decides a review on behalf of an AI model: review and exception decisions are accepted only when a human principal holding the appropriate role sends the mutation.

Interactive OpenAPI documentation is available at `/swagger-ui.html` to the `admin` role. The raw specification is at `/v3/api-docs` and is also protected by that role.

## Authorization Model

| Role | Primary responsibilities |
|---|---|
| `admin` | Creates organizations and projects; administers membership, registry lifecycle, policies, constitutions, capabilities, metrics, API documentation and audit verification. |
| `developer` | Creates validation evidence, trace nodes/edges, exception requests and review requests within projects where they have membership. |
| `reviewer` | Views project governance/evidence and makes final human review or exception decisions. |
| `viewer` | Reads project-scoped governance, quality, traceability and evidence data only. |

Project-scoped reads also require a `project_memberships` record. Membership checks are enforced inside the service boundary; controller role checks do not replace project scope validation.

## Pagination and Errors

Collection endpoints use `page` (zero-based) and `size` (1–100). Supported endpoint-specific sort fields are allow-listed by the server. Paged responses follow this envelope:

```json
{
  "items": [],
  "page": 0,
  "size": 25,
  "totalItems": 0,
  "totalPages": 0
}
```

Invalid input produces an RFC 9457 `application/problem+json` response. Conditional governance updates return `409 Conflict` when a decision was already taken, a record was unpinned, or a lifecycle precondition is no longer true.

## High-Value Workflow Endpoints

| Workflow | Resource path | Notes |
|---|---|---|
| Organization/project/membership administration | `/organizations`, `/organizations/{id}/projects`, `/projects/{id}/memberships` | The final project owner cannot be demoted or deleted. |
| Spec Kit registry | `/organizations/{id}/spec-kits`, `/projects/{id}/spec-kits` | Only an active kit in the same organization can be pinned; duplicate assignments are rejected at database level. |
| Policy and constitution lifecycle | `/organizations/{id}/policies`, `/organizations/{id}/constitutions` | Activation/deactivation transitions include audit entries and lifecycle attribution. |
| Exceptions and reviews | `/projects/{id}/exception-requests`, `/projects/{id}/review-items` | Decisions are final and require an expected `PENDING` state. Approved exceptions must include a future expiry. |
| Validation evidence | `/cli/projects/{id}/validation-runs`, `/projects/{id}/validation-runs` | CLI ingest requires `Idempotency-Key`, a non-empty model pin and `bare=false`. |
| Audit verification | `/organizations/{id}/audit-events/verify` | Recomputes the append-only ledger hash chain without modifying historical events. |

## Runtime Protection and Observability

The API emits a correlation ID on requests and structured logs. A token-bucket limiter protects `/api/**`; its default capacity is 120 requests per minute per source address. Before horizontal scaling, configure a distributed Bucket4j backend so limits remain cluster-wide.

Health endpoints expose liveness and readiness groups under `/actuator/health`. Readiness includes the database and the custom audit-ledger indicator. Set `AISDLC_ALLOWED_ORIGINS` to explicit production portal origins; wildcard origins are deliberately unsupported.
