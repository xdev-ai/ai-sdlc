#!/usr/bin/env bash
# Launch the complete disposable AI-SDLC sandbox stack. This script intentionally accepts secrets
# only from the invoking shell; it never creates, reads, or commits an environment file.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ACTION="${1:-up}"
PROJECT="${AISDLC_SANDBOX_PROJECT:-aisdlc-sandbox}"
export POSTGRES_USER="${POSTGRES_USER:-aisdlc_sandbox}"
export POSTGRES_DB="${POSTGRES_DB:-aisdlc}"
export KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
export AISDLC_EVIDENCE_S3_BUCKET="${AISDLC_EVIDENCE_S3_BUCKET:-aisdlc-evidence-sandbox}"

compose=(docker compose --project-name "$PROJECT" -f docker-compose.yml)

usage() {
  cat <<'EOF'
Usage: scripts/run-sandbox-stack.sh [up|verify|playwright|status|logs|down|reset]

Run `up` with the required disposable secrets exported in the current shell. See
docs/sandbox-compose-oidc-login.md for a copy-safe setup sequence. `reset` removes only volumes
owned by the selected AISDLC_SANDBOX_PROJECT, so it irreversibly removes sandbox test data.
EOF
}

require_runtime() {
  command -v docker >/dev/null 2>&1 || { echo "Docker CLI is required; this default sandbox does not provide it." >&2; exit 127; }
  docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required." >&2; exit 127; }
}

require_secrets() {
  local required=(POSTGRES_PASSWORD KEYCLOAK_ADMIN_PASSWORD PORTAL_CLIENT_SECRET CLI_CLIENT_SECRET AGENT_RUNTIME_CLIENT_SECRET LOCAL_ADMIN_PASSWORD AISDLC_GITHUB_WEBHOOK_SECRET AISDLC_NOTIFICATION_ENCRYPTION_KEY AISDLC_EVIDENCE_S3_ACCESS_KEY AISDLC_EVIDENCE_S3_SECRET_KEY)
  local missing=()
  local name
  for name in "${required[@]}"; do [[ -n "${!name:-}" ]] || missing+=("$name"); done
  if ((${#missing[@]})); then
    printf 'Missing required sandbox secrets: %s\n' "${missing[*]}" >&2
    printf 'Do not create a tracked .env file. Follow docs/sandbox-compose-oidc-login.md.\n' >&2
    exit 2
  fi
}

case "$ACTION" in
  up)
    require_runtime; require_secrets
    "${compose[@]}" config -q
    "${compose[@]}" up --build --wait --wait-timeout 300
    "$ROOT/scripts/verify-sandbox-stack.sh" --project "$PROJECT"
    ;;
  verify)
    require_runtime; require_secrets
    "$ROOT/scripts/verify-sandbox-stack.sh" --project "$PROJECT"
    ;;
  playwright)
    require_runtime; require_secrets
    "$ROOT/scripts/test-sandbox-oidc-playwright.sh"
    ;;
  status)
    require_runtime
    "${compose[@]}" ps
    ;;
  logs)
    require_runtime
    if [[ -n "${2:-}" ]]; then
      "${compose[@]}" logs --tail 200 "$2"
    else
      "${compose[@]}" logs --tail 200
    fi
    ;;
  down)
    require_runtime
    "${compose[@]}" down --remove-orphans
    ;;
  reset)
    require_runtime
    printf 'Removing disposable sandbox containers and volumes for project %s.\n' "$PROJECT" >&2
    "${compose[@]}" down --volumes --remove-orphans
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage >&2; exit 64
    ;;
esac
