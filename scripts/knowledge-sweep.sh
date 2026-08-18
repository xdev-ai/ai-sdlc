#!/usr/bin/env bash
# Knowledge base sweep against the LIVE running stack.
#
# The schema was verified by attacking it with SQL, and the chunker by unit tests. Neither says whether the endpoints
# work: every defect found in this repository this week — an Instant bound positionally to a JdbcTemplate, an int
# overflow in a page response, a template reading a map key that never existed — compiled, passed its unit tests, and
# failed on the first real HTTP call. So this calls the API.
#
# What it checks, beyond happy paths:
#   * history survives an edit (the previous body is still readable at its own version)
#   * retrieval reads the CURRENT version only, so superseded wording cannot ground an answer
#   * accent-folded search works ("tiep nhan" finds "tiếp nhận"), which is the whole point for Vietnamese content
#   * a hostile query of tsquery operators returns a result, not a 500
#   * organization scope holds when a page id is known but the organization is not the caller's
set -uo pipefail
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
authput()  { inapi -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }
putcode()  { inapi -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2" "$API$1"; }
delcode()  { inapi -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $TOKEN" "$API$1"; }

TOKEN="$(curl -s -d "grant_type=client_credentials&client_id=aisdlc-cli&client_secret=$(value CLI_CLIENT_SECRET)" \
  "$KC_URL/realms/ai-sdlc/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$TOKEN" ] || { echo "no token — run end-to-end-acceptance.sh first to elevate the service account"; exit 2; }

ORG="$(authget '/api/v1/organizations?page=0&size=1' | jq_ "d['items'][0]['id']")"
[ -n "$ORG" ] || { echo "no organization visible to this token"; exit 2; }
SUFFIX="$(date +%s)"
KEY="SWEEP$SUFFIX"
echo "organization $ORG / space key $KEY"

# ---------------------------------------------------------------------------------------------------------------
step "Spaces"

# An early failure used to leave its space behind, un-archived, where it then showed up in every later listing and in
# the agent-rules bundle as a real documentation space with no pages. A sweep that litters the environment it measures
# makes the next run harder to read, so the space is archived on any exit path.
CREATED_SPACE=""
cleanup_space() {
  if [ -n "$CREATED_SPACE" ]; then
    delcode "/api/v1/organizations/$ORG/knowledge/spaces/$CREATED_SPACE" >/dev/null 2>&1 || true
    printf '  \033[33m--\033[0m    archived the sweep space on exit (%s)\n' "$CREATED_SPACE"
  fi
}
trap cleanup_space EXIT

SPACE_JSON="$(authpost "/api/v1/organizations/$ORG/knowledge/spaces" \
  "{\"spaceKey\":\"$KEY\",\"name\":\"Sweep space\",\"description\":\"created by knowledge-sweep.sh\"}")"
SPACE="$(printf '%s' "$SPACE_JSON" | jq_ "d.get('id','')")"
[ -n "$SPACE" ] && ok "space created ($SPACE)" || bad "space create failed: $(printf '%s' "$SPACE_JSON" | head -c 300)"
[ -z "$SPACE" ] && { echo "cannot continue without a space"; exit 1; }
CREATED_SPACE="$SPACE"

DUP="$(postcode "/api/v1/organizations/$ORG/knowledge/spaces" "{\"spaceKey\":\"$KEY\",\"name\":\"Duplicate\"}")"
[ "$DUP" = "409" ] && ok "a duplicate space key is refused (409)" || bad "duplicate space key returned $DUP, expected 409"

BADKEY="$(postcode "/api/v1/organizations/$ORG/knowledge/spaces" '{"spaceKey":"-bad key","name":"Invalid"}')"
[ "$BADKEY" = "400" ] && ok "an invalid space key is a 400, not a 500 from the check constraint" \
  || bad "invalid space key returned $BADKEY, expected 400"

# ---------------------------------------------------------------------------------------------------------------
step "Pages, versions, and history"

PARENT_PAYLOAD="$(python3 - <<'PY'
import json
print(json.dumps({
  "slug": "tiep-nhan",
  "title": "Tiếp nhận người bệnh",
  "body": "# Tiếp nhận người bệnh\n\nNhân viên tiếp nhận kiểm tra giấy tờ. Bản gốc yêu cầu THONGTINCU trước khi khám.\n\n## Kiểm tra bảo hiểm\n\nXác minh thẻ bảo hiểm y tế của người bệnh.\n",
  "changeNote": "bản đầu tiên",
  "labels": ["quy-trinh", "tiep-nhan"],
}, ensure_ascii=False))
PY
)"
PARENT_JSON="$(authpost "/api/v1/organizations/$ORG/knowledge/spaces/$SPACE/pages" "$PARENT_PAYLOAD")"
PAGE="$(printf '%s' "$PARENT_JSON" | jq_ "d.get('id','')")"
CHUNKS="$(printf '%s' "$PARENT_JSON" | jq_ "d.get('chunkCount',0)")"
[ -n "$PAGE" ] && ok "page created ($PAGE)" || bad "page create failed: $(printf '%s' "$PARENT_JSON" | head -c 300)"
[ -z "$PAGE" ] && { echo "cannot continue without a page"; exit 1; }
[ "${CHUNKS:-0}" -ge 2 ] && ok "the body was chunked on creation ($CHUNKS chunks)" \
  || bad "expected at least 2 chunks from two sections, got ${CHUNKS:-0}"
LABELS="$(printf '%s' "$PARENT_JSON" | jq_ "','.join(d.get('labels',[]))")"
[ "$LABELS" = "quy-trinh,tiep-nhan" ] && ok "labels applied at creation ($LABELS)" || bad "labels are '$LABELS'"

CHILD_PAYLOAD="$(python3 - <<PY
import json
print(json.dumps({
  "slug": "tiep-nhan-noi-tru",
  "parentPageId": "$PAGE",
  "title": "Tiếp nhận nội trú",
  "body": "## Nhập viện\n\nLập hồ sơ nội trú và phân giường.\n",
}, ensure_ascii=False))
PY
)"
CHILD_JSON="$(authpost "/api/v1/organizations/$ORG/knowledge/spaces/$SPACE/pages" "$CHILD_PAYLOAD")"
CHILD="$(printf '%s' "$CHILD_JSON" | jq_ "d.get('id','')")"
[ -n "$CHILD" ] && ok "child page created under the parent" || bad "child create failed: $(printf '%s' "$CHILD_JSON" | head -c 300)"
CRUMB="$(printf '%s' "$CHILD_JSON" | jq_ "' > '.join(d.get('breadcrumb',[]))")"
[ "$CRUMB" = "tiep-nhan > tiep-nhan-noi-tru" ] && ok "breadcrumb is root-first ($CRUMB)" || bad "breadcrumb is '$CRUMB'"

