#!/usr/bin/env bash
# Verify that the sandbox topology reaches the Keycloak login page without automating credentials.
# Completing the browser login is intentionally a human action; this preserves the platform invariant that
# decisions involving human authority are not impersonated by automation.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PROJECT="aisdlc-sandbox"
if [[ "${1:-}" == "--project" ]]; then PROJECT="${2:?project name required}"; shift 2; fi
[[ $# -eq 0 ]] || { echo "Usage: $0 [--project name]" >&2; exit 64; }

IDENTITY_PORT="${AISDLC_IDENTITY_PORT:-8180}"
PORTAL_PORT="${AISDLC_PORTAL_PORT:-8080}"
compose=(docker compose --project-name "$PROJECT" -f docker-compose.yml)

curl_common=(--fail --silent --show-error --connect-timeout 5 --max-time 20 --retry 8 --retry-all-errors --retry-delay 2)
curl "${curl_common[@]}" --resolve "auth.localhost:${IDENTITY_PORT}:127.0.0.1" \
  "http://auth.localhost:${IDENTITY_PORT}/realms/ai-sdlc/.well-known/openid-configuration" \
  | grep -q '"issuer":"http://auth.localhost:'
curl "${curl_common[@]}" "http://127.0.0.1:${PORTAL_PORT}/" | grep -q 'AI-SDLC'
"${compose[@]}" exec -T management-server sh -lc \
  'wget -qO- http://localhost:8081/actuator/health/readiness | grep -q UP'

# /app initiates authorization. A successful final Keycloak login form proves that browser-facing authorization,
# loopback hostname resolution, portal redirect URI and internal token/JWK endpoints are wired together.
login_page="$(mktemp)"
trap 'rm -f "$login_page"' EXIT
curl "${curl_common[@]}" -L --max-redirs 5 --resolve "auth.localhost:${IDENTITY_PORT}:127.0.0.1" \
  "http://127.0.0.1:${PORTAL_PORT}/app" -o "$login_page"
grep -Eq 'name="username"|id="kc-form-login"' "$login_page"

printf 'Sandbox stack verified. Complete the remaining interactive login at http://localhost:%s/app using platform-admin.\n' "$PORTAL_PORT"
