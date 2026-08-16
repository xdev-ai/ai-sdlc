#!/bin/sh
# Contract tests for the Helm chart's hardened defaults.
#
# A chart that renders is not a chart that is safe. These assertions pin the security posture of the DEFAULT values,
# so weakening it — dropping a security context, publishing the management API, admitting a mutable image tag —
# fails the build instead of shipping quietly. Rendering needs Helm; the guard assertions that do not need a
# rendered manifest run either way.
set -eu

ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
CHART="$ROOT/infra/helm/ai-sdlc"
HELM_IMAGE="${HELM_IMAGE:-alpine/helm@sha256:76c375eed56144c68d6197c55bc5a4552fb42002190b796729901cbab3ae6e51}"

failures=0
fail() { echo "FAIL: $1" >&2; failures=$((failures + 1)); }
pass() { echo "ok: $1"; }

[ -r "$CHART/Chart.yaml" ] || { echo "FAIL: chart not found at $CHART" >&2; exit 1; }
[ -r "$CHART/values.yaml" ] || { echo "FAIL: values.yaml not found" >&2; exit 1; }

# --- Values-level defaults (no rendering required) ------------------------------------------------------------
values="$CHART/values.yaml"
grep -q 'runAsNonRoot: true' "$values" && pass "pods run as non-root by default" || fail "runAsNonRoot is not the default"
grep -q 'readOnlyRootFilesystem: true' "$values" && pass "containers use a read-only root filesystem" || fail "readOnlyRootFilesystem is not the default"
grep -q 'allowPrivilegeEscalation: false' "$values" && pass "privilege escalation is disabled" || fail "allowPrivilegeEscalation is not disabled"
grep -q 'drop: \["ALL"\]' "$values" && pass "all capabilities are dropped" || fail "capabilities are not dropped"
grep -q 'type: RuntimeDefault' "$values" && pass "the default seccomp profile is applied" || fail "seccompProfile RuntimeDefault is missing"
grep -q 'automountServiceAccountToken: false' "$values" && pass "no service-account token is mounted" || fail "a service-account token is mounted by default"
grep -qE '^  enabled: true' "$values" && pass "network policy defaults on" || fail "networkPolicy is not enabled by default"

# Telemetry and the runtime AI surfaces must match the application defaults: off.
grep -A1 '^telemetry:' "$values" | grep -q 'enabled: false' && pass "telemetry is disabled by default" || fail "telemetry is enabled by default"
grep -q 'providerProxyEnabled: false' "$values" && pass "runtime provider proxy is disabled by default" || fail "provider proxy defaults on"
grep -q 'toolBrokerEnabled: false' "$values" && pass "tool broker is disabled by default" || fail "tool broker defaults on"
grep -A2 '^ingress:' "$values" | grep -q 'enabled: false' && pass "ingress is off until a host is chosen" || fail "ingress defaults on"

# No committed secret material, and no mutable image tag as a default.
for pattern in 'password' 'clientSecret' 'BEGIN .* PRIVATE KEY' 'AKIA'; do
  if grep -qiE "$pattern" "$values"; then fail "values.yaml appears to contain secret material ($pattern)"; else pass "values.yaml has no $pattern"; fi
done
grep -qE '^  tag:' "$values" && fail "values.yaml offers a mutable image tag" || pass "values.yaml exposes digests, not tags"

# --- Rendered-manifest assertions ----------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  echo "note: docker unavailable; skipped rendered-manifest assertions"
  [ "$failures" -gt 0 ] && { echo "$failures chart hardening check(s) failed" >&2; exit 1; }
  echo "All available chart hardening checks passed"
  exit 0
fi

render() {
  docker run --rm --network none -v "$CHART:/chart:ro" "$HELM_IMAGE" template rel /chart "$@" 2>&1
}

required="--set image.managementServerDigest=sha256:aaa --set image.portalDigest=sha256:bbb
  --set config.database.host=db --set config.keycloak.issuerUri=https://k/realms/r
  --set config.keycloak.jwkSetUri=https://k/certs --set existingSecrets.database=db-secret
  --set existingSecrets.objectStorage=s3-secret --set existingSecrets.keycloak=kc-secret"

# shellcheck disable=SC2086
manifest="$(render $required)" || { fail "chart does not render with required values"; manifest=""; }

if [ -n "$manifest" ]; then
  printf '%s' "$manifest" | grep -q 'type: ClusterIP' && pass "services are ClusterIP" || fail "a Service is not ClusterIP"
  printf '%s' "$manifest" | grep -q 'kind: NetworkPolicy' && pass "network policies are rendered" || fail "no NetworkPolicy rendered"
  printf '%s' "$manifest" | grep -q 'kind: Ingress' && fail "an Ingress is rendered by default" || pass "no Ingress is rendered by default"
  printf '%s' "$manifest" | grep -q 'kind: Secret' && fail "the chart renders a Secret" || pass "the chart renders no Secret of its own"
  # Every container must carry probes; a pod without readiness joins the Service before it can serve.
  containers="$(printf '%s' "$manifest" | grep -c 'readinessProbe:' || true)"
  [ "${containers:-0}" -ge 2 ] && pass "both workloads declare a readiness probe" || fail "a workload has no readiness probe"
  limits="$(printf '%s' "$manifest" | grep -c 'limits:' || true)"
  [ "${limits:-0}" -ge 2 ] && pass "both workloads declare resource limits" || fail "a workload has no resource limits"

  # Each workload must reference its own digest. A copy-paste that points the portal at the management-server
  # digest renders and lints cleanly, and only fails when something actually pulls the image.
  ms_line="$(printf '%s' "$manifest" | grep -o 'management-server@sha256:[0-9a-f]*' | head -1)"
  portal_line="$(printf '%s' "$manifest" | grep -o 'portal@sha256:[0-9a-f]*' | head -1)"
  ms_digest="${ms_line##*@}"; portal_digest="${portal_line##*@}"
  if [ -n "$ms_digest" ] && [ -n "$portal_digest" ] && [ "$ms_digest" != "$portal_digest" ]; then
    pass "each workload references its own image digest"
  else
    fail "the workloads share a digest ($ms_digest vs $portal_digest); one is running the wrong image"
  fi
fi

# A mutable tag must be refused outright rather than deployed.
# shellcheck disable=SC2086
if render --set image.managementServerDigest=latest --set image.portalDigest=sha256:b \
    --set config.database.host=d --set config.keycloak.issuerUri=i --set config.keycloak.jwkSetUri=j \
    --set existingSecrets.database=d --set existingSecrets.objectStorage=o --set existingSecrets.keycloak=k \
    | grep -q 'kind: Deployment'; then
  fail "the chart accepted a mutable image tag"
else
  pass "the chart refuses a mutable image tag"
fi

# Enabling telemetry without an exporter endpoint must fail the render, matching the container entrypoint.
# shellcheck disable=SC2086
if render $required --set telemetry.enabled=true | grep -q 'kind: Deployment'; then
  fail "the chart enabled telemetry with no exporter endpoint"
else
  pass "the chart refuses telemetry without an exporter endpoint"
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures chart hardening check(s) failed" >&2
  exit 1
fi
echo "All chart hardening checks passed"
