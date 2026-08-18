#!/usr/bin/env bash
# Live sweep of /api/v1/projects/{projectId}/agent-governance/** against the running stack.
#
# Why this exists separately from agent-evidence-acceptance.sh: that script drives the harness
# evidence-forwarder plugin, which lives outside this repository and is not published anywhere CI can
# reach, so it cannot run in CI and the whole agent-governance surface had no live coverage at all —
# only unit tests, which never see PostgreSQL, Keycloak, or the JSON the controller actually accepts.
#
# What is covered here is the API contract itself: fingerprint idempotency, the validation patterns on
# the request records, the session state machine, and the refusal to request human approval for a
# policy-failed change. What is NOT covered here belongs to the plugin and stays with the plugin: its
# digest computation, its spool file, and its behaviour when the control plane rejects a credential.
#
# TEST ENVIRONMENT ONLY. Step 0 elevates the CLI service account through the Keycloak admin API, exactly
# as end-to-end-acceptance.sh does. It never edits the committed realm.
set -uo pipefail
cd "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

KC_URL="${AISDLC_ACCEPTANCE_KEYCLOAK_URL:-http://localhost:8180}"
API="${AISDLC_ACCEPTANCE_API_URL:-http://management-server:8081}"
NETWORK="${AISDLC_ACCEPTANCE_NETWORK:-ai-sdlc_platform}"
CURL_IMAGE="${AISDLC_ACCEPTANCE_CURL_IMAGE:-curlimages/curl:latest}"

pass=0; fail=0
step() { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }
value(){ eval "printf '%s' \"\${$1:-}\""; if [ -z "$(eval "printf '%s' \"\${$1:-}\"")" ] && [ -r .env ]; then grep "^$1=" .env | cut -d= -f2-; fi; }
jq_()  { python3 -c "import json,sys
try: d=json.load(sys.stdin)
except Exception: print(''); raise SystemExit
print($1)"; }

inapi()    { docker run --rm --network "$NETWORK" "$CURL_IMAGE" -s "$@"; }
authget()  { inapi -H "Authorization: Bearer $TOKEN" "$API$1"; }
authpost() { inapi -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }
postcode() { inapi -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }

# A 64-hex digest derived from a label, so every run uses fresh fingerprints without needing randomness.
sha() { printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; }

step "0  Elevate the CLI service account (test environment only)"
KC_TOKEN="$(curl -s -d "grant_type=password&client_id=admin-cli&username=$(value KEYCLOAK_ADMIN)&password=$(value KEYCLOAK_ADMIN_PASSWORD)" "$KC_URL/realms/master/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$KC_TOKEN" ] && ok "Keycloak administration token obtained" || { bad "could not authenticate to Keycloak"; exit 1; }
CLIENT_UUID="$(curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/clients?clientId=aisdlc-cli" | jq_ "d[0]['id']")"
SA_USER="$(curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/clients/$CLIENT_UUID/service-account-user" | jq_ "d['id']")"
curl -s -H "Authorization: Bearer $KC_TOKEN" "$KC_URL/admin/realms/ai-sdlc/roles/admin" | python3 -c "import json,sys; print(json.dumps([json.load(sys.stdin)]))" > /tmp/aisdlc-agentgov-role.json
curl -s -o /dev/null -X POST -H "Authorization: Bearer $KC_TOKEN" -H 'Content-Type: application/json' \
  --data @/tmp/aisdlc-agentgov-role.json "$KC_URL/admin/realms/ai-sdlc/users/$SA_USER/role-mappings/realm"
ok "admin realm role granted for this run"

