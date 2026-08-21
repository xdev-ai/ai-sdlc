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
# 32 bytes, base64url. Without it every notification-channel create returns 409 and the feature is untestable —
# which is how it reached main unnoticed in the first place.
export AISDLC_NOTIFICATION_ENCRYPTION_KEY="YWlzZGxjLWNpLWVwaGVtZXJhbC1ub3RpZmljYXRpb24"
export AISDLC_EVIDENCE_S3_ACCESS_KEY="aisdlc_ci_minio"
export AISDLC_EVIDENCE_S3_SECRET_KEY="aisdlc_ci_minio_ephemeral_password"
export AISDLC_EVIDENCE_S3_BUCKET="aisdlc-evidence-ci"
# The disposable GitHub runner probes host-published health surfaces. Compose's local/sandbox
# default remains 127.0.0.1; CI explicitly uses an isolated runner network rather than inheriting
# a host-restricted mapping that made the SSR health surface unreachable in the smoke job.
export AISDLC_IDENTITY_BIND_ADDRESS="0.0.0.0"
export AISDLC_PORTAL_BIND_ADDRESS="0.0.0.0"

compose=(docker compose -p aisdlc-ci -f docker-compose.yml -f docker-compose.integration.yml)
cleanup() {
  "${compose[@]}" logs --no-color > target-compose-integration.log 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans || true
}
trap cleanup EXIT

# GNU coreutils `timeout` is absent on macOS, where the whole script previously died at this line with
# "timeout: command not found" — before starting anything, so a developer could not run the integration suite
# locally at all. Homebrew installs it as `gtimeout`. If neither exists the run still proceeds: compose's own
# `--wait-timeout 240` bounds the wait, and an unbounded outer guard is a weaker failure than no local run.
# 420s was below the real cold-build time and the job started failing with exit 124 on a build that was working: one
# Maven step alone took 339s. This guard exists to stop a hung startup from occupying a runner for six hours, not to
# enforce a build budget, so it is set with headroom rather than close to the measured time. The cause of that 339s is
# fixed separately — both Dockerfiles now carry the Maven cache mount on the package step, not only on the prefetch.
bound_startup=(timeout --foreground 900s)
if ! command -v timeout >/dev/null 2>&1; then
  if command -v gtimeout >/dev/null 2>&1; then
    bound_startup=(gtimeout --foreground 900s)
  else
    echo "warning: neither timeout nor gtimeout found; relying on compose --wait-timeout 240 alone" >&2
    bound_startup=()
  fi
fi

# The ${array[@]+"${array[@]}"} form is required: macOS ships bash 3.2, where `set -u` treats an empty array as an
# unbound variable and the script dies on the fallback path it was written to support.
${bound_startup[@]+"${bound_startup[@]}"} "${compose[@]}" up --build --wait --wait-timeout 240

curl --fail --silent --show-error --connect-timeout 5 --max-time 10 --retry 12 --retry-all-errors --retry-delay 2 \
  http://localhost:8180/realms/ai-sdlc/.well-known/openid-configuration >/dev/null
# Readiness is read from inside the container, not through a published port.
#
# This used to curl http://localhost:18081, which required the integration overlay to publish the management API to
# the host — in the same run whose step 10 asserts that the API must never be on the host. The contradiction went
# unnoticed only because that assertion probed port 8081 while the exposure was on 18081, so it reported "not
# published" about an API that was published. Removing the need for the port removes the contradiction, and now the
# suite's own topology satisfies the property it checks.
"${compose[@]}" exec -T management-server sh -lc \
  'for attempt in $(seq 1 24); do wget -qO- http://localhost:8081/actuator/health/readiness 2>/dev/null | grep -q UP && exit 0; sleep 2; done; exit 1' \
  >/dev/null
curl --fail --silent --show-error --connect-timeout 5 --max-time 10 --retry 12 --retry-all-errors --retry-delay 2 \
  http://localhost:19000/minio/health/ready >/dev/null
curl --fail --silent --show-error --connect-timeout 5 --max-time 10 --retry 12 --retry-all-errors --retry-delay 2 \
  http://localhost:8080/ >/dev/null

echo "Compose integration smoke test passed: identity, object storage, readiness and SSR landing are reachable."