TREE="$(authget "/api/v1/organizations/$ORG/knowledge/spaces/$SPACE/pages")"
DEPTHS="$(printf '%s' "$TREE" | python3 -c "import json,sys
d=json.load(sys.stdin)
print(','.join('%s@%s' % (n['slug'], n['depth']) for n in d))")"
[ "$DEPTHS" = "tiep-nhan@0,tiep-nhan-noi-tru@1" ] && ok "tree is ordered parents-before-children with depths ($DEPTHS)" \
  || bad "tree came back as '$DEPTHS'"

V2_PAYLOAD="$(python3 - <<'PY'
import json
print(json.dumps({
  "title": "Tiếp nhận người bệnh",
  "body": "# Tiếp nhận người bệnh\n\nNhân viên tiếp nhận kiểm tra giấy tờ tùy thân.\n\n## Kiểm tra bảo hiểm\n\nXác minh thẻ bảo hiểm y tế và tra cứu cổng thông tin.\n",
  "changeNote": "bỏ yêu cầu cũ, thêm tra cứu cổng thông tin",
}, ensure_ascii=False))
PY
)"
V2_JSON="$(authput "/api/v1/organizations/$ORG/knowledge/pages/$PAGE" "$V2_PAYLOAD")"
V2="$(printf '%s' "$V2_JSON" | jq_ "d.get('version',0)")"
[ "$V2" = "2" ] && ok "authoring produced version 2" || bad "expected version 2, got '$V2': $(printf '%s' "$V2_JSON" | head -c 300)"

