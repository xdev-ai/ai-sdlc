#!/bin/sh
# Offline contract tests for the P3.1 observability configuration.
#
# These assert properties that YAML validity and `otelcol validate` cannot: that the Collector still bounds memory and
# queues, that redaction is still in every pipeline, that mTLS is still required, that the SLO rules and the
# application agree on one label set, and that the Compose gateway stays opt-in. They need no Docker, no network, and
# no running Collector, so they run on every pull request alongside the image-backed validation.
set -eu

ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
COLLECTOR="$ROOT/infra/observability/otelcol-gateway.yaml"
DEFINITIONS="$ROOT/infra/observability/p3-slo-definitions.yaml"
BURN="$ROOT/infra/observability/p3-slo-burn-rate-rules.yaml"
ROUTES="$ROOT/infra/observability/alertmanager-routes.yaml"
CONTRACT="$ROOT/management-server/src/main/java/ai/xdev/aisdlc/telemetry/TelemetryAttributeContract.java"
COMPOSE="$ROOT/docker-compose.yml"
RUNBOOKS="$ROOT/docs/slo-runbooks.md"

failures=0
fail() { echo "FAIL: $1" >&2; failures=$((failures + 1)); }
pass() { echo "ok: $1"; }
want() { grep -q -- "$2" "$1" && pass "$3" || fail "$3"; }
reject() { grep -q -- "$2" "$1" && fail "$3" || pass "$3"; }

for f in "$COLLECTOR" "$DEFINITIONS" "$BURN" "$ROUTES" "$CONTRACT" "$COMPOSE" "$RUNBOOKS"; do
  [ -r "$f" ] || fail "missing required file: $f"
done
[ "$failures" -eq 0 ] || { echo "$failures missing file(s)" >&2; exit 1; }

# --- Collector resilience controls -------------------------------------------------------------------------------
want "$COLLECTOR" 'memory_limiter' "collector bounds memory before batching"
want "$COLLECTOR" 'sending_queue' "collector bounds its exporter queue"
want "$COLLECTOR" 'retry_on_failure' "collector retries with bounded backoff"
want "$COLLECTOR" 'transform/redact' "collector applies the redaction transform"
want "$COLLECTOR" 'client_ca_file' "collector requires a client certificate authority"
reject "$COLLECTOR" 'insecure: true' "collector never disables transport security"

# Redaction and memory limiting must be in every pipeline, not merely defined.
pipelines="$(awk '/^  pipelines:/,0' "$COLLECTOR" | grep -c 'transform/redact' || true)"
[ "${pipelines:-0}" -ge 3 ] && pass "redaction is wired into every signal pipeline" \
  || fail "redaction appears in only ${pipelines:-0} pipeline(s); traces, metrics, and logs all need it"
limiters="$(awk '/^  pipelines:/,0' "$COLLECTOR" | grep -c 'memory_limiter' || true)"
[ "${limiters:-0}" -ge 3 ] && pass "memory limiting is wired into every signal pipeline" \
  || fail "memory_limiter appears in only ${limiters:-0} pipeline(s)"

# --- Cardinality and privacy --------------------------------------------------------------------------------------
for banned in 'tenant' 'project' 'user' 'session' 'trace_id' 'request_id'; do
  if grep -E '^\s+by \(.*'"$banned" "$BURN" >/dev/null 2>&1; then
    fail "burn-rate rules group by the unbounded label '$banned'"
  else
    pass "burn-rate rules do not group by '$banned'"
  fi
done

# The rules aggregate by exactly the labels the application is allowed to emit.
if grep -q 'by (service, environment, journey)' "$BURN"; then
  pass "burn-rate rules aggregate by the published SLI label set"
else
  fail "burn-rate rules no longer aggregate by (service, environment, journey)"
fi
for label in service environment journey outcome; do
  want "$CONTRACT" "\"$label\"" "application contract still declares the '$label' SLI label"
done

# --- SLO definitions -------------------------------------------------------------------------------------------
want "$DEFINITIONS" 'aisdlc_slo_target_ratio' "SLO definitions publish the target ratio the burn rules divide by"
want "$DEFINITIONS" 'budget_policy: integrity' "integrity objectives are labelled as such"
want "$BURN" 'budget_policy="error-budget"' "burn-rate alerting excludes integrity objectives"

# Every journey with a target must have a runbook anchor, or the alert links nowhere.
for journey in $(grep -oE 'journey: [a-z-]+' "$DEFINITIONS" | awk '{print $2}' | sort -u); do
  grep -q "^## $journey" "$RUNBOOKS" && pass "runbook section exists for '$journey'" \
    || fail "docs/slo-runbooks.md has no '## $journey' section for the alert link"
done
grep -q '^## governance-integrity' "$RUNBOOKS" && pass "runbook section exists for 'governance-integrity'" \
  || fail "docs/slo-runbooks.md has no '## governance-integrity' section"

# --- Alert routing ------------------------------------------------------------------------------------------------
want "$ROUTES" 'alert_class = governance-integrity' "integrity violations have a dedicated route"
want "$ROUTES" 'inhibit_rules' "routing suppresses redundant burn alerts during an integrity incident"
reject "$ROUTES" 'Bearer ' "routing configuration embeds no bearer token"
for secretish in 'password:' 'api_key' 'slack.com/services'; do
  reject "$ROUTES" "$secretish" "routing configuration embeds no committed secret ($secretish)"
done

# --- Compose gateway stays opt-in -----------------------------------------------------------------------------
want "$COMPOSE" 'profiles: \[observability\]' "collector service is behind an opt-in Compose profile"
want "$COMPOSE" 'otel/opentelemetry-collector-contrib@sha256:' "collector image is digest-pinned"

if [ "$failures" -gt 0 ]; then
  echo "$failures observability contract check(s) failed" >&2
  exit 1
fi
echo "All observability contract checks passed"
