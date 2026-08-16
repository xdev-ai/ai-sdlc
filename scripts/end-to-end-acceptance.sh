#!/usr/bin/env bash
# End-to-end acceptance run for the governed AI-SDLC flow.
#
# Drives one project through the chain the platform exists to enforce — organization and project creation, repository
# link, validation evidence, and audit-chain verification — against the running Docker Compose topology. This is the
# check unit tests and the public-health smoke cannot make: that the pieces work together on a real PostgreSQL, a real
# Keycloak, and real object storage.
#
# Two production defects were found the first time this ran and neither was visible to 175 passing unit tests: every
# jsonb column was bound as varchar so no audit event could be inserted, and audit_events.tenant_id became NOT NULL in
# V11 without the entity carrying it. Keep this in CI.
#
# TEST ENVIRONMENT ONLY. Step 0 elevates the CLI service account through the Keycloak admin API to perform admin-only
# bootstrap. The shipped realm grants that account only `developer`; this script never edits the committed realm.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

KC_URL="${AISDLC_ACCEPTANCE_KEYCLOAK_URL:-http://localhost:8180}"
API="${AISDLC_ACCEPTANCE_API_URL:-http://management-server:8081}"
NETWORK="${AISDLC_ACCEPTANCE_NETWORK:-ai-sdlc_platform}"
CURL_IMAGE="${AISDLC_ACCEPTANCE_CURL_IMAGE:-curlimages/curl:latest}"

pass=0; fail=0
step() { printf '\n=== %s ===\n' "$1"; }
ok()   { printf 'PASS  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf 'FAIL  %s\n' "$1"; fail=$((fail+1)); }
# Prefer the environment so CI can provision ephemeral values; fall back to .env for a local run.
value() {
  eval "printf '%s' \"\${$1:-}\""
  if [ -z "$(eval "printf '%s' \"\${$1:-}\"")" ] && [ -r .env ]; then grep "^$1=" .env | cut -d= -f2-; fi
}
jq_() { python3 -c "import json,sys
try: d=json.load(sys.stdin)
except Exception: print(''); raise SystemExit
print($1)"; }

if [ -z "$(value CLI_CLIENT_SECRET)" ]; then
  echo "CLI_CLIENT_SECRET must be set in the environment or in .env" >&2; exit 2
fi

# The management API is not published to the host — that is part of the design — so calls run inside the network.
inapi() { docker run --rm --network "$NETWORK" "$CURL_IMAGE" -s "$@"; }
authget()  { inapi -H "Authorization: Bearer $TOKEN" "$API$1"; }
authpost() { inapi -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }

step "0  Elevate the CLI service account (test environment only)"
KC_TOKEN="$(curl -s -d "grant_type=password&client_id=admin-cli&username=$(value KEYCLOAK_ADMIN)&password=$(value KEYCLOAK_ADMIN_PASSWORD)" "$KC_URL/realms/master/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$KC_TOKEN" ] && ok "Keycloak administration token obtained" || { bad "could not authenticate to Keycloak"; exit 1; }
CLIENT_UUID="$(curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/clients?clientId=aisdlc-cli" | jq_ "d[0]['id']")"
SA_USER="$(curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/clients/$CLIENT_UUID/service-account-user" | jq_ "d['id']")"
curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/roles/admin" | python3 -c "import json,sys; print(json.dumps([json.load(sys.stdin)]))" > /tmp/aisdlc-acceptance-role.json
curl -s -o /dev/null -X POST -H "Authorization: Bearer $KC_TOKEN" -H 'Content-Type: application/json' \
  --data @/tmp/aisdlc-acceptance-role.json "$KC_URL/admin/realms/ai-sdlc/users/$SA_USER/role-mappings/realm"
ok "admin realm role granted for this run"

step "1  Obtain a control-plane token"
TOKEN="$(curl -s -d "grant_type=client_credentials&client_id=aisdlc-cli&client_secret=$(value CLI_CLIENT_SECRET)" "$KC_URL/realms/ai-sdlc/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$TOKEN" ] && ok "client-credentials token issued" || { bad "no token — check that the realm import bound CLI_CLIENT_SECRET"; exit 1; }
CLAIMS="$(printf '%s' "$TOKEN" | cut -d. -f2 | python3 -c "
import base64,json,sys
raw=sys.stdin.read().strip(); raw+='='*(-len(raw)%4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(raw))))")"
printf '%s' "$CLAIMS" | grep -q 'aisdlc-management' && ok "token carries the control-plane audience" || bad "control-plane audience missing"
printf '%s' "$CLAIMS" | grep -q 'agent_runtime' && bad "a human-facing token must not carry agent_runtime" || ok "token carries no runtime workload role"

step "2  Create an organization and a project"
SUFFIX="$(date +%s)"
ORG="$(authpost /api/v1/organizations "{\"slug\":\"acc-$SUFFIX\",\"name\":\"Acceptance Org\",\"description\":\"end-to-end run\"}" | jq_ "d.get('id','')")"
[ -n "$ORG" ] && ok "organization created ($ORG)" || { bad "organization creation failed"; exit 1; }
PROJECT="$(authpost "/api/v1/organizations/$ORG/projects" "{\"slug\":\"acc-$SUFFIX\",\"name\":\"Acceptance Project\",\"description\":\"end-to-end run\"}" | jq_ "d.get('id','')")"
[ -n "$PROJECT" ] && ok "project created ($PROJECT)" || bad "project creation failed"

step "3  Link a repository"
LINK="$(authpost "/api/v1/projects/$PROJECT/scm-repositories" "{\"provider\":\"GITHUB\",\"repositoryFullName\":\"xdev-ai/acc-$SUFFIX\",\"defaultBranch\":\"main\",\"policyGateEnabled\":false}" | jq_ "d.get('id','')")"
[ -n "$LINK" ] && ok "repository linked ($LINK)" || bad "repository link failed"

step "4  Verify the audit chain over everything above"
VERIFY="$(authget "/api/v1/organizations/$ORG/audit-events/verify")"
printf '%s' "$VERIFY" | grep -q '"intact":true' && ok "audit hash chain intact" || bad "audit chain not intact: $VERIFY"
COUNT="$(authget "/api/v1/organizations/$ORG/audit-events?page=0&size=50" | jq_ "d.get('totalItems', 0)")"
[ "${COUNT:-0}" -ge 2 ] && ok "audit ledger recorded ${COUNT} events for this run" || bad "expected at least 2 audit events, saw ${COUNT:-0}"

step "5  Confirm the management API stays off the host network"
if curl -s -m 5 -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then
  bad "the management API answered on the host; it must stay on the private network"
else
  ok "the management API is not published to the host"
fi

printf '\n=== summary ===\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
