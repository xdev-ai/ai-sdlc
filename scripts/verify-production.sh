#!/usr/bin/env bash
set -euo pipefail

require() {
  local file="$1" expression="$2" description="$3"
  if ! grep -Eq "$expression" "$file"; then
    echo "production verification failed: $description" >&2
    exit 1
  fi
}

for dockerfile in management-server/Dockerfile portal/Dockerfile; do
  require "$dockerfile" '^USER 10001:10001$' "$dockerfile must run as the non-root AI-SDLC user"
  require "$dockerfile" '^ENV JAVA_TOOL_OPTIONS=' "$dockerfile must define JVM memory and temporary-directory guardrails"
  require "$dockerfile" 'COPY --chown=aisdlc:aisdlc' "$dockerfile must transfer the application artifact with non-root ownership"
done

require docker-compose.yml 'read_only: true' 'application topology must mount runtime filesystems read-only'
require docker-compose.yml 'no-new-privileges:true' 'application topology must block privilege escalation'
require infra/nginx/keycloak.conf 'X-Content-Type-Options' 'identity gateway must emit content-type protection'
require infra/nginx/keycloak.conf 'Content-Security-Policy' 'identity gateway must restrict framing'
require infra/nginx/keycloak.conf 'limit_req zone=identity_per_ip' 'identity gateway must rate-limit requests'

echo 'Production topology static verification passed.'
