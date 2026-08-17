#!/usr/bin/env bash
# Acceptance run for the harness agent-governance evidence path.
#
# Drives one agent session through the DeepSeek/xDev Harness evidence-forwarder plugin
# (`@xdev-ai/dsh-plugin-aisdlc-evidence`) against the running Docker Compose topology, then reads the
# agent-governance ledger back and compares the stored digests against the ones the plugin computed.
#
# This covers the surface `end-to-end-acceptance.sh` does not touch at all: before this script existed,
# no test exercised `/api/v1/projects/{id}/agent-governance/**` against a live PostgreSQL and Keycloak,
# so the module's idempotency and validation contract were only ever asserted by unit tests.
#
# The plugin lives outside this repository. Point AISDLC_EVIDENCE_LIB_DIR at a directory holding its
# built output plus the driver — `index.js`, `digest.js`, `live.mjs`, and a `node_modules` with
# `@deepseek-ai/schemastery` — and this script runs it inside the compose network, the same way the
# acceptance run drives the Go CLI. The management API is not published to the host by design.
#
# TEST ENVIRONMENT ONLY. Step 0 elevates the CLI service account through the Keycloak admin API for
# admin-only bootstrap, exactly as `end-to-end-acceptance.sh` does. It never edits the committed realm.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

KC_URL="${AISDLC_ACCEPTANCE_KEYCLOAK_URL:-http://localhost:8180}"
API="${AISDLC_ACCEPTANCE_API_URL:-http://management-server:8081}"
NETWORK="${AISDLC_ACCEPTANCE_NETWORK:-ai-sdlc_platform}"
CURL_IMAGE="${AISDLC_ACCEPTANCE_CURL_IMAGE:-curlimages/curl:latest}"
NODE_IMAGE="${AISDLC_ACCEPTANCE_NODE_IMAGE:-node:24-alpine}"

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

# A missing plugin build is a configuration error, not a pass. Skipping quietly here would report a
# green run for a path nothing exercised.
LIB_DIR="${AISDLC_EVIDENCE_LIB_DIR:-}"
if [ -z "$LIB_DIR" ]; then
  echo "AISDLC_EVIDENCE_LIB_DIR must point at the built evidence-forwarder plugin (index.js, digest.js, live.mjs)" >&2
  exit 2
fi
for required in index.js digest.js live.mjs; do
  if [ ! -r "$LIB_DIR/$required" ]; then
    echo "AISDLC_EVIDENCE_LIB_DIR is missing $required — build the plugin first (see its README, \"Verify\")" >&2
    exit 2
  fi
done
if [ -z "$(value CLI_CLIENT_SECRET)" ]; then
  echo "CLI_CLIENT_SECRET must be set in the environment or in .env" >&2; exit 2
fi

inapi()    { docker run --rm --network "$NETWORK" "$CURL_IMAGE" -s "$@"; }
authget()  { inapi -H "Authorization: Bearer $TOKEN" "$API$1"; }
authpost() { inapi -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }

step "0  Elevate the CLI service account (test environment only)"
KC_TOKEN="$(curl -s -d "grant_type=password&client_id=admin-cli&username=$(value KEYCLOAK_ADMIN)&password=$(value KEYCLOAK_ADMIN_PASSWORD)" "$KC_URL/realms/master/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$KC_TOKEN" ] && ok "Keycloak administration token obtained" || { bad "could not authenticate to Keycloak"; exit 1; }
CLIENT_UUID="$(curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/clients?clientId=aisdlc-cli" | jq_ "d[0]['id']")"
SA_USER="$(curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/clients/$CLIENT_UUID/service-account-user" | jq_ "d['id']")"
curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/roles/admin" | python3 -c "import json,sys; print(json.dumps([json.load(sys.stdin)]))" > /tmp/aisdlc-evidence-role.json
curl -s -o /dev/null -X POST -H "Authorization: Bearer $KC_TOKEN" -H 'Content-Type: application/json' \
  --data @/tmp/aisdlc-evidence-role.json "$KC_URL/admin/realms/ai-sdlc/users/$SA_USER/role-mappings/realm"
ok "admin realm role granted for this run"

step "1  Obtain a control-plane token"
TOKEN="$(curl -s -d "grant_type=client_credentials&client_id=aisdlc-cli&client_secret=$(value CLI_CLIENT_SECRET)" "$KC_URL/realms/ai-sdlc/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$TOKEN" ] && ok "client-credentials token issued" || { bad "no token — check that the realm import bound CLI_CLIENT_SECRET"; exit 1; }

step "2  Create an organization and a project"
SUFFIX="$(date +%s)"
ORG="$(authpost /api/v1/organizations "{\"slug\":\"dsh-$SUFFIX\",\"name\":\"Harness Evidence Org\"}" | jq_ "d.get('id','')")"
[ -n "$ORG" ] && ok "organization created ($ORG)" || { bad "organization creation failed"; exit 1; }
PROJECT="$(authpost "/api/v1/organizations/$ORG/projects" "{\"slug\":\"dsh-$SUFFIX\",\"name\":\"Harness Evidence Project\",\"description\":\"evidence forwarder acceptance run\"}" | jq_ "d.get('id','')")"
[ -n "$PROJECT" ] && ok "project created ($PROJECT)" || { bad "project creation failed"; exit 1; }

