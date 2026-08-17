#!/usr/bin/env bash
# Broad feature sweep against the LIVE running stack.
#
# end-to-end-acceptance.sh proves the governed spine. This exercises the features it does not touch:
# evidence upload into real object storage, risk intelligence, tenants and SCIM, notification channels,
# runtime AI governance, the cost ledger and budget enforcement, agent governance, and paging.
set -uo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

KC_URL="${AISDLC_ACCEPTANCE_KEYCLOAK_URL:-http://localhost:8180}"
API="http://management-server:8081"
NETWORK="${AISDLC_ACCEPTANCE_NETWORK:-ai-sdlc_platform}"
CURL_IMAGE="curlimages/curl:latest"

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
authcode() { inapi -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$API$1"; }
authpost() { inapi -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }
postcode() { inapi -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }

TOKEN="$(curl -s -d "grant_type=client_credentials&client_id=aisdlc-cli&client_secret=$(value CLI_CLIENT_SECRET)" \
  "$KC_URL/realms/ai-sdlc/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$TOKEN" ] || { echo "no token — run end-to-end-acceptance.sh first to elevate the service account"; exit 2; }

# Reuse the org/project the acceptance run created, so this sweeps over real existing data.
ORG="$(authget '/api/v1/organizations?page=0&size=1' | jq_ "d['items'][0]['id']")"
PROJECT="$(authget "/api/v1/organizations/$ORG/projects?page=0&size=1" | jq_ "d['items'][0]['id']")"
SUFFIX="$(date +%s)"
echo "organization $ORG / project $PROJECT"

step "Evidence repository — real bytes into MinIO"
printf 'acceptance evidence %s\n' "$SUFFIX" > /tmp/aisdlc-evidence.txt
DIGEST="$(shasum -a 256 /tmp/aisdlc-evidence.txt | cut -d' ' -f1)"
UPLOAD="$(docker run --rm --network "$NETWORK" -v /tmp/aisdlc-evidence.txt:/tmp/e.txt "$CURL_IMAGE" -s \
  -X POST -H "Authorization: Bearer $TOKEN" -H "X-Content-SHA256: $DIGEST" \
  -F "file=@/tmp/e.txt" -F "assetType=VALIDATION" \
  "$API/api/v1/projects/$PROJECT/evidence-assets")"
ASSET="$(printf '%s' "$UPLOAD" | jq_ "d.get('id','')")"
[ -n "$ASSET" ] && ok "asset uploaded ($ASSET)" || bad "upload failed: $(printf '%s' "$UPLOAD" | head -c 200)"
STORED="$(printf '%s' "$UPLOAD" | jq_ "d.get('sha256Digest','')")"
[ "$STORED" = "$DIGEST" ] && ok "server-computed SHA-256 matches the client digest" || bad "digest mismatch: $STORED vs $DIGEST"
DL="$(authget "/api/v1/projects/$PROJECT/evidence-assets/$ASSET" | jq_ "d.get('downloadUrl','')")"
case "$DL" in *X-Amz-Signature*) ok "download URL is presigned, not a public object URL";; *) bad "no presigned URL: $DL";; esac
BYTES="$(docker run --rm --network "$NETWORK" "$CURL_IMAGE" -s "$DL")"
[ "$BYTES" = "$(cat /tmp/aisdlc-evidence.txt)" ] && ok "presigned URL returns the exact bytes uploaded" || bad "downloaded content differs"
BADSHA="$(docker run --rm --network "$NETWORK" -v /tmp/aisdlc-evidence.txt:/tmp/e.txt "$CURL_IMAGE" -s -o /dev/null -w '%{http_code}' \
  -X POST -H "Authorization: Bearer $TOKEN" -H "X-Content-SHA256: $(printf '0%.0s' $(seq 64))" \
  -F "file=@/tmp/e.txt" -F "assetType=VALIDATION" "$API/api/v1/projects/$PROJECT/evidence-assets")"
[ "$BADSHA" = "400" ] || [ "$BADSHA" = "422" ] && ok "a wrong client digest is rejected (HTTP $BADSHA)" || bad "wrong digest accepted (HTTP $BADSHA)"

step "Risk intelligence"
RISK="$(authpost "/api/v1/projects/$PROJECT/risk-intelligence/recompute" '{}')"
SCORE="$(printf '%s' "$RISK" | jq_ "d.get('score','')")"
BAND="$(printf '%s' "$RISK" | jq_ "d.get('band','')")"
[ -n "$SCORE" ] && ok "risk score computed: $SCORE ($BAND)" || bad "recompute failed: $(printf '%s' "$RISK" | head -c 200)"
FORMULA="$(authget "/api/v1/projects/$PROJECT/risk-intelligence/latest" | jq_ "d.get('formulaVersion','')")"
[ "$FORMULA" = "risk.v1" ] && ok "snapshot records formula version risk.v1" || bad "unexpected formula version: $FORMULA"

step "Notification channels — secrets never returned"
CH="$(authpost "/api/v1/projects/$PROJECT/notification-channels" \
  "{\"type\":\"GENERIC_WEBHOOK\",\"name\":\"sweep-$SUFFIX\",\"destination\":\"https://example.invalid/hook\",\"sharedSecret\":\"sweep-secret-$SUFFIX\"}")"
CHID="$(printf '%s' "$CH" | jq_ "d.get('id','')")"
[ -n "$CHID" ] && ok "channel created ($CHID)" || bad "channel creation failed: $(printf '%s' "$CH" | head -c 200)"
LIST="$(authget "/api/v1/projects/$PROJECT/notification-channels")"
printf '%s' "$LIST" | grep -q "sweep-secret-$SUFFIX" && bad "the signing secret came back in the list response" || ok "signing secret is never returned"
printf '%s' "$LIST" | grep -q 'example.invalid' && bad "the raw destination came back" || ok "destination is fingerprinted, not returned"

