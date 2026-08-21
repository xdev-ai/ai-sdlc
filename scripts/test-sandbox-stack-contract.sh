#!/usr/bin/env bash
set -euo pipefail

for script in scripts/run-sandbox-stack.sh scripts/verify-sandbox-stack.sh; do
  test -x "$script" || { echo "$script must be executable" >&2; exit 1; }
  bash -n "$script"
done

grep -q 'AISDLC_IDENTITY_BIND_ADDRESS:-127.0.0.1' docker-compose.yml
grep -q 'AISDLC_PORTAL_BIND_ADDRESS:-127.0.0.1' docker-compose.yml
grep -q 'docker compose --project-name' scripts/run-sandbox-stack.sh
grep -q 'never creates, reads, or commits an environment file' scripts/run-sandbox-stack.sh
grep -q 'kc-form-login' scripts/verify-sandbox-stack.sh
grep -q 'http://127.0.0.1:8080/login/oauth2/code/keycloak' infra/keycloak/ai-sdlc-realm.json

echo 'Sandbox Compose launcher contract passed.'