step "3  Run the evidence forwarder against the live control plane"
SESSION_ID="acc-$SUFFIX"
EXPECTED="$(docker run --rm --network "$NETWORK" \
  -v "$LIB_DIR:/plugin:ro" -w /plugin \
  -e AISDLC_BASE_URL="$API" -e AISDLC_PROJECT_ID="$PROJECT" \
  -e AISDLC_API_TOKEN="$TOKEN" -e AISDLC_SESSION_ID="$SESSION_ID" \
  "$NODE_IMAGE" node live.mjs)"
[ -n "$EXPECTED" ] && ok "plugin run completed" || { bad "plugin produced no output"; exit 1; }
export EXPECTED
WARNINGS="$(printf '%s' "$EXPECTED" | jq_ "json.dumps(d['warnings'])")"
[ "$WARNINGS" = "[]" ] && ok "plugin logged no warnings" || bad "plugin warnings: $WARNINGS"

step "4  Read the agent-governance ledger back"
if printf '%s\n' "$(authget "/api/v1/projects/$PROJECT/agent-governance/sessions?page=0&size=10")" | python3 -c "
import json,sys,os
expected=json.loads(os.environ['EXPECTED'])
page=json.load(sys.stdin)
items=page.get('items') or page.get('content') or []
rows=[r for r in items if r.get('sessionFingerprint')==expected['sessionFingerprint']]
if not rows:
    print('FAIL  no ledger row matched the declared fingerprint'); print(json.dumps(page)[:600]); raise SystemExit(1)
row=rows[0]
checks=[
  ('fingerprint stored', row['sessionFingerprint']==expected['sessionFingerprint']),
  ('context digest stored', row.get('contextSha256')==expected['contextSha256']),
  ('tool digest stored', row.get('toolInvocationSha256')==expected['toolInvocationSha256']),
  ('tool count stored', row.get('toolInvocationCount')==expected['toolInvocationCount']),
  ('provider recorded', row.get('provider')=='deepseek'),
  ('model recorded', row.get('modelName')=='deepseek-chat'),
  ('session completed', row.get('status')=='COMPLETED'),
]
for name,good in checks: print(('PASS  ' if good else 'FAIL  ')+name)
if [name for name,good in checks if not good]:
    print(json.dumps(row)); raise SystemExit(1)
"; then
  pass=$((pass+7))
else
  fail=$((fail+1))
fi

step "5  Re-declare the same session (idempotency)"
docker run --rm --network "$NETWORK" -v "$LIB_DIR:/plugin:ro" -w /plugin \
  -e AISDLC_BASE_URL="$API" -e AISDLC_PROJECT_ID="$PROJECT" \
  -e AISDLC_API_TOKEN="$TOKEN" -e AISDLC_SESSION_ID="$SESSION_ID" \
  "$NODE_IMAGE" node live.mjs >/dev/null
COUNT="$(authget "/api/v1/projects/$PROJECT/agent-governance/sessions?page=0&size=50" | python3 -c "
import json,sys,os
expected=json.loads(os.environ['EXPECTED'])
page=json.load(sys.stdin)
items=page.get('items') or page.get('content') or []
print(len([r for r in items if r.get('sessionFingerprint')==expected['sessionFingerprint']]))")"
[ "$COUNT" = "1" ] && ok "re-declaration did not duplicate provenance" || bad "fingerprint appears $COUNT times"

step "6  Reject the credential and prove the failure is contained"
SPOOL_DIR="$(mktemp -d)"
if REJECTED="$(docker run --rm --network "$NETWORK" \
  -v "$LIB_DIR:/plugin:ro" -v "$SPOOL_DIR:/spool" -w /plugin \
  -e AISDLC_BASE_URL="$API" -e AISDLC_PROJECT_ID="$PROJECT" \
  -e AISDLC_API_TOKEN="not-a-valid-token" -e AISDLC_SESSION_ID="acc-rejected-$SUFFIX" \
  -e AISDLC_SPOOL_PATH="/spool/evidence.jsonl" \
  "$NODE_IMAGE" node live.mjs)"; then
  ok "a rejected declaration exits cleanly (no throw into the agent loop)"
else
  bad "the driver exited non-zero on a rejected credential"
fi
printf '%s' "$REJECTED" | grep -q '401' && ok "the rejection is reported as a warning" \
  || bad "expected an HTTP 401 warning, got: $(printf '%s' "$REJECTED" | jq_ "json.dumps(d.get('warnings'))")"
[ -s "$SPOOL_DIR/evidence.jsonl" ] && ok "the undelivered declaration was spooled" || bad "nothing was spooled"
rm -rf "$SPOOL_DIR"

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
