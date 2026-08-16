# P3.1 Sprint 1: Telemetry Configuration Model and Trace Context

**Status:** Implemented in the management server. Telemetry is disabled by default and no exporter is created.
**Scope:** Configuration contract, resource-attribute allowlist, W3C trace-context propagation, and privacy/cardinality contract tests. Java agent packaging, the Collector deployment, domain instrumentation, SLI metrics, and burn-rate alerting remain open P3.1 work.

This sprint delivers steps 1 and part of step 2 of the implementation sequence in [`p3-reliability-and-runtime-ai-governance-design.md`](p3-reliability-and-runtime-ai-governance-design.md). It intentionally adds no OpenTelemetry SDK dependency: the configuration model, the data-classification contract, and the propagation behaviour are established and tested first, so the later agent and Collector work starts from a reviewed contract rather than from defaults.

## Configuration Model

The prefix is `aisdlc.telemetry` and the model is bound by `TelemetryProperties`. Defaults keep an existing deployment unchanged: `enabled` is `false` and `exporter-endpoint` is empty, so nothing is exported and no exporter is constructed.

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `enabled` | `AISDLC_TELEMETRY_ENABLED` | `false` | Master switch; an export is possible only when this is true **and** an endpoint is set. |
| `contract-version` | — | `telemetry.v1` | Version of the data-classification contract. A mismatch fails startup rather than exporting under an unreviewed policy. |
| `service-name` | `AISDLC_TELEMETRY_SERVICE_NAME` | `ai-sdlc-management-server` | `service.name` resource attribute. |
| `service-namespace` | `AISDLC_TELEMETRY_SERVICE_NAMESPACE` | `ai-sdlc` | `service.namespace` resource attribute. |
| `service-version` | `AISDLC_TELEMETRY_SERVICE_VERSION` | empty | `service.version`; omitted from the resource when empty. |
| `service-instance-id` | `AISDLC_TELEMETRY_SERVICE_INSTANCE_ID` | empty | `service.instance.id`; omitted from the resource when empty. |
| `environment` | `DEPLOYMENT_ENVIRONMENT` | `development` | `deployment.environment.name`; restricted to `development`, `staging`, or `production`. |
| `exporter-endpoint` | `AISDLC_TELEMETRY_EXPORTER_ENDPOINT` | empty | Private Collector endpoint. |
| `exporter-timeout` | `AISDLC_TELEMETRY_EXPORTER_TIMEOUT` | `PT10S` | Bounded to 1–30 seconds. |
| `trace-sample-ratio` | `AISDLC_TELEMETRY_TRACE_SAMPLE_RATIO` | `0.1` | Root-span sampling ratio, bounded to 0.0–1.0. |
| `accept-remote-trace-context` | `AISDLC_TELEMETRY_ACCEPT_REMOTE_TRACE_CONTEXT` | `true` | When false, every request starts a new root instead of continuing an inbound trace. |

Validation runs at startup and fails closed. The following configurations are rejected:

- an exporter endpoint that is not HTTPS, except plain HTTP to a loopback host while `environment` is `development`;
- an endpoint that embeds user information, a query string, or a fragment;
- `enabled` without an endpoint, or an export timeout outside the bounded range;
- a deployment environment outside the supported set, which prevents a tenant or project value from becoming a resource attribute;
- a sample ratio outside 0.0–1.0, a contract-version mismatch, or an operator-supplied resource attribute outside the allowlist.

## Resource and Attribute Contract

`TelemetryAttributeContract` is the single source of the allowlists. Resource attributes and metric labels are strict allowlists; span attributes additionally pass a prohibited-token scan as defense in depth. The Collector transform stage in [`infra/observability/otelcol-gateway.yaml`](../infra/observability/otelcol-gateway.yaml) repeats these removals, but the application is expected not to produce a prohibited value at all.

| Signal | Permitted keys |
|---|---|
| Resource | `service.name`, `service.namespace`, `service.version`, `service.instance.id`, `deployment.environment.name`, `aisdlc.telemetry.contract` |
| Span | `http.request.method`, `http.route`, `http.response.status_code`, `server.address`, `aisdlc.operation`, `aisdlc.outcome`, `aisdlc.policy.bundle_version`, `aisdlc.correlation_id` |
| `aisdlc_sli_events_total` | `service`, `environment`, `journey`, `outcome` (exactly `good` or `bad`) |
| `aisdlc_slo_target_ratio` | `service`, `environment`, `journey`, `window` |

Prohibited tokens cover credentials and authorization material, prompts and completions, tool arguments, request and response bodies, raw database statements, full URLs and query strings, and tenant/project/user/session/subject/evidence identifiers. Governance span outcomes are limited to `success`, `rejected`, `failed`, and `timeout`.

Tenant, project, user, session, request, and trace identifiers are excluded from metric labels so metric identity stays bounded. When an operator needs a trace-to-audit pivot, the linkage is read through the control plane under existing tenant/project authorization; it is not solved by exporting the identifier.

## W3C Trace Context Propagation

`TraceContextFilter` binds a `W3CTraceContext` to every management-server request, immediately after the existing correlation filter and before rate limiting.

- An acceptable inbound `traceparent` is **continued**, not replaced: the trace identifier and the sampling decision are inherited, the inbound span identifier becomes the parent, and only a new span identifier is generated.
- A new root is created only when no acceptable context is present. Version `ff`, an all-zero trace or parent identifier, a malformed field, and a version-`00` header with extra fields are all unacceptable. A higher version is accepted by parsing its first four fields.
- An unparsable or oversized (more than 32 members) `tracestate` is discarded while the `traceparent` still continues the trace.
- Root sampling is deterministic in the trailing 64 bits of the trace identifier, so independent services derive the same decision for the same trace without coordination.
- `traceId` and `spanId` are placed in the logging context and included in the JSON log encoder; both are removed, and the thread-local context cleared, even when the downstream chain throws.
- Trace identifiers are **not** returned to the caller. `X-Correlation-Id` remains the client-facing handle.

`W3CTraceContext.outboundHeaders()` renders the version-`00` `traceparent` and, when present, the forwarded `tracestate` for outbound calls. Wiring it into the outbound HTTP clients, and adding the same propagation to the SSR portal, belongs to P3.1 Sprint 2 instrumentation; today a portal-to-API request starts a new root at the API boundary.

The agent deployment profile and the domain instrumentation built on this contract are described in [`telemetry-agent-and-instrumentation.md`](telemetry-agent-and-instrumentation.md).

## Verification

```sh
mvn -pl management-server test
```

The contract tests are `TelemetryPropertiesTest`, `TelemetryAttributeContractTest`, `W3CTraceContextTest`, and `TraceContextFilterTest` under `management-server/src/test/java/ai/xdev/aisdlc/telemetry/`. They assert the disabled-by-default posture, every rejection rule above, the resource and metric-label allowlists, the prohibited-token scan, trace continuation and root creation, deterministic sampling, logging-context lifecycle, and that no trace identifier reaches the response.

## References

[1] [W3C, Trace Context](https://www.w3.org/TR/trace-context/)

[2] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[3] [OpenTelemetry, Transforming Telemetry](https://opentelemetry.io/docs/collector/transforming-telemetry/)
