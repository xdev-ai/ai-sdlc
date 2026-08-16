# Enterprise Multi-Tenancy and Identity Integration

## Design Sources

The implementation follows the SCIM protocol and core schema defined by IETF RFC 7644 and RFC 7643. SCIM is an HTTP protocol for provisioning and managing cross-domain identity resources such as users and groups; RFC 7644 specifies resource endpoints, retrieval, mutation, errors, resource versioning, service-provider configuration, multi-tenancy, TLS, token, and privacy considerations. [1] [2]

Keycloak 26.7.1 supports OpenID Connect, OAuth 2.0, SAML, identity brokering with external OIDC or SAML identity providers, groups, composite roles, and token/claim protocol mappers. The platform therefore stores tenant-specific federation intent and permission mappings, while a Keycloak administrator applies confidential provider credentials and activates the corresponding broker configuration in the identity plane. [3]

## Boundary Model

An AI-SDLC tenant is an enterprise data and governance boundary. A tenant has an immutable external key, display name, lifecycle status, residency code, encryption-key reference, and a legal-hold state. Tenant scope is enforced at the application service boundary and attached to organization/project resources. A tenant must not be inferred from user-controlled request data.

The management server never persists a private key, SAML signing key, raw SCIM bearer token, or raw evidence export secret. SCIM bearer tokens are generated once and stored only as SHA-256 hashes. When a tenant administrator elects to retain an OIDC client secret for a declared federation record, the server encrypts it with the existing AES-256-GCM deployment encryption boundary and never returns it through the API. Key material, signing keys, and Keycloak broker activation remain deployment/identity-plane responsibilities.

## Identity Contracts

The initial SCIM surface is tenant-scoped at `/scim/v2/tenants/{tenantId}/Users`, where `tenantId` is a platform UUID. It requires a tenant-bound provisioning principal whose raw bearer token matches a stored SHA-256 hash. The server supports SCIM `Users` list and create/upsert, with `application/scim+json` envelopes and core User schema URN. Group provisioning, ServiceProviderConfig discovery, bulk operations, password attributes, PATCH, and delete/deactivate operations are intentionally not exposed until their authorization and reconciliation contracts are separately implemented.

SCIM responses use `application/scim+json`, core schema URNs, and list pagination envelopes. Externally supplied subject identifiers are idempotent only inside their tenant. Every mutation emits a tenant-scoped immutable audit event. Clients must use HTTPS and retain the one-time provisioning token in an external secret manager.

Tenant federation configurations support OIDC and SAML metadata declarations. An active federation configuration is a policy record, not an automatic credential rotation or Keycloak mutation. The documented administration runbook requires issuer/entity-ID verification, HTTPS metadata retrieval, certificate pin/fingerprint review, claim-to-subject mapping, approved domain restrictions, explicit group mapping, and a tested break-glass local administrator path before activation.

## Authorization and Legal Hold

Custom permission sets are additive metadata for tenant members and mapped IdP groups. Built-in platform RBAC remains the enforcement authority for current project APIs; future endpoints must explicitly consult mapped tenant permissions before treating a declared permission as an authorization grant. Tenant-admin authority is required for role configuration, federation policy, provisioning, legal hold, and e-discovery export.

Legal hold is a tenant-scoped, auditable control that prevents its own release by an unauthorized actor and records the tenant's active hold state. E-discovery export is permission-gated and writes a JSON manifest to object storage with a SHA-256 digest, a one-year compliance retention lock, and a short-lived presigned download URL. The manifest contains bounded tenant audit and organization audit chain metadata, never secrets, access tokens, client secrets, raw notification destinations, or object bytes.

## Operational API Summary

| Capability | API path | Required tenant role |
|---|---|---|
| Tenant bootstrap | `POST /api/v1/tenants` | Platform `admin`; creator becomes `TENANT_ADMIN`. |
| Membership and custom permission metadata | `/api/v1/tenants/{tenantId}/memberships`, `/permission-sets` | `TENANT_ADMIN`. |
| OIDC/SAML declaration | `/api/v1/tenants/{tenantId}/federation-configs` | `TENANT_ADMIN` or `IDENTITY_ADMIN`. |
| One-time SCIM credential | `POST /api/v1/tenants/{tenantId}/scim-service-principals` | `TENANT_ADMIN` or `IDENTITY_ADMIN`. |
| SCIM User list/upsert | `/scim/v2/tenants/{tenantId}/Users` | Valid tenant SCIM bearer token. |
| Legal hold | `/api/v1/tenants/{tenantId}/legal-holds` | `TENANT_ADMIN` or `COMPLIANCE_OFFICER`. |
| E-discovery manifest | `/api/v1/tenants/{tenantId}/e-discovery-exports` | `TENANT_ADMIN`, `COMPLIANCE_OFFICER`, or `AUDITOR`. |

## References

[1] [RFC 7644: System for Cross-domain Identity Management Protocol](https://datatracker.ietf.org/doc/html/rfc7644)

[2] [RFC 7643: System for Cross-domain Identity Management Core Schema](https://datatracker.ietf.org/doc/html/rfc7643)

[3] [Keycloak 26.7.1 Server Administration Guide](https://www.keycloak.org/docs/26.7.1/server_admin/)
