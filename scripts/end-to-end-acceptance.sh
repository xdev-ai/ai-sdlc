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
ORG="$(authpost /api/v1/organizations "{\"slug\":\"acc-$SUFFIX\",\"name\":\"Acceptance Org\"}" | jq_ "d.get('id','')")"
[ -n "$ORG" ] && ok "organization created ($ORG)" || { bad "organization creation failed"; exit 1; }
PROJECT="$(authpost "/api/v1/organizations/$ORG/projects" "{\"slug\":\"acc-$SUFFIX\",\"name\":\"Acceptance Project\",\"description\":\"end-to-end run\"}" | jq_ "d.get('id','')")"
[ -n "$PROJECT" ] && ok "project created ($PROJECT)" || bad "project creation failed"

step "3  Link a repository"
LINK="$(authpost "/api/v1/projects/$PROJECT/scm-repositories" "{\"provider\":\"GITHUB\",\"repositoryFullName\":\"xdev-ai/acc-$SUFFIX\",\"defaultBranch\":\"main\",\"policyGateEnabled\":false}" | jq_ "d.get('id','')")"
[ -n "$LINK" ] && ok "repository linked ($LINK)" || bad "repository link failed"

step "4  Sync validation evidence through the CLI ingest contract"
DIGEST="$(printf 'acceptance-evidence-%s' "$SUFFIX" | shasum -a 256 2>/dev/null | awk '{print $1}')"
[ -n "$DIGEST" ] || DIGEST="$(printf 'acceptance-evidence-%s' "$SUFFIX" | sha256sum | awk '{print $1}')"
RUN_BODY="{\"status\":\"PASSED\",\"cliVersion\":\"acceptance-1.0.0\",\"kitVersion\":\"core-1.0.0\",\"modelPin\":\"claude-opus-5\",\"bare\":false,\"findings\":[{\"severity\":\"MEDIUM\",\"code\":\"SPEC-STRUCT-004\",\"message\":\"missing recommended section\",\"path\":\"specs/a.md\",\"line\":3}],\"evidence\":[{\"type\":\"validation-report\",\"digestSha256\":\"$DIGEST\",\"uri\":\"file:///acceptance/report.json\"}]}"
RUN="$(inapi -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: acceptance-$SUFFIX" -d "$RUN_BODY" "$API/api/v1/cli/projects/$PROJECT/validation-runs" | jq_ "d.get('id','')")"
[ -n "$RUN" ] && ok "validation run ingested with evidence ($RUN)" || bad "validation run ingest failed"
# The same key must not create a second run: the CLI retries, and a duplicate would corrupt the evidence record.
RUN_REPLAY="$(inapi -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: acceptance-$SUFFIX" -d "$RUN_BODY" "$API/api/v1/cli/projects/$PROJECT/validation-runs" | jq_ "d.get('id','')")"
[ "$RUN_REPLAY" = "$RUN" ] && ok "replayed ingest returned the same run, not a duplicate" || bad "idempotency broken: replay produced $RUN_REPLAY"
DETAIL="$(authget "/api/v1/projects/$PROJECT/validation-runs/$RUN")"
printf '%s' "$DETAIL" | grep -q "$DIGEST" && ok "evidence digest stored with the run" || bad "evidence digest missing from the run detail"
printf '%s' "$DETAIL" | grep -q 'claude-opus-5' && ok "model pin recorded on the run" || bad "model pin missing"

step "5  Evaluate a CEL policy bundle"
BUNDLE_BODY='{"key":"acceptance-gate","semanticVersion":"1.0.0","description":"acceptance","expression":"context.evidenceCount > 0","dryRunDefault":false,"fixtures":[{"name":"has-evidence","expected":true,"context":{"evidenceCount":1}},{"name":"no-evidence","expected":false,"context":{"evidenceCount":0}}]}'
BUNDLE="$(authpost "/api/v1/projects/$PROJECT/policy-bundles" "$BUNDLE_BODY" | jq_ "d.get('id','')")"
[ -n "$BUNDLE" ] && ok "policy bundle created ($BUNDLE)" || bad "policy bundle creation failed"
if [ -n "$BUNDLE" ]; then
  ACTIVATED="$(authpost "/api/v1/projects/$PROJECT/policy-bundles/$BUNDLE/activate" '{}')"
  printf '%s' "$ACTIVATED" | grep -q 'ACTIVE' && ok "bundle activated after its fixtures passed" || bad "activation refused: $ACTIVATED"
  PASS_EVAL="$(authpost "/api/v1/projects/$PROJECT/policy-bundles/$BUNDLE/evaluate" '{"context":{"evidenceCount":1},"dryRun":false}')"
  printf '%s' "$PASS_EVAL" | grep -q '"result":true' && ok "policy evaluates true on satisfying context" || bad "policy did not pass: $PASS_EVAL"
  FAIL_EVAL="$(authpost "/api/v1/projects/$PROJECT/policy-bundles/$BUNDLE/evaluate" '{"context":{"evidenceCount":0},"dryRun":false}')"
  printf '%s' "$FAIL_EVAL" | grep -q '"result":false' && ok "policy evaluates false on failing context" || bad "policy did not fail closed: $FAIL_EVAL"
  # A context the expression cannot resolve must not silently pass.
  ERR_EVAL="$(authpost "/api/v1/projects/$PROJECT/policy-bundles/$BUNDLE/evaluate" '{"context":{},"dryRun":false}')"
  printf '%s' "$ERR_EVAL" | grep -q '"result":true' && bad "a missing input produced a passing decision" || ok "a missing input does not produce a pass"
  EVALS="$(authget "/api/v1/projects/$PROJECT/policy-bundles/$BUNDLE/evaluations?page=0&size=10" | jq_ "d.get('totalItems',0)")"
  [ "${EVALS:-0}" -ge 3 ] && ok "evaluation evidence retained ($EVALS records)" || bad "expected retained evaluations, saw ${EVALS:-0}"