SAME="$(putcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE" "$V2_PAYLOAD")"
[ "$SAME" = "409" ] && ok "re-authoring identical content is refused (409), so history stays meaningful" \
  || bad "identical re-author returned $SAME, expected 409"

HISTORY="$(authget "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/versions")"
COUNT="$(printf '%s' "$HISTORY" | jq_ "d.get('totalItems',0)")"
CURRENT="$(printf '%s' "$HISTORY" | jq_ "','.join('v%s:%s' % (i['version'], i['current']) for i in d.get('items',[]))")"
[ "$COUNT" = "2" ] && ok "history has both versions" || bad "history reports $COUNT versions"
[ "$CURRENT" = "v2:True,v1:False" ] && ok "the current version is flagged, newest first ($CURRENT)" || bad "history flags: $CURRENT"

OLD_BODY="$(authget "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/versions/1" | jq_ "d.get('body','')")"
case "$OLD_BODY" in
  *THONGTINCU*) ok "version 1 text is still readable after being superseded" ;;
  *) bad "version 1 body did not survive: $(printf '%s' "$OLD_BODY" | head -c 120)" ;;
esac

# ---------------------------------------------------------------------------------------------------------------
step "Retrieval"

HIT="$(authget "/api/v1/organizations/$ORG/knowledge/search?q=tiep%20nhan&spaceKey=$KEY")"
HITS="$(printf '%s' "$HIT" | jq_ "len(d)")"
MATCHED="$(printf '%s' "$HIT" | jq_ "d[0]['matchedBy'] if d else ''")"
[ "${HITS:-0}" -ge 1 ] && ok "an unaccented query finds accented content ($HITS hits)" \
  || bad "'tiep nhan' found nothing — accent folding is not working end to end"
[ "$MATCHED" = "keyword" ] && ok "it matched on the indexed keyword path, not the fuzzy fallback" \
  || bad "matchedBy was '$MATCHED'"
HEADING="$(printf '%s' "$HIT" | jq_ "d[0]['headingPath'] if d else ''")"
case "$HEADING" in
  *"Tiếp nhận"*) ok "the hit carries a heading path for citation ($HEADING)" ;;
  *) bad "heading path is '$HEADING'" ;;
esac

SUPERSEDED="$(authget "/api/v1/organizations/$ORG/knowledge/search?q=THONGTINCU&spaceKey=$KEY" | jq_ "len(d)")"
[ "${SUPERSEDED:-1}" = "0" ] && ok "wording that version 2 removed is no longer retrievable" \
  || bad "superseded text is still searchable ($SUPERSEDED hits) — an answer could be grounded in replaced wording"

HOSTILE="$(authcode "/api/v1/organizations/$ORG/knowledge/search?q=%26%26%7C%21%3A%2A&spaceKey=$KEY")"
[ "$HOSTILE" = "200" ] && ok "a query of bare tsquery operators returns 200, not a 500" \
  || bad "hostile query returned $HOSTILE"

EMPTYQ="$(authcode "/api/v1/organizations/$ORG/knowledge/search?q=&spaceKey=$KEY")"
[ "$EMPTYQ" = "400" ] && ok "an empty query is a 400 naming the problem" || bad "empty query returned $EMPTYQ"

LABELLED="$(authget "/api/v1/organizations/$ORG/knowledge/search?q=bao%20hiem&label=quy-trinh" | jq_ "len(d)")"
[ "${LABELLED:-0}" -ge 1 ] && ok "the label filter narrows without losing the match ($LABELLED hits)" \
  || bad "label-filtered search found nothing"
MISLABEL="$(authget "/api/v1/organizations/$ORG/knowledge/search?q=bao%20hiem&label=khong-ton-tai" | jq_ "len(d)")"
[ "${MISLABEL:-1}" = "0" ] && ok "an unmatched label really excludes" || bad "unmatched label still returned $MISLABEL hits"