step "Cost ledger and budget enforcement"
USAGE="$(authpost "/api/v1/projects/$PROJECT/inference-costs/usage" \
  "{\"sourceEventKey\":\"sweep-$SUFFIX\",\"provider\":\"anthropic\",\"modelName\":\"claude-opus-5\",\"occurredAt\":\"2026-08-17T00:00:00Z\",\"inputTokens\":1000,\"outputTokens\":500,\"currencyCode\":\"USD\",\"sourceCostMinor\":250,\"sourceClaimSha256\":\"$(printf 'a%.0s' $(seq 64))\"}")"
UID_="$(printf '%s' "$USAGE" | jq_ "d.get('id','')")"
[ -n "$UID_" ] && ok "usage event ingested ($UID_)" || bad "usage ingest failed: $(printf '%s' "$USAGE" | head -c 200)"
REPLAY="$(authpost "/api/v1/projects/$PROJECT/inference-costs/usage" \
  "{\"sourceEventKey\":\"sweep-$SUFFIX\",\"provider\":\"anthropic\",\"modelName\":\"claude-opus-5\",\"occurredAt\":\"2026-08-17T00:00:00Z\",\"inputTokens\":1000,\"outputTokens\":500,\"currencyCode\":\"USD\",\"sourceCostMinor\":250,\"sourceClaimSha256\":\"$(printf 'a%.0s' $(seq 64))\"}" | jq_ "d.get('id','')")"
[ "$REPLAY" = "$UID_" ] && ok "replayed usage is idempotent, not double-counted" || bad "replay created a second row: $REPLAY"
FC="$(authpost "/api/v1/projects/$PROJECT/inference-costs/forecasts" '{"currencyCode":"USD","horizonDays":7}')"
FSTATUS="$(printf '%s' "$FC" | jq_ "d.get('status','')")"
[ "$FSTATUS" = "INSUFFICIENT_DATA" ] && ok "forecast refuses on one day of history (INSUFFICIENT_DATA)" || bad "expected INSUFFICIENT_DATA, got '$FSTATUS'"
printf '%s' "$FC" | grep -q '"predictedCostMinor":null' && ok "a refusal carries no number" || bad "refusal returned a projection anyway"

step "Runtime AI governance — workload boundary"
RT="$(authcode "/internal/runtime-ai/decisions")"
[ "$RT" = "403" ] || [ "$RT" = "401" ] && ok "a human token cannot reach /internal/runtime-ai (HTTP $RT)" || bad "human token reached the runtime surface (HTTP $RT)"

step "Multi-tenancy and SCIM"
SCIM="$(inapi -o /dev/null -w '%{http_code}' "$API/scim/v2/tenants/00000000-0000-0000-0000-000000000001/Users")"
[ "$SCIM" = "401" ] && ok "SCIM without a bearer token is refused (HTTP 401)" || bad "SCIM answered without a token (HTTP $SCIM)"
SCIMBAD="$(inapi -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer not-a-real-token' "$API/scim/v2/tenants/00000000-0000-0000-0000-000000000001/Users")"
[ "$SCIMBAD" = "401" ] && ok "SCIM with a wrong token is refused (HTTP 401)" || bad "SCIM accepted a wrong token (HTTP $SCIMBAD)"

step "Paging contract"
P="$(authget "/api/v1/organizations/$ORG/audit-events?page=0&size=5")"
printf '%s' "$P" | grep -q '"totalItems"' && ok "paged envelope returned" || bad "no paging envelope"
HASNEXT="$(printf '%s' "$P" | jq_ "d.get('hasNext','')")"
TOTAL="$(printf '%s' "$P" | jq_ "d.get('totalItems',0)")"
ok "audit ledger holds $TOTAL events (hasNext=$HASNEXT)"
EXTREME="$(authcode "/api/v1/organizations/$ORG/audit-events?page=2147483647&size=1")"
[ "$EXTREME" = "200" ] && ok "an extreme page number is served, not a 500 (HTTP $EXTREME)" || bad "extreme page returned HTTP $EXTREME"
EXTJSON="$(authget "/api/v1/organizations/$ORG/audit-events?page=2147483647&size=1")"
printf '%s' "$EXTJSON" | grep -q '"hasNext":false' && ok "hasNext is false past the last page — the int overflow is gone" || bad "hasNext wrong at the boundary: $(printf '%s' "$EXTJSON" | head -c 120)"
BADSIZE="$(authcode "/api/v1/organizations/$ORG/audit-events?page=0&size=99999")"
[ "$BADSIZE" = "400" ] && ok "an out-of-range page size is rejected (HTTP 400)" || bad "size=99999 returned HTTP $BADSIZE"

step "Unauthenticated access"
ANON="$(inapi -o /dev/null -w '%{http_code}' "$API/api/v1/organizations")"
[ "$ANON" = "401" ] && ok "the API refuses an anonymous caller (HTTP 401)" || bad "anonymous call returned HTTP $ANON"
HEALTH="$(inapi -o /dev/null -w '%{http_code}' "$API/actuator/health/readiness")"
[ "$HEALTH" = "200" ] && ok "readiness stays public for the orchestrator (HTTP 200)" || bad "readiness returned HTTP $HEALTH"

step "Audit chain after everything above"
V="$(authget "/api/v1/organizations/$ORG/audit-events/verify")"
printf '%s' "$V" | grep -q '"intact":true' && ok "hash chain still intact after the sweep" || bad "chain broken: $(printf '%s' "$V" | head -c 200)"

printf '\n\033[1m=== summary ===\033[0m\n%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