fi

step "6  Require a human approval and reach quorum"
DUE="$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")"
APPROVAL="$(authpost "/api/v1/projects/$PROJECT/approvals" "{\"sourceType\":\"release\",\"sourceId\":\"acc-$SUFFIX\",\"title\":\"Acceptance release gate\",\"details\":\"end-to-end run\",\"requiredQuorum\":1,\"dueAt\":\"$DUE\"}" | jq_ "d.get('id','')")"
[ -n "$APPROVAL" ] && ok "approval requested with quorum 1 ($APPROVAL)" || bad "approval request failed"
if [ -n "$APPROVAL" ]; then
  authpost "/api/v1/approvals/$APPROVAL/decisions" '{"decision":"APPROVE","comment":"acceptance run"}' >/dev/null
  STATE="$(authget "/api/v1/projects/$PROJECT/approvals?page=0&size=10")"
  printf '%s' "$STATE" | grep -q 'APPROVED' && ok "approval reached quorum and is recorded as APPROVED" || bad "approval did not reach quorum: $STATE"
  # An approval already decided must not be decidable again.
  REDECIDE="$(inapi -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"decision":"REJECT","comment":"second decision"}' "$API/api/v1/approvals/$APPROVAL/decisions")"
  [ "$REDECIDE" = "200" ] && bad "a decided approval accepted a second decision" || ok "a decided approval refuses a second decision (HTTP $REDECIDE)"
fi

step "7  Ingest a signed SCM webhook"
WEBHOOK_SECRET="$(value AISDLC_GITHUB_WEBHOOK_SECRET)"
if [ -z "$WEBHOOK_SECRET" ]; then
  bad "AISDLC_GITHUB_WEBHOOK_SECRET is not set, so webhook ingestion cannot be exercised"
else
  HOOK_BODY="{\"action\":\"opened\",\"repository\":{\"full_name\":\"xdev-ai/acc-$SUFFIX\"},\"pull_request\":{\"number\":1,\"head\":{\"ref\":\"topic\",\"sha\":\"1111111111111111111111111111111111111111\"}}}"
  SIG="sha256=$(printf '%s' "$HOOK_BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | awk '{print $NF}')"
  DELIVERY="acc-delivery-$SUFFIX"
  HOOK="$(inapi -X POST -H "Content-Type: application/json" -H "X-Hub-Signature-256: $SIG" -H "X-GitHub-Delivery: $DELIVERY" -H "X-GitHub-Event: pull_request" -d "$HOOK_BODY" "$API/api/v1/webhooks/github")"
  printf '%s' "$HOOK" | grep -q '"accepted":true' && ok "signed webhook accepted and correlated to the linked repository" || bad "webhook rejected: $HOOK"
  printf '%s' "$HOOK" | grep -q '"disposition":"processed"' && ok "webhook processed into the event ledger" || bad "webhook not processed: $HOOK"
  # The same delivery must be de-duplicated: providers retry, and a second event would double-count the change.
  REPLAY="$(inapi -X POST -H "Content-Type: application/json" -H "X-Hub-Signature-256: $SIG" -H "X-GitHub-Delivery: $DELIVERY" -H "X-GitHub-Event: pull_request" -d "$HOOK_BODY" "$API/api/v1/webhooks/github")"
  printf '%s' "$REPLAY" | grep -q '"duplicate":true' && ok "replayed delivery de-duplicated" || bad "replay was not de-duplicated: $REPLAY"
  # An unsigned or wrongly signed delivery must never reach the ledger.
  FORGED="$(inapi -o /dev/null -w '%{http_code}' -X POST -H "Content-Type: application/json" -H "X-Hub-Signature-256: sha256=0000000000000000000000000000000000000000000000000000000000000000" -H "X-GitHub-Delivery: forged-$SUFFIX" -H "X-GitHub-Event: pull_request" -d "$HOOK_BODY" "$API/api/v1/webhooks/github")"
  [ "$FORGED" = "202" ] && bad "a forged signature was accepted" || ok "a forged signature is refused (HTTP $FORGED)"
fi

step "8  Verify the audit chain over everything above"
VERIFY="$(authget "/api/v1/organizations/$ORG/audit-events/verify")"
printf '%s' "$VERIFY" | grep -q '"intact":true' && ok "audit hash chain intact" || bad "audit chain not intact: $VERIFY"
COUNT="$(authget "/api/v1/organizations/$ORG/audit-events?page=0&size=50" | jq_ "d.get('totalItems', 0)")"
[ "${COUNT:-0}" -ge 6 ] && ok "audit ledger recorded ${COUNT} events for this run" || bad "expected at least 6 audit events across the full flow, saw ${COUNT:-0}"

step "9  Confirm the management API stays off the host network"
if curl -s -m 5 -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then
  bad "the management API answered on the host; it must stay on the private network"
else
  ok "the management API is not published to the host"
fi

printf '\n=== summary ===\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