# Drive the browser-visible OIDC code flow, not merely the discovery document and redirect target.
# The runner is short-lived, host-networked, and configured with no trace/screenshot/video output so
# the ephemeral Keycloak password never becomes a CI artifact or log entry.
AISDLC_PORTAL_BASE_URL="http://localhost:8080" \
AISDLC_KEYCLOAK_BASE_URL="http://auth.localhost:8180" \
  bash "$(dirname -- "$0")/test-sandbox-oidc-playwright.sh"

# Reachable health surfaces are not a working platform. Drive one project through the governed flow and verify the
# audit chain over it before the topology is torn down.
AISDLC_ACCEPTANCE_NETWORK="aisdlc-ci_platform" \
AISDLC_ACCEPTANCE_KEYCLOAK_URL="http://localhost:8180" \
  bash "$(dirname -- "$0")/end-to-end-acceptance.sh"

# The governed spine is not the whole platform. Sweep the features the acceptance run does not touch — evidence
# upload into real object storage, risk intelligence, notification channels, the cost ledger, the runtime AI
# boundary, and the paging contract. Five defects reached main because nothing exercised these against a live
# stack: two Instant parameters the driver cannot bind, an int overflow in the paging envelope, an internal error
# surfacing as 403 insufficient_scope, and an encryption key that never reached the container.
AISDLC_ACCEPTANCE_NETWORK="aisdlc-ci_platform" \
AISDLC_ACCEPTANCE_KEYCLOAK_URL="http://localhost:8180" \
  bash "$(dirname -- "$0")/feature-sweep.sh"

# The knowledge base grounds AI answers, so its retrieval behaviour is a correctness concern, not a convenience.
# This checks the properties no unit test can reach: that accent-folded search finds Vietnamese content typed without
# diacritics, that wording a later version removed stops being retrievable, and that a page's history survives an
# edit. The first run of it found a query referencing a column that does not exist — after the code compiled and the
# schema had been verified directly against PostgreSQL.
AISDLC_ACCEPTANCE_NETWORK="aisdlc-ci_platform" \
AISDLC_ACCEPTANCE_KEYCLOAK_URL="http://localhost:8180" \
  bash "$(dirname -- "$0")/knowledge-sweep.sh"

# Agent governance had no live coverage at all: agent-evidence-acceptance.sh covers it, but only by driving a plugin
# that lives outside this repository and is not published anywhere CI can reach, so it never ran here. Unit tests never
# see the JSON the controller accepts — which is how a service-side digest validator came to reject the uppercase hex
# the controller's own @Pattern allows, telling callers a valid SHA-256 was invalid.
AISDLC_ACCEPTANCE_NETWORK="aisdlc-ci_platform" \
AISDLC_ACCEPTANCE_KEYCLOAK_URL="http://localhost:8180" \
  bash "$(dirname -- "$0")/agent-governance-sweep.sh"

# The plugin-side half of agent governance — digest computation, the spool file, and what the forwarder does when the
# control plane rejects its credential — can only be exercised by the harness evidence-forwarder plugin, which is not
# in this repository. Set AISDLC_EVIDENCE_LIB_DIR to a built plugin directory (index.js, digest.js, live.mjs, and a
# node_modules carrying @deepseek-ai/schemastery) and it runs here, inside the same topology.
#
# When it is unset the run says so out loud instead of passing quietly. That matters because the two suites cover
# different things: agent-governance-sweep.sh above covers the API contract and always runs, so an absent plugin leaves
# the plugin's own behaviour uncovered — not the control plane's.
if [ -n "${AISDLC_EVIDENCE_LIB_DIR:-}" ]; then
  AISDLC_ACCEPTANCE_NETWORK="aisdlc-ci_platform" \
  AISDLC_ACCEPTANCE_KEYCLOAK_URL="http://localhost:8180" \
    bash "$(dirname -- "$0")/agent-evidence-acceptance.sh"
else
  echo "note: AISDLC_EVIDENCE_LIB_DIR is unset, so the evidence-forwarder plugin suite did not run." >&2
  echo "      The agent-governance API contract IS covered above by agent-governance-sweep.sh; what is not covered" >&2
  echo "      is the plugin's own digest, spool and rejection behaviour. See scripts/agent-evidence-acceptance.sh." >&2
fi
