# SSR Portal Authentication Experience

## Purpose

The SSR portal keeps its Keycloak integration explicit without revealing identity-provider internals. The authenticated workspace exposes a compact **Keycloak session connected** status indicator. This indicator means that the current server-rendered request has an OAuth2 access token available for the portal's server-side management API client. It does not report token lifetime, realm topology, client configuration, claims, or any credential material.

> The browser never receives a management API access token. The portal forwards the access token only in server-to-server requests to the management control plane.

## Session-expiry and identity-provider failure flow

| Trigger | Portal behavior | User-facing result |
| --- | --- | --- |
| An unauthenticated request targets `/app/**` | Spring Security saves the eligible request and sends the browser to `/session-expired`. | A neutral recovery screen offers **Sign in again** and **Return to home**. |
| Keycloak authorization-code login fails | OAuth2 login sends the browser to `/session-expired`. | The recovery screen does not expose the provider error, authorization code, state parameter, or client information. |
| Management control plane returns HTTP `401` during an SSR read | `ManagementApiClient` classifies the response as `AUTHENTICATION_REQUIRED`; the controller redirects centrally to `/session-expired`. | The user sees one consistent recovery action rather than a raw API status message. |
| Management control plane returns HTTP `401` during a SSR mutation or evidence download | The common mutation/download helper redirects centrally to `/session-expired`. | No success flash message is shown for an operation whose authorization could not be confirmed. |
| Management control plane returns HTTP `403` | The portal returns a generic project-access message. | The user is not signed out, because an access denial is distinct from an expired session. |
| Network or non-authentication upstream fault | The portal reports a generic temporary control-plane error. | Provider and transport diagnostics remain server-side. |

After a successful reauthentication, Spring Security uses a safe saved request when one exists; otherwise it sends the user to `/app`. The portal does not accept a caller-provided arbitrary redirect target, preventing an open-redirect recovery path.

## Security and privacy controls

The recovery page is intentionally public so that an expired or absent browser session can reach it. It contains only static instructional content and links to the registered OAuth2 authorization endpoint. It does not contain a user name, prior workspace data, saved form content, access token, refresh token, Keycloak realm URL, client identifier, authorization-state value, or server error trace.

Sign-out is now submitted as a CSRF-protected `POST /logout` form rather than a state-changing link. The portal retains its existing browser security headers and keeps `/app/**` authenticated. Localized English and Vietnamese copy is supplied through the existing cookie-backed locale mechanism.

## Operations and verification

An operator should first verify that the portal can reach Keycloak and the management server using the deployment's normal readiness checks. The UI's connection indicator is not a health probe and must not be used as one. For an incident that affects Keycloak availability, users should be directed to the neutral recovery page; detailed diagnostics belong in protected server logs and observability systems.

The regression suite covers the session-recovery entry point, OAuth2 failure route, secure logout form, classification of HTTP `401` versus `403`, and SSR rendering of the connection indicator. A real runtime capture of the public recovery page is stored as [`docs/screenshots/portal-session-expired-en.png`](screenshots/portal-session-expired-en.png).
