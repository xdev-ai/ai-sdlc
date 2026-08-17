# Verified Portal Screenshots

## Capture policy

The screenshot set contains only **real runtime evidence**. It never uses mock UI, fabricated data, or a simulated authentication state. Public surfaces can be rendered by the release-verification environment. Data-bearing screens under `/app/*` require a running Keycloak realm, a valid OIDC user session with the appropriate role, and the management-server API; they are not captured until that authenticated environment is available.

| Surface | Evidence | Verification state |
| --- | --- | --- |
| Public English portal landing | `portal-landing-en.png` | Captured from the live SSR portal at `http://127.0.0.1:8080/` with Chromium headless at a 1440×1100 viewport during release verification. |
| Public Keycloak session-recovery screen | `portal-session-expired-en.png` | Captured from the live SSR portal at `http://127.0.0.1:18080/session-expired` with Chromium headless at a 1440×1100 viewport. The page contains no token, user identity, draft, or identity-provider diagnostic data. |
| Control-plane workspace | Automated module/API/Docker Compose verification | Requires Keycloak authentication and a management-server session. |
| Setup checklist on the overview | Rendered and asserted by `SetupChecklistRenderTest` | Requires Keycloak authentication to capture. The checklist state comes from live control-plane data, so a capture without a session would show an empty, misleading page. |
| Documentation workspace (`/app/knowledge`) | Rendered and asserted by `KnowledgeWorkspaceRenderTest`, including the escaping of hostile page bodies | Requires Keycloak authentication and at least one imported space. |
| Risk Intelligence Cockpit | Automated React-island and module verification | Requires Keycloak authentication and project-scoped data. |
| Policy-as-Code workspace | Automated policy-engine and module verification | Requires Keycloak authentication and project-scoped policy data. |
| Agent Governance workspace | Automated governance-service and module verification | Requires Keycloak authentication and project-scoped agent data. |
| Notification center and approval queue | Automated orchestration and module verification | Requires Keycloak authentication and tenant workflow data. |
| Tenant administration | Automated federation/SCIM/e-discovery module verification | Requires Keycloak authentication and an administrative role. |
| Supply-chain workspace | Automated SBOM/provenance/security-pipeline verification | Requires Keycloak authentication and project evidence. |

## `portal-landing-en.png`

The landing capture verifies the public user-facing controls: governance positioning, the deterministic execution / human authority / immutable evidence model, locale switcher, and control-plane entry point. Full implementation and integration evidence for authenticated features is recorded in [`integrations-and-sdks.md`](../integrations-and-sdks.md#module-usage-and-verification-guide) and [`verification.md`](../verification.md).
