#!/usr/bin/env bash
# Execute browser-only OIDC checks against an already running disposable sandbox stack. Credentials
# are passed only to the short-lived container and no trace, screenshot, video, or HTML report is kept.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PLAYWRIGHT_IMAGE="${AISDLC_PLAYWRIGHT_IMAGE:-mcr.microsoft.com/playwright:v1.62.1-jammy}"
PORTAL_PORT="${AISDLC_PORTAL_PORT:-8080}"
IDENTITY_PORT="${AISDLC_IDENTITY_PORT:-8180}"

command -v docker >/dev/null 2>&1 || { echo "Docker CLI is required for the Playwright sandbox test." >&2; exit 127; }
docker info >/dev/null 2>&1 || { echo "A reachable Docker daemon is required for the Playwright sandbox test." >&2; exit 127; }
[[ "$(uname -s)" == "Linux" ]] || { echo "The disposable Playwright runner uses Linux host networking; run it on a Linux Docker host." >&2; exit 64; }
[[ -n "${LOCAL_ADMIN_PASSWORD:-}" ]] || { echo "LOCAL_ADMIN_PASSWORD must be provided in the invoking shell." >&2; exit 2; }

# The portal and Keycloak intentionally bind to loopback by default. Host networking preserves the
# public browser URLs (`localhost` and `auth.localhost`) without exposing either service to the LAN.
export AISDLC_PORTAL_BASE_URL="${AISDLC_PORTAL_BASE_URL:-http://localhost:${PORTAL_PORT}}"
export AISDLC_KEYCLOAK_BASE_URL="${AISDLC_KEYCLOAK_BASE_URL:-http://auth.localhost:${IDENTITY_PORT}}"
export AISDLC_LOCAL_ADMIN_USERNAME="${AISDLC_LOCAL_ADMIN_USERNAME:-platform-admin}"

echo "Running disposable Playwright OIDC checks against local sandbox endpoints." >&2
docker run --rm --network host --add-host auth.localhost:127.0.0.1 \
  --env AISDLC_PORTAL_BASE_URL --env AISDLC_KEYCLOAK_BASE_URL --env AISDLC_LOCAL_ADMIN_USERNAME --env LOCAL_ADMIN_PASSWORD \
  --mount "type=bind,src=${ROOT}/tests/playwright,dst=/work,readonly" \
  --workdir /work "$PLAYWRIGHT_IMAGE" /bin/bash -lc '
    set -euo pipefail
    cp -a /work /tmp/aisdlc-playwright
    cd /tmp/aisdlc-playwright
    npm ci --ignore-scripts --no-audit --no-fund >/dev/null
    npx playwright test --config=playwright.config.mjs
  '