step "1  Obtain a control-plane token and a project"
TOKEN="$(curl -s -d "grant_type=client_credentials&client_id=aisdlc-cli&client_secret=$(value CLI_CLIENT_SECRET)" "$KC_URL/realms/ai-sdlc/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$TOKEN" ] && ok "client-credentials token issued" || { bad "no token — check that the realm import bound CLI_CLIENT_SECRET"; exit 1; }
SUFFIX="$(date +%s)"
ORG="$(authpost /api/v1/organizations "{\"slug\":\"agentgov-$SUFFIX\",\"name\":\"Agent Governance Sweep\"}" | jq_ "d.get('id','')")"
[ -n "$ORG" ] && ok "organization created" || { bad "organization creation failed"; exit 1; }
PROJECT="$(authpost "/api/v1/organizations/$ORG/projects" "{\"slug\":\"agentgov-$SUFFIX\",\"name\":\"Agent Governance Sweep\",\"description\":\"live contract sweep\"}" | jq_ "d.get('id','')")"
[ -n "$PROJECT" ] && ok "project created" || { bad "project creation failed"; exit 1; }
BASE="/api/v1/projects/$PROJECT/agent-governance"

step "2  Register a prompt template"
TPL_SHA="$(sha "template-$SUFFIX")"
TPL="$(authpost "$BASE/prompt-templates" "{\"templateKey\":\"review.assistant\",\"semanticVersion\":\"1.2.3\",\"displayName\":\"Review assistant\",\"sourceReference\":\"git://prompts/review.md\",\"templateSha256\":\"$TPL_SHA\",\"classification\":\"INTERNAL\"}" | jq_ "d.get('id','')")"
[ -n "$TPL" ] && ok "prompt template registered ($TPL)" || bad "prompt template creation failed"
LISTED="$(authget "$BASE/prompt-templates?page=0&size=25" | jq_ "len([t for t in (d.get('items') or []) if t.get('templateSha256')=='$TPL_SHA'])")"
[ "$LISTED" = "1" ] && ok "the template is listed with the digest it was registered under" || bad "template listing did not return the digest (got $LISTED matches)"

# The pattern is ^[a-z0-9._-]{3,160}$ and the version is semver. An uppercase key and a two-part version
# are the two mistakes a client actually makes, and both must be refused before anything is stored.
CODE="$(postcode "$BASE/prompt-templates" "{\"templateKey\":\"Review.Assistant\",\"semanticVersion\":\"1.2.3\",\"displayName\":\"x\",\"templateSha256\":\"$TPL_SHA\"}")"
[ "$CODE" = "400" ] && ok "an uppercase template key is refused (HTTP 400)" || bad "expected 400 for an uppercase template key, got $CODE"
CODE="$(postcode "$BASE/prompt-templates" "{\"templateKey\":\"review.assistant\",\"semanticVersion\":\"1.2\",\"displayName\":\"x\",\"templateSha256\":\"$TPL_SHA\"}")"
[ "$CODE" = "400" ] && ok "a non-semver version is refused (HTTP 400)" || bad "expected 400 for a two-part version, got $CODE"

step "3  Declare an agent session"
FP="$(sha "session-$SUFFIX")"
CTX="$(sha "context-$SUFFIX")"
TOOLS="$(sha "tools-$SUFFIX")"
SESSION_BODY="{\"promptTemplateId\":\"$TPL\",\"agentIdentity\":\"sweep-agent\",\"provider\":\"deepseek\",\"modelName\":\"deepseek-chat\",\"modelVersion\":\"2026-05-01\",\"sessionFingerprint\":\"$FP\",\"contextSha256\":\"$CTX\",\"toolInvocationCount\":7,\"toolInvocationSha256\":\"$TOOLS\",\"purpose\":\"live contract sweep\"}"
SESSION="$(authpost "$BASE/sessions" "$SESSION_BODY" | jq_ "d.get('id','')")"
[ -n "$SESSION" ] && ok "session declared ($SESSION)" || { bad "session declaration failed"; exit 1; }

# Every digest the client sent must come back byte for byte. A ledger that stores a truncated or
# re-cased digest cannot be compared against what the agent computed, which is the whole point of it.
if authget "$BASE/sessions?page=0&size=25" | python3 -c "
import json,sys
page=json.load(sys.stdin)
rows=[r for r in (page.get('items') or []) if r.get('sessionFingerprint')=='$FP']
if not rows: print('NOROW'); raise SystemExit(1)
r=rows[0]
checks=[('context digest stored verbatim', r.get('contextSha256')=='$CTX'),
        ('tool digest stored verbatim', r.get('toolInvocationSha256')=='$TOOLS'),
        ('tool count stored', r.get('toolInvocationCount')==7),
        ('provider recorded', r.get('provider')=='deepseek'),
        ('model version recorded', r.get('modelVersion')=='2026-05-01'),
        ('template linked', r.get('promptTemplateId')=='$TPL'),
        ('status starts DECLARED', r.get('status')=='DECLARED')]
