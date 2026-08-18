#!/usr/bin/env bash
set -euo pipefail

require() {
  local file="$1" expression="$2" description="$3"
  if ! grep -Eq -- "$expression" "$file"; then
    echo "production verification failed: $description" >&2
    exit 1
  fi
}

forbid() {
  local file="$1" expression="$2" description="$3"
  if grep -Eq -- "$expression" "$file"; then
    echo "production verification failed: $description" >&2
    exit 1
  fi
}

for dockerfile in management-server/Dockerfile portal/Dockerfile; do
  require "$dockerfile" '^USER 10001:10001$' "$dockerfile must run as the non-root AI-SDLC user"
  require "$dockerfile" '^ENV JAVA_TOOL_OPTIONS=' "$dockerfile must define JVM memory and temporary-directory guardrails"
  require "$dockerfile" 'COPY --chown=aisdlc:aisdlc' "$dockerfile must transfer the application artifact with non-root ownership"
done

require portal/Dockerfile 'COPY portal/frontend/package\.json portal/frontend/package-lock\.json portal/frontend/vite\.config\.mjs portal/frontend/' 'portal build must copy Vite package metadata before Maven invokes npm'
require portal/Dockerfile 'COPY portal/frontend/src portal/frontend/src' 'portal build must copy React source before Maven invokes Vite'

require docker-compose.yml 'read_only: true' 'application topology must mount runtime filesystems read-only'
require docker-compose.yml 'no-new-privileges:true' 'application topology must block privilege escalation'
require docker-compose.yml 'cap_add: \[CHOWN, SETUID, SETGID\]' 'identity gateway must retain only the capabilities required to initialize Nginx temporary paths and drop workers to the non-root user'
require infra/nginx/keycloak.conf 'X-Content-Type-Options' 'identity gateway must emit content-type protection'
require infra/nginx/keycloak.conf 'Content-Security-Policy' 'identity gateway must restrict framing'
require infra/nginx/keycloak.conf 'limit_req zone=identity_per_ip' 'identity gateway must rate-limit requests'
require infra/nginx/keycloak.conf 'client_body_temp_path /tmp/client_body_temp;' 'identity gateway must redirect request temp files to writable tmpfs'
require docker-compose.yml '/tmp:uid=101,gid=101,mode=1777' 'identity gateway must expose writable tmpfs for Nginx temporary paths'
require docker-compose.yml '/var/run:uid=0,gid=0,mode=0755' 'identity gateway must grant only the Nginx master process ownership of its PID directory'
require docker-compose.yml 'KC_HEALTH_ENABLED: "true"' 'Keycloak must expose private health endpoints'
require docker-compose.yml '/health/ready HTTP/1.0' 'Keycloak must expose a private readiness probe before dependent services start'
require docker-compose.yml 'condition: service_healthy' 'identity gateway and control plane must wait for dependent service readiness'
require infra/postgres/init-keycloak-db.sql '^CREATE DATABASE keycloak;$' 'PostgreSQL init must create the Keycloak database'
forbid infra/postgres/init-keycloak-db.sql 'GRANT .* TO aisdlc' 'PostgreSQL init must not assume a hard-coded database role'
# The property is that startup IS bounded, not that it is bounded at one particular number. Pinning the literal 420s
# meant raising the guard — after it began killing builds that were working — failed this check, which says nothing
# about production topology.
#
# Two assertions, not one, because the first alone does not detect removal: the script carries a `gtimeout` fallback for
# hosts without GNU coreutils, and a bare 'timeout --foreground [0-9]+s' pattern matches that fallback line as a
# substring. Deleting the real bound therefore left this check green — verified by deleting it. So the bound must be
# assigned, and it must be applied to the `compose up` that it exists to bound.
require scripts/integration-smoke.sh '^bound_startup=\(timeout --foreground [0-9]+s\)$' 'integration smoke must bound Compose startup duration'
require scripts/integration-smoke.sh 'bound_startup\[@\].*compose\[@\].*up --build' 'the startup bound must be applied to the Compose up it exists to bound'
require scripts/integration-smoke.sh '--connect-timeout 5 --max-time 10' 'integration smoke must bound health probe duration'

echo 'Production topology static verification passed.'
