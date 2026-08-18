#!/bin/sh
# Authenticated synthetic health journey for the P3.1 availability SLI.
#
# It exercises the same path a real caller uses — obtain a client-credentials token, read liveness and readiness, then
# perform one authorized read against the control plane — and writes a Prometheus textfile the node exporter can
# collect. It is a probe, not a load test: one pass, bounded timeouts, no mutation.
#
# The journey never prints or writes the access token, the client secret, or any response body. Only status codes,
# durations, and a pass/fail result leave this script.
set -eu

fail() { echo "synthetic-health-journey: $1" >&2; exit 2; }

: "${AISDLC_SYNTHETIC_TOKEN_URL:?set AISDLC_SYNTHETIC_TOKEN_URL}"
: "${AISDLC_SYNTHETIC_CLIENT_ID:?set AISDLC_SYNTHETIC_CLIENT_ID}"
: "${AISDLC_SYNTHETIC_CLIENT_SECRET:?set AISDLC_SYNTHETIC_CLIENT_SECRET}"
: "${AISDLC_SYNTHETIC_API_BASE_URL:?set AISDLC_SYNTHETIC_API_BASE_URL}"
SERVICE="${AISDLC_TELEMETRY_SERVICE_NAME:-ai-sdlc-management-server}"
ENVIRONMENT="${DEPLOYMENT_ENVIRONMENT:-development}"
TIMEOUT="${AISDLC_SYNTHETIC_TIMEOUT_SECONDS:-10}"
OUTPUT="${AISDLC_SYNTHETIC_TEXTFILE:-/var/lib/node_exporter/textfile_collector/aisdlc_synthetic.prom}"

case "$AISDLC_SYNTHETIC_API_BASE_URL" in
  https://*) ;;
  http://localhost*|http://127.0.0.1*) [ "$ENVIRONMENT" = "development" ] || fail "plain HTTP is only permitted for a development loopback target" ;;
  *) fail "AISDLC_SYNTHETIC_API_BASE_URL must use https outside a development loopback target" ;;
esac

command -v curl >/dev/null 2>&1 || fail "curl is required"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
chmod 700 "$WORK"

started="$(date +%s)"
outcome="bad"
stage="token"
status=0

# The secret is passed on stdin rather than the command line so it never appears in the process table.
printf 'grant_type=client_credentials&client_id=%s&client_secret=%s' \
  "$AISDLC_SYNTHETIC_CLIENT_ID" "$AISDLC_SYNTHETIC_CLIENT_SECRET" > "$WORK/body"
chmod 600 "$WORK/body"

if curl -sS -m "$TIMEOUT" -o "$WORK/token.json" -w '%{http_code}' \
     -H 'Content-Type: application/x-www-form-urlencoded' \
     --data-binary "@$WORK/body" "$AISDLC_SYNTHETIC_TOKEN_URL" > "$WORK/code" 2>/dev/null; then
  status="$(cat "$WORK/code")"
fi
rm -f "$WORK/body"

if [ "$status" = "200" ]; then
  # Extract only the token value; nothing from this file is ever echoed.
  ACCESS_TOKEN="$(sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$WORK/token.json")"
  rm -f "$WORK/token.json"
  if [ -n "$ACCESS_TOKEN" ]; then
    stage="health"
    status="$(curl -sS -m "$TIMEOUT" -o /dev/null -w '%{http_code}' "$AISDLC_SYNTHETIC_API_BASE_URL/actuator/health/readiness" || echo 000)"
    if [ "$status" = "200" ]; then
      stage="authorized-read"
      # /api/v1/organizations, not /api/v1/projects: there is no unscoped project list — project listing is
      # organization-scoped at /api/v1/organizations/{organizationId}/projects. The probe read a route that does not
      # exist, so it returned 404 on a perfectly healthy platform and this availability SLI reported "bad" on every
      # single pass. An indicator that is always red is an indicator nobody acts on.
      #
      # Organizations is the right read for a probe: it exists, it requires a token, and it needs no identifier the
      # probe would otherwise have to be configured with.
      status="$(curl -sS -m "$TIMEOUT" -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $ACCESS_TOKEN" "$AISDLC_SYNTHETIC_API_BASE_URL/api/v1/organizations?page=0&size=1" || echo 000)"
      case "$status" in 200|204) outcome="good" ;; esac
    fi
  fi
fi
unset ACCESS_TOKEN

elapsed="$(( $(date +%s) - started ))"

tmp_out="$WORK/out.prom"
{
  echo "# HELP aisdlc_synthetic_journey_success Whether the last authenticated synthetic journey completed."
  echo "# TYPE aisdlc_synthetic_journey_success gauge"
  echo "aisdlc_synthetic_journey_success{service=\"$SERVICE\",environment=\"$ENVIRONMENT\",journey=\"control-plane-availability\"} $([ "$outcome" = good ] && echo 1 || echo 0)"
  echo "# HELP aisdlc_synthetic_journey_duration_seconds Wall-clock duration of the last synthetic journey."
  echo "# TYPE aisdlc_synthetic_journey_duration_seconds gauge"
  echo "aisdlc_synthetic_journey_duration_seconds{service=\"$SERVICE\",environment=\"$ENVIRONMENT\",journey=\"control-plane-availability\"} $elapsed"
  echo "# HELP aisdlc_sli_events_total Service-level indicator events for a governance journey."
  echo "# TYPE aisdlc_sli_events_total counter"
  echo "aisdlc_sli_events_total{service=\"$SERVICE\",environment=\"$ENVIRONMENT\",journey=\"control-plane-availability\",outcome=\"$outcome\"} 1"
} > "$tmp_out"

if [ -d "$(dirname "$OUTPUT")" ]; then
  mv "$tmp_out" "$OUTPUT"
else
  cat "$tmp_out"
fi

# The failing stage and status code are operational detail; neither carries request or identity content.
echo "synthetic-health-journey: outcome=$outcome stage=$stage status=$status duration=${elapsed}s"
[ "$outcome" = "good" ] || exit 1