bad=[n for n,good in checks if not good]
print('\n'.join('OK '+n for n,g in checks if g))
if bad: print('BAD '+'; '.join(bad)); raise SystemExit(1)
" > /tmp/aisdlc-agentgov-session.txt 2>&1; then
  while read -r line; do case "$line" in "OK "*) ok "${line#OK }";; esac; done < /tmp/aisdlc-agentgov-session.txt
else
  bad "session read-back mismatched: $(tr '\n' ' ' < /tmp/aisdlc-agentgov-session.txt)"
fi

step "4  Re-declare the same fingerprint (idempotency)"
AGAIN="$(authpost "$BASE/sessions" "$SESSION_BODY" | jq_ "d.get('id','')")"
[ "$AGAIN" = "$SESSION" ] && ok "re-declaration returns the same session, not a new one" || bad "re-declaration produced $AGAIN instead of $SESSION"
COUNT="$(authget "$BASE/sessions?page=0&size=50" | jq_ "len([r for r in (d.get('items') or []) if r.get('sessionFingerprint')=='$FP'])")"
[ "$COUNT" = "1" ] && ok "the fingerprint appears exactly once in the ledger" || bad "fingerprint appears $COUNT times"

# The fingerprint is stored lowercased, so the same digest sent uppercase is the same session.
UPPER="$(printf '%s' "$FP" | tr 'a-f' 'A-F')"
CASED="$(authpost "$BASE/sessions" "$(printf '%s' "$SESSION_BODY" | sed "s/$FP/$UPPER/")" | jq_ "d.get('id','')")"
[ "$CASED" = "$SESSION" ] && ok "the same digest sent uppercase resolves to the same session" || bad "an uppercase fingerprint created $CASED"

step "5  Validation on the session contract"
CODE="$(postcode "$BASE/sessions" "$(printf '%s' "$SESSION_BODY" | sed "s/\"$FP\"/\"not-a-digest\"/")")"
[ "$CODE" = "400" ] && ok "a malformed fingerprint is refused (HTTP 400)" || bad "expected 400 for a malformed fingerprint, got $CODE"
CODE="$(postcode "$BASE/sessions" "$(printf '%s' "$SESSION_BODY" | sed 's/"toolInvocationCount":7/"toolInvocationCount":100001/')")"
[ "$CODE" = "400" ] && ok "a tool count past the ceiling is refused (HTTP 400)" || bad "expected 400 for toolInvocationCount 100001, got $CODE"
CODE="$(postcode "$BASE/sessions" "$(printf '%s' "$SESSION_BODY" | sed 's/"agentIdentity":"sweep-agent"/"agentIdentity":""/')")"
[ "$CODE" = "400" ] && ok "a blank agent identity is refused (HTTP 400)" || bad "expected 400 for a blank agent identity, got $CODE"
# A template from another project must not be attachable, or the ledger would cite provenance across tenants.
CODE="$(postcode "$BASE/sessions" "$(printf '%s' "$SESSION_BODY" | sed "s/\"$TPL\"/\"00000000-0000-0000-0000-000000000000\"/;s/\"$FP\"/\"$(sha "other-$SUFFIX")\"/")")"
[ "$CODE" = "400" ] && ok "a prompt template outside the project is refused (HTTP 400)" || bad "expected 400 for a foreign template, got $CODE"

