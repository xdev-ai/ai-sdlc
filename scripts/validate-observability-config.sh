#!/usr/bin/env sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
collector_config="${OTELCOL_CONFIG:-infra/observability/otelcol-gateway.yaml}"
rules_file="${PROMETHEUS_RULES_FILE:-infra/observability/p3-slo-burn-rate-rules.yaml}"

# Image digests are intentionally pinned. Update them only through dependency review.
otelcol_image="${OTELCOL_IMAGE:-otel/opentelemetry-collector-contrib@sha256:45392d534c1edcc809c2d112394029246bc679d2ae5ea7081414a1fc74f2c621}"
prometheus_image="${PROMETHEUS_IMAGE:-prom/prometheus@sha256:2b6f734e372c1b4717008f7d0a0152316aedd4d13ae17ef1e3268dbfaf68041b}"

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

case "$otelcol_image" in *@sha256:*) ;; *) fail "OTELCOL_IMAGE must be digest-pinned." ;; esac
case "$prometheus_image" in *@sha256:*) ;; *) fail "PROMETHEUS_IMAGE must be digest-pinned." ;; esac

command -v docker >/dev/null 2>&1 || fail "docker is required to validate observability configurations."
command -v openssl >/dev/null 2>&1 || fail "openssl is required to generate ephemeral validation TLS material."

test -f "$repo_root/$collector_config" || fail "Collector configuration not found: $collector_config"
test -f "$repo_root/$rules_file" || fail "Prometheus rules not found: $rules_file"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT HUP INT TERM

openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
  -subj '/CN=aisdlc-otel-validation' \
  -keyout "$tmpdir/tls.key" \
  -out "$tmpdir/tls.crt" >/dev/null 2>&1

printf '%s\n' 'Validating OpenTelemetry Collector configuration with a digest-pinned image...'
docker run --rm --network none --read-only \
  --mount "type=bind,src=$repo_root/$collector_config,dst=/etc/otelcol/config.yaml,readonly" \
  --mount "type=bind,src=$tmpdir,dst=/tls,readonly" \
  -e OTEL_HEALTH_CHECK_ENDPOINT=127.0.0.1:13133 \
  -e OTEL_OTLP_GRPC_ENDPOINT=127.0.0.1:4317 \
  -e OTEL_OTLP_HTTP_ENDPOINT=127.0.0.1:4318 \
  -e OTEL_TLS_CERT_FILE=/tls/tls.crt \
  -e OTEL_TLS_KEY_FILE=/tls/tls.key \
  -e OTEL_TLS_CLIENT_CA_FILE=/tls/tls.crt \
  -e OTEL_MEMORY_LIMIT_MIB=256 \
  -e OTEL_MEMORY_SPIKE_LIMIT_MIB=64 \
  -e DEPLOYMENT_ENVIRONMENT=ci-validation \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=https://127.0.0.1:4318 \
  -e OTEL_EXPORTER_AUTH_TOKEN=ci-validation-token \
  -e OTEL_EXPORTER_CA_FILE=/tls/tls.crt \
  -e OTEL_EXPORTER_SERVER_NAME=aisdlc-otel-validation \
  "$otelcol_image" validate --config=/etc/otelcol/config.yaml

printf '%s\n' 'Validating Prometheus recording and alert rules with a digest-pinned image...'
docker run --rm --network none --read-only \
  --entrypoint /bin/promtool \
  --mount "type=bind,src=$repo_root/$rules_file,dst=/rules/p3-slo-burn-rate-rules.yaml,readonly" \
  "$prometheus_image" check rules /rules/p3-slo-burn-rate-rules.yaml

printf '%s\n' 'Observability configuration validation passed.'
