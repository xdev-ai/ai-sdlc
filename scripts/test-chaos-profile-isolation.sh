#!/bin/sh
# Static guardrails for the chaos tier.
#
# The chaos fault registry is a deliberate seam in production code, so the safety property is not "it is absent" but
# "it can only be reached through an explicit, isolated profile that no shipped configuration turns on". This script
# proves that statically, before the isolated tests run, and needs no JVM, Docker, or network.
set -eu

ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
REGISTRY="$ROOT/management-server/src/main/java/ai/xdev/aisdlc/service/ChaosFaultRegistry.java"
APP_CONFIG="$ROOT/management-server/src/main/resources/application.yml"
COMPOSE="$ROOT/docker-compose.yml"
COMPOSE_PROD="$ROOT/docker-compose.production.yml"

failures=0
fail() { echo "FAIL: $1" >&2; failures=$((failures + 1)); }
pass() { echo "ok: $1"; }

[ -r "$REGISTRY" ] || { echo "FAIL: $REGISTRY is missing" >&2; exit 1; }

# 1. The registry bean exists only under the explicit profile.
grep -q '@Profile("chaos")' "$REGISTRY" && pass "fault registry is gated on the explicit chaos profile" \
  || fail "fault registry is not annotated @Profile(\"chaos\")"

# 2. No shipped configuration activates the profile.
for f in "$APP_CONFIG" "$COMPOSE" "$COMPOSE_PROD"; do
  [ -r "$f" ] || continue
  if grep -nE 'profiles?[^#]*chaos|SPRING_PROFILES_ACTIVE[^#]*chaos' "$f" >/dev/null 2>&1; then
    fail "$(basename "$f") activates the chaos profile"
  else
    pass "$(basename "$f") does not activate the chaos profile"
  fi
done

# 3. No HTTP surface can enable a fault. An endpoint that accepts fault parameters would make chaos reachable in a
#    deployment where the profile happened to be on.
if grep -rl 'ChaosFaultRegistry' "$ROOT/management-server/src/main/java/ai/xdev/aisdlc/web" >/dev/null 2>&1; then
  fail "a web controller references the chaos fault registry"
else
  pass "no web controller can enable a fault"
fi

# 4. The registry injects only deterministic, in-process outcomes. A destructive verb here would mean the seam can do
#    more than fail a call.
for verb in 'Runtime.getRuntime' 'ProcessBuilder' 'exec(' 'delete' 'drop ' 'truncate' 'shutdown' 'System.exit'; do
  if grep -q -- "$verb" "$REGISTRY"; then
    fail "fault registry contains a destructive operation: $verb"
  else
    pass "fault registry contains no '$verb'"
  fi
done

# 5. Every declared component is covered by a seam in production code, otherwise a scenario silently tests nothing.
components="$(sed -n 's/.*enum Component *{ *\(.*\) *}.*/\1/p' "$REGISTRY" | tr -d ' ' | tr ',' ' ')"
[ -n "$components" ] || fail "could not read the Component enum"
for component in $components; do
  if grep -rq "Component.$component" "$ROOT/management-server/src/main/java" --include='*.java' \
       --exclude=ChaosFaultRegistry.java; then
    pass "component $component has a seam in production code"
  else
    fail "component $component is declared but never checked; scenarios for it would pass vacuously"
  fi
done

if [ "$failures" -gt 0 ]; then
  echo "$failures chaos isolation check(s) failed" >&2
  exit 1
fi
echo "All chaos isolation checks passed"