CTX="$(authget "/api/v1/organizations/$ORG/knowledge/context?q=bao%20hiem&spaceKey=$KEY&budgetChars=200")"
USED="$(printf '%s' "$CTX" | jq_ "d.get('usedChars',-1)")"
CITED="$(printf '%s' "$CTX" | jq_ "d['citations'][0] if d.get('citations') else ''")"
CAVEAT="$(printf '%s' "$CTX" | jq_ "d.get('caveat','')")"
[ "${USED:-9999}" -le 200 ] && ok "the context bundle respects its character budget ($USED of 200)" \
  || bad "bundle used $USED characters against a 200 budget"
case "$CITED" in
  "$KEY/tiep-nhan v2 § "*) ok "each chunk arrives with a citation ($CITED)" ;;
  *) bad "citation is '$CITED'" ;;
esac
case "$CAVEAT" in
  *lexical*) ok "the response states that retrieval is lexical, not semantic" ;;
  *) bad "no caveat in the bundle" ;;
esac
OVERBUDGET="$(authcode "/api/v1/organizations/$ORG/knowledge/context?q=bao%20hiem&budgetChars=999999")"
[ "$OVERBUDGET" = "400" ] && ok "an unbounded budget request is refused" || bad "huge budget returned $OVERBUDGET"

# ---------------------------------------------------------------------------------------------------------------
step "References to governed artifacts"

# The kit is registered here rather than borrowed from existing data. The first version of this sweep looked for one
# and skipped when the organization had none — which is what happened, so the entire reference path reported nothing
# at all and read as if it had been covered. A check that can silently skip itself is not a check.
KIT="$(authpost "/api/v1/organizations/$ORG/spec-kits" \
  "{\"slug\":\"sweep-kit-$SUFFIX\",\"version\":\"1.0.0\",\"layer\":\"EXTENSION\",\"manifest\":\"{}\"}" | jq_ "d.get('id','')")"
[ -n "$KIT" ] && ok "registered a Spec Kit to cite ($KIT)" || bad "could not register a Spec Kit; the reference path is untested"

if [ -n "$KIT" ]; then
  REF="$(authpost "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/references" \
    "{\"specKitId\":\"$KIT\",\"referenceNote\":\"the kit this page documents\"}")"
  TYPE="$(printf '%s' "$REF" | jq_ "d[0]['targetType'] if d else ''")"
  [ "$TYPE" = "SPEC_KIT" ] && ok "a page cites a Spec Kit" || bad "reference came back as '$TYPE': $(printf '%s' "$REF" | head -c 200)"
  TARGET="$(printf '%s' "$REF" | jq_ "d[0]['targetLabel'] if d else ''")"
  [ "$TARGET" = "sweep-kit-$SUFFIX 1.0.0" ] && ok "the reference resolves the target's name, not just its id ($TARGET)" \
    || bad "targetLabel is '$TARGET'"

  SEEN="$(authget "/api/v1/organizations/$ORG/knowledge/pages/$PAGE" | jq_ "len(d.get('references',[]))")"
  [ "${SEEN:-0}" = "1" ] && ok "the page detail carries its references" || bad "page detail shows $SEEN references"

  TWO="$(postcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/references" \
    "{\"specKitId\":\"$KIT\",\"traceNodeId\":\"$KIT\"}")"
  [ "$TWO" = "400" ] && ok "two targets on one reference is a 400" || bad "two targets returned $TWO"

  NONE="$(postcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/references" '{"referenceNote":"nothing"}')"
  [ "$NONE" = "400" ] && ok "zero targets is a 400" || bad "zero targets returned $NONE"

  FOREIGN="$(postcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/references" \
    '{"specKitId":"00000000-0000-0000-0000-0000000000ff"}')"
  [ "$FOREIGN" = "400" ] && ok "a kit outside the organization cannot be cited" || bad "foreign kit returned $FOREIGN"
fi

# ---------------------------------------------------------------------------------------------------------------
step "The fuzzy fallback, and two authors at once"

# The trigram branch builds a different argument list from the keyword branch, and positional binding is exactly where
# this repository has been bitten before. A misspelling with correct diacritics misses the keyword index — "hiemm" is
# not "hiem" after folding — so it is the query that forces the fallback to run.
FUZZY="$(authget "/api/v1/organizations/$ORG/knowledge/search?q=b%E1%BA%A3o%20hi%E1%BB%83mm&spaceKey=$KEY")"
FUZZY_HITS="$(printf '%s' "$FUZZY" | jq_ "len(d)")"
FUZZY_BY="$(printf '%s' "$FUZZY" | jq_ "d[0]['matchedBy'] if d else ''")"
[ "${FUZZY_HITS:-0}" -ge 1 ] && ok "a misspelled query still finds the section ($FUZZY_HITS hits)" \
  || bad "the trigram fallback found nothing — the branch may never have run"
