#!/usr/bin/env bash
# Upload a payload written by workbook-to-pages.py into the knowledge base.
#
# Deliberately a separate step from conversion. The workbooks this exists for are frequently confidential, so the
# file that is about to be transmitted should be readable and reviewable first, by a person, with no network involved.
# Converting and uploading in one command removes that opportunity.
#
# Re-running is safe and is the intended way to refresh: a page that already exists gets a new version rather than a
# duplicate or an error, so the history shows what the workbook said before and what it says now, with the change
# note carrying the reason. Nothing is ever overwritten.
#
# The API is not published to the host — end-to-end-acceptance.sh asserts that — so requests go through a container
# on the compose network, the same way the sweeps do.
set -uo pipefail
cd "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

PAYLOAD=""; ORG=""; NOTE="workbook import"
while [ $# -gt 0 ]; do
  case "$1" in
    --payload) PAYLOAD="${2:-}"; shift 2 ;;
    --org)     ORG="${2:-}"; shift 2 ;;
    --note)    NOTE="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$PAYLOAD" ] && [ -r "$PAYLOAD" ] || { echo "usage: $0 --payload pages.json --org <organization-uuid> [--note reason]" >&2; exit 2; }
[ -n "$ORG" ] || { echo "--org is required" >&2; exit 2; }

KC_URL="${AISDLC_ACCEPTANCE_KEYCLOAK_URL:-http://localhost:8180}"
API="http://management-server:8081"
NETWORK="${AISDLC_ACCEPTANCE_NETWORK:-ai-sdlc_platform}"
CURL_IMAGE="curlimages/curl:latest"

