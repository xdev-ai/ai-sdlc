#!/bin/sh
# Attaches the OpenTelemetry Java agent only when telemetry is explicitly enabled.
#
# Default behaviour is unchanged: with AISDLC_TELEMETRY_ENABLED unset or false the JVM starts with no agent, no
# exporter, and no additional class loading. Enabling telemetry without a readable, verified agent is a startup
# failure rather than a silent downgrade, so a deployment cannot believe it is observed when it is not.
set -eu

AGENT_PATH="${AISDLC_OTEL_AGENT_PATH:-/opt/opentelemetry/opentelemetry-javaagent.jar}"
APP_JAR="${AISDLC_APP_JAR:-/app/app.jar}"

if [ "${AISDLC_TELEMETRY_ENABLED:-false}" = "true" ]; then
  if [ ! -r "$AGENT_PATH" ]; then
    echo "Telemetry is enabled but the OpenTelemetry agent is not readable at $AGENT_PATH" >&2
    exit 78
  fi
  if [ -z "${AISDLC_TELEMETRY_EXPORTER_ENDPOINT:-}" ]; then
    echo "Telemetry is enabled but AISDLC_TELEMETRY_EXPORTER_ENDPOINT is empty" >&2
    exit 78
  fi
  # The application owns the resource contract; the agent must not invent its own service identity.
  exec java \
    "-javaagent:$AGENT_PATH" \
    "-Dotel.service.name=${AISDLC_TELEMETRY_SERVICE_NAME:-ai-sdlc-management-server}" \
    "-Dotel.resource.attributes=service.namespace=${AISDLC_TELEMETRY_SERVICE_NAMESPACE:-ai-sdlc},deployment.environment.name=${DEPLOYMENT_ENVIRONMENT:-development},aisdlc.telemetry.contract=telemetry.v1" \
    "-Dotel.exporter.otlp.endpoint=${AISDLC_TELEMETRY_EXPORTER_ENDPOINT}" \
    "-Dotel.exporter.otlp.protocol=${AISDLC_TELEMETRY_EXPORTER_PROTOCOL:-grpc}" \
    "-Dotel.traces.sampler=parentbased_traceidratio" \
    "-Dotel.traces.sampler.arg=${AISDLC_TELEMETRY_TRACE_SAMPLE_RATIO:-0.1}" \
    "-Dotel.propagators=tracecontext,baggage" \
    "-Dotel.instrumentation.common.default-enabled=true" \
    "-Dotel.instrumentation.runtime-telemetry.enabled=true" \
    -jar "$APP_JAR" "$@"
fi

exec java -jar "$APP_JAR" "$@"