[ "$FUZZY_BY" = "trigram" ] && ok "and it is reported as a fuzzy match, not passed off as a keyword hit" \
  || bad "matchedBy was '$FUZZY_BY', expected trigram"
FUZZY_STRATEGY="$(authget "/api/v1/organizations/$ORG/knowledge/context?q=b%E1%BA%A3o%20hi%E1%BB%83mm&spaceKey=$KEY" | jq_ "d.get('strategy','')")"
[ "$FUZZY_STRATEGY" = "trigram-fallback" ] && ok "the context bundle names the strategy it used" \
  || bad "bundle strategy was '$FUZZY_STRATEGY'"

# Two authors saving at once must both end up with a version. This asserts the user-visible outcome; it cannot prove
# the row lock was actually contended, since two container starts may well serialise on their own.
A_FILE="$(mktemp)"; B_FILE="$(mktemp)"
putcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE" \
  '{"title":"Tiếp nhận người bệnh","body":"# Tiếp nhận\n\nSửa đồng thời A.\n","changeNote":"A"}' > "$A_FILE" &
putcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE" \
  '{"title":"Tiếp nhận người bệnh","body":"# Tiếp nhận\n\nSửa đồng thời B.\n","changeNote":"B"}' > "$B_FILE" &
wait
A_CODE="$(cat "$A_FILE")"; B_CODE="$(cat "$B_FILE")"; rm -f "$A_FILE" "$B_FILE"
[ "$A_CODE" = "200" ] && [ "$B_CODE" = "200" ] \
  && ok "two concurrent authors both succeeded (HTTP $A_CODE and $B_CODE)" \
  || bad "concurrent authoring returned $A_CODE and $B_CODE; one lost its edit to a constraint"
FINAL="$(authget "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/versions" | jq_ "d.get('totalItems',0)")"
[ "$FINAL" = "4" ] && ok "both edits became their own version (4 in history, none overwritten)" \
  || bad "history holds $FINAL versions after two concurrent edits, expected 4"

# ---------------------------------------------------------------------------------------------------------------
step "Hierarchy and scope"

CYCLE="$(putcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/parent" "{\"parentPageId\":\"$CHILD\"}")"
[ "$CYCLE" = "409" ] && ok "moving a page under its own child is refused (409), not a 500 from the trigger" \
  || bad "cycle move returned $CYCLE, expected 409"

SELF="$(putcode "/api/v1/organizations/$ORG/knowledge/pages/$PAGE/parent" "{\"parentPageId\":\"$PAGE\"}")"
case "$SELF" in 400|409) ok "a page cannot be its own parent (HTTP $SELF)" ;; *) bad "self-parent returned $SELF" ;; esac

WRONGORG="$(authcode "/api/v1/organizations/00000000-0000-0000-0000-0000000000ff/knowledge/pages/$PAGE")"
[ "$WRONGORG" = "400" ] || [ "$WRONGORG" = "404" ] \
  && ok "a known page id is not readable under another organization (HTTP $WRONGORG)" \
  || bad "cross-organization read returned $WRONGORG"

ANON="$(inapi -o /dev/null -w '%{http_code}' "$API/api/v1/organizations/$ORG/knowledge/spaces")"
[ "$ANON" = "401" ] && ok "unauthenticated access is refused" || bad "anonymous request returned $ANON"

UPPER="$(postcode "/api/v1/organizations/$ORG/knowledge/spaces/$SPACE/pages" \
  '{"slug":"Not-Lowercase","title":"x","body":"y"}')"
[ "$UPPER" = "400" ] && ok "an uppercase slug is a 400 before it reaches the check constraint" \
  || bad "uppercase slug returned $UPPER"

# ---------------------------------------------------------------------------------------------------------------
step "Governing rules an AI agent is handed"

