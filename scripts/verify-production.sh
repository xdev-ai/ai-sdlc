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
require docker-compose.yml 'cap_add: \[SETUID, SETGID\]' 'identity gateway must retain only the capabilities required to drop Nginx workers to the non-root user'
require infra/nginx/keycloak.conf 'X-Content-Type-Options' 'identity gateway must emit content-type protection'
require infra/nginx/keycloak.conf 'Content-Security-Policy' 'identity gateway must restrict framing'
require infra/nginx/keycloak.conf 'limit_req zone=identity_per_ip' 'identity gateway must rate-limit requests'
require infra/nginx/keycloak.conf 'client_body_temp_path /tmp/client_body_temp;' 'identity gateway must redirect request temp files to writable tmpfs'
require docker-compose.yml '/tmp:uid=101,gid=101,mode=1777' 'identity gateway must expose writable tmpfs for Nginx temporary paths'
require docker-compose.yml '/var/run:uid=0,gid=0,mode=0755' 'identity gateway must grant only the Nginx master process ownership of its PID directory'
require infra/postgres/init-keycloak-db.sql '^CREATE DATABASE keycloak;$' 'PostgreSQL init must create the Keycloak database'
forbid infra/postgres/init-keycloak-db.sql 'GRANT .* TO aisdlc' 'PostgreSQL init must not assume a hard-coded database role'
require scripts/integration-smoke.sh 'timeout --foreground 420s' 'integration smoke must bound Compose startup duration'
require scripts/integration-smoke.sh '--connect-timeout 5 --max-time 10' 'integration smoke must bound health probe duration'

echo 'Production topology static verification passed.'
