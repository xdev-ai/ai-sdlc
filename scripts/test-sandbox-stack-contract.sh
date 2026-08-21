#!/usr/bin/env bash
set -euo pipefail

for script in scripts/run-sandbox-stack.sh scripts/verify-sandbox-stack.sh scripts/test-sandbox-oidc-playwright.sh; do
  test -x "$script" || { echo "$script must be executable" >&2; exit 1; }
  bash -n "$script"
done

grep -q 'AISDLC_IDENTITY_BIND_ADDRESS:-127.0.0.1' docker-compose.yml
grep -q 'AISDLC_PORTAL_BIND_ADDRESS:-127.0.0.1' docker-compose.yml
grep -q 'AISDLC_IDENTITY_BIND_ADDRESS="0.0.0.0"' scripts/integration-smoke.sh
grep -q 'AISDLC_PORTAL_BIND_ADDRESS="0.0.0.0"' scripts/integration-smoke.sh
grep -q 'docker compose --project-name' scripts/run-sandbox-stack.sh
grep -q 'never creates, reads, or commits an environment file' scripts/run-sandbox-stack.sh
grep -q 'playwright)' scripts/run-sandbox-stack.sh
grep -q 'kc-form-login' scripts/verify-sandbox-stack.sh
grep -q 'http://127.0.0.1:8080/login/oauth2/code/keycloak' infra/keycloak/ai-sdlc-realm.json
grep -q 'test-sandbox-oidc-playwright.sh' scripts/integration-smoke.sh
grep -q "screenshot: 'off'" tests/playwright/playwright.config.mjs
grep -q "trace: 'off'" tests/playwright/playwright.config.mjs

echo 'Sandbox Compose launcher contract passed.'
