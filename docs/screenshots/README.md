# Verified Portal Screenshots

## `portal-landing-en.png`

Captured from the live SSR portal at `http://127.0.0.1:8080/` with Chromium headless at a 1440×1100 viewport during the release-verification run.

The screenshot verifies the public English landing surface and its completed user-facing controls: governance positioning, the deterministic execution / human authority / immutable evidence model, locale switcher, and control-plane entry point.

Authenticated `/app/*` feature screens require a running Keycloak realm, a valid OIDC user session, and the management-server API. No static or fabricated screenshots are included for those data-bearing workflows; their implementation is instead evidenced by the automated module, API, and Docker Compose verification recorded in `docs/module-usage-and-verification.md`.