step "6  Declare a generated change and require human approval"
DUE="$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))")"
CHANGE_SHA="$(sha "change-$SUFFIX")"
CHANGE_BODY="{\"changeReference\":\"pr://sweep/$SUFFIX\",\"generatedChangeSha256\":\"$CHANGE_SHA\",\"policyDecision\":\"PASS\",\"policyReference\":\"policy://sweep\",\"approvalTitle\":\"Review generated change\",\"approvalDetails\":\"live contract sweep\",\"requiredQuorum\":1,\"requestedApprover\":\"sweep-reviewer\",\"approvalDueAt\":\"$DUE\"}"
EV="$(authpost "$BASE/sessions/$SESSION/evidence" "$CHANGE_BODY" | jq_ "d.get('id','')")"
[ -n "$EV" ] && ok "generated change declared ($EV)" || bad "generated change declaration failed"
if authget "$BASE/evidence?page=0&size=25" | python3 -c "
import json,sys
rows=[r for r in (json.load(sys.stdin).get('items') or []) if r.get('generatedChangeSha256')=='$CHANGE_SHA']
if not rows: print('NOROW'); raise SystemExit(1)
r=rows[0]
print('OK the change digest is stored verbatim')
print(('OK ' if r.get('agentSessionId')=='$SESSION' else 'BAD ')+'the change is linked to its session')
print(('OK ' if r.get('approvalRequestId') else 'BAD ')+'a human approval request was opened alongside it')
print(('OK ' if r.get('humanApprovalStatus')=='PENDING' else 'BAD ')+'the approval starts PENDING, not approved on arrival')
" > /tmp/aisdlc-agentgov-ev.txt 2>&1; then
  while read -r line; do case "$line" in "OK "*) ok "${line#OK }";; "BAD "*) bad "${line#BAD }";; esac; done < /tmp/aisdlc-agentgov-ev.txt
else
  bad "evidence read-back failed: $(tr '\n' ' ' < /tmp/aisdlc-agentgov-ev.txt)"
fi

# A policy-failed change must not be able to open a human approval: that would launder a refusal into
# a review queue, where an approver could wave it through without ever seeing that policy said no.
CODE="$(postcode "$BASE/sessions/$SESSION/evidence" "$(printf '%s' "$CHANGE_BODY" | sed 's/"policyDecision":"PASS"/"policyDecision":"FAIL"/;s|pr://sweep/|pr://sweep-fail/|')")"
[ "$CODE" = "409" ] || [ "$CODE" = "400" ] && ok "a policy-failed change cannot request approval (HTTP $CODE)" || bad "expected 400/409 for a FAIL policy decision, got $CODE"

step "7  Session state machine"
STATUS="$(authpost "$BASE/sessions/$SESSION/complete" "" | jq_ "d.get('status','')")"
[ "$STATUS" = "COMPLETED" ] && ok "the session completes" || bad "expected COMPLETED, got '$STATUS'"
# Evidence may only be declared against an active session, so a completed one must refuse more.
CODE="$(postcode "$BASE/sessions/$SESSION/evidence" "$(printf '%s' "$CHANGE_BODY" | sed 's|pr://sweep/|pr://sweep-late/|')")"
[ "$CODE" = "409" ] || [ "$CODE" = "400" ] && ok "a completed session refuses further evidence (HTTP $CODE)" || bad "expected 400/409 after completion, got $CODE"

step "8  Access control"
CODE="$(inapi -o /dev/null -w '%{http_code}' "$API$BASE/sessions")"
[ "$CODE" = "401" ] && ok "an anonymous caller is refused (HTTP 401)" || bad "expected 401 without a token, got $CODE"
CODE="$(inapi -o /dev/null -w '%{http_code}' -H "Authorization: Bearer not-a-token" "$API$BASE/sessions")"
[ "$CODE" = "401" ] && ok "an invalid token is refused (HTTP 401)" || bad "expected 401 for a bogus token, got $CODE"

step "9  The ledger recorded all of it"
EVENTS="$(authget "/api/v1/organizations/$ORG/audit-events?page=0&size=50" | jq_ "len([e for e in (d.get('items') or []) if 'AGENT' in (e.get('action') or '')])")"
[ "${EVENTS:-0}" -ge 2 ] && ok "the audit ledger holds $EVENTS agent-governance events" || bad "expected at least 2 agent audit events, got '${EVENTS:-0}'"

printf '\n\033[1m%s passed, %s failed\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
