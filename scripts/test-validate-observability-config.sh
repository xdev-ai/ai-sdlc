#!/usr/bin/env sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
script="$repo_root/scripts/validate-observability-config.sh"

test -x "$script" || {
  printf '%s\n' "Validation script must be executable: $script" >&2
  exit 1
}

sh -n "$script"
grep -q 'otel/opentelemetry-collector-contrib@sha256:' "$script"
grep -q 'prom/prometheus@sha256:' "$script"
grep -q 'validate --config=/etc/otelcol/config.yaml' "$script"
grep -q 'check rules /rules/p3-slo-definitions.yaml /rules/p3-slo-burn-rate-rules.yaml' "$script"
# `check rules` proves only that the syntax parses. The unit tests are what prove an alert fires when it should and
# stays silent when it should not, so the validator must run them too.
grep -q 'test rules /rules/p3-slo-rule-tests.yaml' "$script"
grep -q -- '--tmpfs /tmp:rw,noexec,nosuid' "$script"
grep -q -- '--network none' "$script"
grep -q -- '--read-only' "$script"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT HUP INT TERM
mkdir -p "$tmpdir/bin"
cat > "$tmpdir/bin/docker" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$DOCKER_CALL_LOG"
EOF
cat > "$tmpdir/bin/openssl" <<'EOF'
#!/usr/bin/env sh
key=''
cert=''
while test "$#" -gt 0; do
  case "$1" in
    -keyout) key="$2"; shift 2 ;;
    -out) cert="$2"; shift 2 ;;
    *) shift ;;
  esac
done
test -n "$key" && : > "$key"
test -n "$cert" && : > "$cert"
EOF
chmod +x "$tmpdir/bin/docker" "$tmpdir/bin/openssl"

DOCKER_CALL_LOG="$tmpdir/docker-calls.log" \
PATH="$tmpdir/bin:$PATH" \
OTELCOL_IMAGE='otel/opentelemetry-collector-contrib@sha256:test' \
PROMETHEUS_IMAGE='prom/prometheus@sha256:test' \
"$script"

grep -q 'validate --config=/etc/otelcol/config.yaml' "$tmpdir/docker-calls.log"
grep -q 'check rules /rules/p3-slo-definitions.yaml /rules/p3-slo-burn-rate-rules.yaml' "$tmpdir/docker-calls.log"
grep -q 'test rules /rules/p3-slo-rule-tests.yaml' "$tmpdir/docker-calls.log"
grep -q -- '--network none' "$tmpdir/docker-calls.log"
grep -q -- '--read-only' "$tmpdir/docker-calls.log"

if PATH="$tmpdir/bin:$PATH" OTELCOL_IMAGE='otel/opentelemetry-collector-contrib:mutable' "$script" >/dev/null 2>&1; then
  printf '%s\n' 'Expected mutable Collector image to be rejected.' >&2
  exit 1
fi

printf '%s\n' 'Observability validation script contract tests passed.'
