#!/usr/bin/env bash
set -euo pipefail

# Ephemeral credentials are restricted to the disposable CI topology. Never reuse them in production.
export POSTGRES_USER="aisdlc_ci"
export POSTGRES_PASSWORD="aisdlc_ci_ephemeral_password"
export POSTGRES_DB="aisdlc"
export KEYCLOAK_ADMIN="admin"
export KEYCLOAK_ADMIN_PASSWORD="aisdlc_ci_ephemeral_admin"
export PORTAL_CLIENT_SECRET="ci-placeholder-not-a-production-secret"
# The realm import binds these; without them Keycloak generates random secrets and nothing can authenticate.
export CLI_CLIENT_SECRET="ci-placeholder-cli-not-a-production-secret"
export AGENT_RUNTIME_CLIENT_SECRET="ci-placeholder-agent-not-a-production-secret"
export LOCAL_ADMIN_PASSWORD="aisdlc_ci_ephemeral_portal_admin"
export AISDLC_GITHUB_WEBHOOK_SECRET="aisdlc_ci_ephemeral_webhook_secret"
export AISDLC_EVIDENCE_S3_ACCESS_KEY="aisdlc_ci_minio"
export AISDLC_EVIDENCE_S3_SECRET_KEY="aisdlc_ci_minio_ephemeral_password"
export AISDLC_EVIDENCE_S3_BUCKET="aisdlc-evidence-ci"

compose=(docker compose -p aisdlc-ci -f docker-compose.yml -f docker-compose.integration.yml)
cleanup() {
  "${compose[@]}" logs --no-color > target-compose-integration.log 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans || true
}
trap cleanup EXIT

timeout --foreground 420s "${compose[@]}" up --build --wait --wait-timeout 240

curl --fail --silent --show-error --connect-timeout 5 --max-time 10 --retry 12 --retry-all-errors --retry-delay 2 \
  http://localhost:8180/realms/ai-sdlc/.well-known/openid-configuration >/dev/null
curl --fail --silent --show-error --connect-timeout 5 --max-time 10 --retry 12 --retry-all-errors --retry-delay 2 \
  http://localhost:18081/actuator/health/readiness >/dev/null
curl --fail --silent --show-error --connect-timeout 5 --max-time 10 --retry 12 --retry-all-errors --retry-delay 2 \
  http://localhost:19000/minio/health/ready >/dev/null
curl --fail --silent --show-error --connect-timeout 5 --max-time 10 --retry 12 --retry-all-errors --retry-delay 2 \
  http://localhost:8080/ >/dev/null

echo "Compose integration smoke test passed: identity, object storage, readiness and SSR landing are reachable."

# Reachable health surfaces are not a working platform. Drive one project through the governed flow and verify the
# audit chain over it before the topology is torn down.
AISDLC_ACCEPTANCE_NETWORK="aisdlc-ci_platform" \
AISDLC_ACCEPTANCE_KEYCLOAK_URL="http://localhost:8180" \
  bash "$(dirname -- "$0")/end-to-end-acceptance.sh"