# The rules bundle is what every AI assistant on every developer machine reads. If it drifts, agents on different
# machines work to different rules, which is the one failure this endpoint exists to prevent.
RULES_PROJECT="$(authget "/api/v1/organizations/$ORG/projects?page=0&size=1" | jq_ "d['items'][0]['id'] if d.get('items') else ''")"
if [ -z "$RULES_PROJECT" ]; then
  bad "no project in this organization, so the agent-rules bundle is untested"
else
  RULES="$(authget "/api/v1/projects/$RULES_PROJECT/agent-rules")"
  INVARIANTS="$(printf '%s' "$RULES" | jq_ "len(d.get('invariants',[]))")"
  [ "${INVARIANTS:-0}" -ge 5 ] && ok "the bundle carries the platform invariants ($INVARIANTS)" \
    || bad "invariants missing from the rules bundle: $(printf '%s' "$RULES" | head -c 200)"

  ORGID="$(printf '%s' "$RULES" | jq_ "d.get('organizationId','')")"
  [ "$ORGID" = "$ORG" ] && ok "it carries organizationId, so a client knowing only a project can reach the docs" \
    || bad "organizationId is '$ORGID', expected $ORG"

  COMPLETENESS="$(printf '%s' "$RULES" | jq_ "d.get('completeness','')")"
  case "$COMPLETENESS" in
    COMPLETE|PARTIAL|UNCONFIGURED) ok "configuration state is reported as $COMPLETENESS, not left for the caller to guess" ;;
    *) bad "completeness is '$COMPLETENESS'" ;;
  esac

  SPACES="$(printf '%s' "$RULES" | jq_ "len(d.get('knowledgeSpaces',[]))")"
  [ "${SPACES:-0}" -ge 1 ] && ok "documentation spaces are listed for retrieval ($SPACES)" \
    || bad "the bundle lists no documentation space, though this sweep created one"

  MD="$(authget "/api/v1/projects/$RULES_PROJECT/agent-rules/markdown")"
  case "$MD" in
    *"Platform invariants"*) ok "the Markdown rendering is produced by the server, not by each client" ;;
    *) bad "markdown bundle looks wrong: $(printf '%s' "$MD" | head -c 160)" ;;
  esac
  case "$MD" in
    *"lexical, not semantic"*) ok "the Markdown states that retrieval is lexical, where an agent will read it" ;;
    *) bad "the lexical caveat is absent from the agent-facing text" ;;
  esac

  FOREIGN="$(authcode "/api/v1/projects/00000000-0000-0000-0000-0000000000ff/agent-rules")"
  [ "$FOREIGN" = "400" ] || [ "$FOREIGN" = "404" ] \
    && ok "an unknown project is refused (HTTP $FOREIGN)" || bad "unknown project returned $FOREIGN"
fi

# ---------------------------------------------------------------------------------------------------------------
step "Archiving"

ARCHIVED="$(delcode "/api/v1/organizations/$ORG/knowledge/spaces/$SPACE")"
[ "$ARCHIVED" = "200" ] && ok "space archived" || bad "archive returned $ARCHIVED"
CREATED_SPACE=""

AFTER="$(postcode "/api/v1/organizations/$ORG/knowledge/spaces/$SPACE/pages" \
  '{"slug":"too-late","title":"x","body":"y"}')"
[ "$AFTER" = "409" ] && ok "an archived space refuses new pages" || bad "page create in archived space returned $AFTER"

GONE="$(authget "/api/v1/organizations/$ORG/knowledge/search?q=tiep%20nhan&spaceKey=$KEY" | jq_ "len(d)")"
[ "${GONE:-1}" = "0" ] && ok "an archived space drops out of retrieval" || bad "archived space still returns $GONE hits"

LISTED="$(authget "/api/v1/organizations/$ORG/knowledge/spaces?includeArchived=true&size=100" \
  | jq_ "sum(1 for s in d.get('items',[]) if s['spaceKey']=='$KEY')")"
[ "$LISTED" = "1" ] && ok "it is still listed with includeArchived=true, because nothing was deleted" \
  || bad "archived space is not listed even with includeArchived=true"

printf '\n\033[1m%s passed, %s failed\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