value(){ eval "printf '%s' \"\${$1:-}\""; if [ -z "$(eval "printf '%s' \"\${$1:-}\"")" ] && [ -r .env ]; then grep "^$1=" .env | cut -d= -f2-; fi; }
jq_()  { python3 -c "import json,sys
try: d=json.load(sys.stdin)
except Exception: print(''); raise SystemExit
print($1)"; }

TOKEN="$(curl -s -d "grant_type=client_credentials&client_id=aisdlc-cli&client_secret=$(value CLI_CLIENT_SECRET)" \
  "$KC_URL/realms/ai-sdlc/protocol/openid-connect/token" | jq_ "d['access_token']")"
[ -n "$TOKEN" ] || { echo "could not obtain a token" >&2; exit 2; }

api()     { docker run --rm --network "$NETWORK" "$CURL_IMAGE" -s -H "Authorization: Bearer $TOKEN" "$@"; }
send()    { docker run --rm -i --network "$NETWORK" "$CURL_IMAGE" -s -w '\n%{http_code}' -X "$1" \
              -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d @- "$API$2"; }

SPACE_KEY="$(python3 -c "import json,sys; print(json.load(open('$PAYLOAD'))['spaceKey'])")"
echo "space key $SPACE_KEY -> organization $ORG"

# Reuse the space when it already exists, so a re-import lands in the same place rather than beside it.
SPACE="$(api "$API/api/v1/organizations/$ORG/knowledge/spaces?size=100" \
  | jq_ "next((s['id'] for s in d.get('items',[]) if s['spaceKey']=='$SPACE_KEY'), '')")"
if [ -z "$SPACE" ]; then
  SPACE="$(printf '{"spaceKey":"%s","name":"%s","description":"Imported from a spreadsheet."}' "$SPACE_KEY" "$SPACE_KEY" \
    | send POST "/api/v1/organizations/$ORG/knowledge/spaces" | sed '$d' | jq_ "d.get('id','')")"
  [ -n "$SPACE" ] || { echo "could not create the space" >&2; exit 1; }
  echo "created space $SPACE"
else
  echo "reusing space $SPACE"
fi

TOTAL="$(python3 -c "import json; print(len(json.load(open('$PAYLOAD'))['pages']))")"
created=0; updated=0; unchanged=0; failed=0
MAP="$(mktemp)"   # slug -> page id, so a child can be created under its parent

index=0
while [ "$index" -lt "$TOTAL" ]; do
  SLUG="$(python3 -c "import json; print(json.load(open('$PAYLOAD'))['pages'][$index]['slug'])")"
  PARENT_SLUG="$(python3 -c "import json; print(json.load(open('$PAYLOAD'))['pages'][$index].get('_parent') or '')")"
  PARENT_ID=""
  if [ -n "$PARENT_SLUG" ]; then
    PARENT_ID="$(grep "^$PARENT_SLUG	" "$MAP" 2>/dev/null | cut -f2 | head -1)"
  fi

  # The create body and the version body differ, so both are built here from the same page entry.
  # Values reach python through the environment, never interpolated into the source. A change note containing a quote
  # would otherwise end the string and change the program, and macOS ships bash 3.2, which has no ${var@Q}.
  CREATE_BODY="$(PARENT_ID="$PARENT_ID" NOTE="$NOTE" PAYLOAD="$PAYLOAD" INDEX="$index" python3 -c "
import json, os
page = json.load(open(os.environ['PAYLOAD']))['pages'][int(os.environ['INDEX'])]
body = {k: page[k] for k in ('slug','title','body','labels') if k in page}
body['changeNote'] = os.environ['NOTE'].strip() or 'workbook import'
parent = os.environ.get('PARENT_ID') or ''
if parent: body['parentPageId'] = parent
print(json.dumps(body, ensure_ascii=False))")"

  RESULT="$(printf '%s' "$CREATE_BODY" | send POST "/api/v1/organizations/$ORG/knowledge/spaces/$SPACE/pages")"
  CODE="$(printf '%s' "$RESULT" | tail -1)"
  BODY="$(printf '%s' "$RESULT" | sed '$d')"

  if [ "$CODE" = "201" ]; then
    PAGE_ID="$(printf '%s' "$BODY" | jq_ "d.get('id','')")"
    printf '%s\t%s\n' "$SLUG" "$PAGE_ID" >> "$MAP"
    created=$((created+1))
    printf '  created  %-40s %s chunks\n' "$SLUG" "$(printf '%s' "$BODY" | jq_ "d.get('chunkCount',0)")"
  elif [ "$CODE" = "409" ]; then
    # Already present: author a new version instead. Its id has to be looked up, since create did not return one.
    PAGE_ID="$(api "$API/api/v1/organizations/$ORG/knowledge/spaces/$SPACE/pages?includeArchived=true" \
      | jq_ "next((p['id'] for p in d if p['slug']=='$SLUG'), '')")"
    if [ -z "$PAGE_ID" ]; then
      failed=$((failed+1)); printf '  FAILED   %-40s conflict, but the page could not be found\n' "$SLUG"
    else
      printf '%s\t%s\n' "$SLUG" "$PAGE_ID" >> "$MAP"
      VERSION_BODY="$(NOTE="$NOTE" PAYLOAD="$PAYLOAD" INDEX="$index" python3 -c "
import json, os
page = json.load(open(os.environ['PAYLOAD']))['pages'][int(os.environ['INDEX'])]
print(json.dumps({'title': page['title'], 'body': page['body'],
                  'changeNote': os.environ['NOTE'].strip() or 'workbook import'}, ensure_ascii=False))")"
      VR="$(printf '%s' "$VERSION_BODY" | send PUT "/api/v1/organizations/$ORG/knowledge/pages/$PAGE_ID")"
      VC="$(printf '%s' "$VR" | tail -1)"
      case "$VC" in
        200) updated=$((updated+1))
             printf '  updated  %-40s now version %s\n' "$SLUG" "$(printf '%s' "$VR" | sed '$d' | jq_ "d.get('version','?')")" ;;
        409) unchanged=$((unchanged+1)); printf '  same     %-40s identical to the current version\n' "$SLUG" ;;
        *)   failed=$((failed+1)); printf '  FAILED   %-40s HTTP %s on version\n' "$SLUG" "$VC" ;;
      esac
    fi
  else
    failed=$((failed+1))
    printf '  FAILED   %-40s HTTP %s %s\n' "$SLUG" "$CODE" "$(printf '%s' "$BODY" | head -c 160)"
  fi
  index=$((index+1))
done
rm -f "$MAP"

printf '\n%s created, %s updated, %s unchanged, %s failed (of %s pages)\n' \
  "$created" "$updated" "$unchanged" "$failed" "$TOTAL"
[ "$failed" -eq 0 ] || exit 1
