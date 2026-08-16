#!/bin/sh
# Deterministic contract test for infra/observability/entrypoint-with-optional-agent.sh.
#
# A stub `java` placed earlier on PATH records the arguments the entrypoint would pass, so the test proves which JVM
# configuration each case produces without starting a JVM, downloading an agent, or contacting a network. `exec` is a
# shell builtin and is not replaced; it simply hands off to the stub.
set -eu

ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
ENTRYPOINT="$ROOT/infra/observability/entrypoint-with-optional-agent.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
fail() { echo "FAIL: $1" >&2; failures=$((failures + 1)); }
pass() { echo "ok: $1"; }

# A stub `java` on PATH records its arguments instead of executing anything.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/java" <<'STUB'
#!/bin/sh
printf '%s\n' "$@" > "$RECORDED_ARGS"
exit 0
STUB
chmod +x "$WORK/bin/java"
: > "$WORK/agent.jar"
: > "$WORK/app.jar"

# Prefix assignments in front of a shell function persist in the calling shell, which would leak one case's
# environment into the next. Each case therefore exports what it needs and clears it again here.
reset_env() {
  unset AISDLC_TELEMETRY_ENABLED AISDLC_TELEMETRY_EXPORTER_ENDPOINT DEPLOYMENT_ENVIRONMENT || true
  AISDLC_OTEL_AGENT_PATH="$WORK/agent.jar"; export AISDLC_OTEL_AGENT_PATH
}

run() {
  RECORDED_ARGS="$WORK/args"; export RECORDED_ARGS
  : > "$RECORDED_ARGS"
  PATH="$WORK/bin:$PATH" AISDLC_APP_JAR="$WORK/app.jar" sh "$ENTRYPOINT" >"$WORK/out" 2>"$WORK/err"
}

# 1. Default: no agent, no exporter, unchanged local behaviour.
reset_env
AISDLC_TELEMETRY_ENABLED=false; export AISDLC_TELEMETRY_ENABLED
if run; then
  if grep -q -- '-javaagent' "$WORK/args"; then fail "disabled telemetry must not attach an agent"; else pass "disabled telemetry starts a plain JVM"; fi
  if grep -q -- '-Dotel' "$WORK/args"; then fail "disabled telemetry must not set otel system properties"; else pass "disabled telemetry sets no otel properties"; fi
  if grep -qx -- "$WORK/app.jar" "$WORK/args"; then pass "disabled telemetry still runs the application jar"; else fail "application jar missing"; fi
else
  fail "entrypoint exited non-zero with telemetry disabled"
fi

# 2. Unset variable behaves exactly like false.
reset_env
if run; then
  if grep -q -- '-javaagent' "$WORK/args"; then fail "unset telemetry must not attach an agent"; else pass "unset telemetry behaves as disabled"; fi
else
  fail "entrypoint exited non-zero with telemetry unset"
fi

# 3. Enabled with a readable agent and an endpoint attaches the agent and pins the resource contract.
reset_env
AISDLC_TELEMETRY_ENABLED=true; AISDLC_TELEMETRY_EXPORTER_ENDPOINT=https://collector.internal:4317; DEPLOYMENT_ENVIRONMENT=production
export AISDLC_TELEMETRY_ENABLED AISDLC_TELEMETRY_EXPORTER_ENDPOINT DEPLOYMENT_ENVIRONMENT
if run; then
  grep -q -- "-javaagent:$WORK/agent.jar" "$WORK/args" && pass "enabled telemetry attaches the pinned agent" || fail "agent not attached"
  grep -q -- '-Dotel.exporter.otlp.endpoint=https://collector.internal:4317' "$WORK/args" && pass "exporter endpoint forwarded" || fail "exporter endpoint missing"
  grep -q -- 'deployment.environment.name=production' "$WORK/args" && pass "deployment environment forwarded" || fail "deployment environment missing"
  grep -q -- 'aisdlc.telemetry.contract=telemetry.v1' "$WORK/args" && pass "telemetry contract version pinned" || fail "contract version missing"
  grep -q -- '-Dotel.propagators=tracecontext,baggage' "$WORK/args" && pass "W3C propagation configured" || fail "propagators missing"
else
  fail "entrypoint exited non-zero with telemetry enabled"
fi

# 4. Enabled without a readable agent is a startup failure, never a silent downgrade.
reset_env
AISDLC_TELEMETRY_ENABLED=true; AISDLC_TELEMETRY_EXPORTER_ENDPOINT=https://collector.internal:4317; AISDLC_OTEL_AGENT_PATH="$WORK/absent.jar"
export AISDLC_TELEMETRY_ENABLED AISDLC_TELEMETRY_EXPORTER_ENDPOINT AISDLC_OTEL_AGENT_PATH
RECORDED_ARGS="$WORK/args"; export RECORDED_ARGS; : > "$RECORDED_ARGS"
if PATH="$WORK/bin:$PATH" AISDLC_APP_JAR="$WORK/app.jar" sh "$ENTRYPOINT" >"$WORK/out" 2>"$WORK/err"; then
  fail "a missing agent must fail startup"
else
  [ ! -s "$WORK/args" ] && pass "a missing agent starts no JVM" || fail "JVM started despite a missing agent"
fi

# 5. Enabled without an exporter endpoint is also a startup failure.
reset_env
AISDLC_TELEMETRY_ENABLED=true; export AISDLC_TELEMETRY_ENABLED
RECORDED_ARGS="$WORK/args"; export RECORDED_ARGS; : > "$RECORDED_ARGS"
if PATH="$WORK/bin:$PATH" AISDLC_APP_JAR="$WORK/app.jar" sh "$ENTRYPOINT" >"$WORK/out" 2>"$WORK/err"; then
  fail "an empty exporter endpoint must fail startup"
else
  [ ! -s "$WORK/args" ] && pass "an empty exporter endpoint starts no JVM" || fail "JVM started without an exporter endpoint"
fi

# 6. The pinned agent coordinates in both Dockerfiles must carry an immutable SHA-256.
for dockerfile in "$ROOT/management-server/Dockerfile" "$ROOT/portal/Dockerfile"; do
  grep -q 'OTEL_AGENT_SHA256=[0-9a-f]\{64\}' "$dockerfile" && pass "$(basename "$(dirname "$dockerfile")") pins an agent digest" \
    || fail "$dockerfile does not pin a 64-character agent digest"
  grep -q 'sha256sum -c -' "$dockerfile" && pass "$(basename "$(dirname "$dockerfile")") verifies the agent digest" \
    || fail "$dockerfile does not verify the downloaded agent"
done

if [ "$failures" -gt 0 ]; then
  echo "$failures entrypoint contract check(s) failed" >&2
  exit 1
fi
echo "All entrypoint contract checks passed"
