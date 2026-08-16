# P3.1 Observability Reference Artefacts

This directory contains the OpenTelemetry Collector gateway configuration, Prometheus SLO definitions, recording and alert rules, and alert routing. The Collector ships as the separate `docker-compose.observability.yml` overlay, so the default topology never parses it and the gateway cannot start implicitly. A Compose profile is not sufficient: Compose interpolates every service's `${VAR:?}` variables at parse time regardless of profile, which would break `docker compose up` for the whole topology whenever the exporter variables are unset. P3.1 implementation must pin a Collector Contrib distribution by digest, select an approved telemetry backend, supply the deployment environment variables, test the configuration in the selected distribution, and complete the observe-only baseline before enabling paging.

## Files

| File | Purpose |
|---|---|
| `otelcol-gateway.yaml` | Private OTLP gateway with mTLS, allowlisted platform services, memory limiting, redaction, batching, bounded exporter queue, and retry controls. |
| `p3-slo-burn-rate-rules.yaml` | Prometheus recording and initial multi-window, multi-burn-rate alert rules for 30-day SLOs. |
| `../../scripts/validate-observability-config.sh` | CI/local validator that runs the Collector `validate` command and `promtool check rules` with reviewed digest-pinned images. |
| `../../scripts/test-validate-observability-config.sh` | Deterministic script contract test; it verifies pinning, isolation flags, command shape, and mutable-image rejection without contacting a registry. |
| `p3-slo-definitions.yaml` | Versioned SLO target ratios per journey, split into error-budget and integrity policies. Burn-rate rules divide by these. |
| `p3-slo-rule-tests.yaml` | promtool unit tests: burn alerts fire when they should, stay silent on integrity objectives, and keep their runbook links. |
| `alertmanager-routes.yaml` | Severity and integrity routing, inhibition, and secret-free receivers. |
| `entrypoint-with-optional-agent.sh` | Attaches the OpenTelemetry Java agent only when telemetry is explicitly enabled. |
| `../../scripts/test-observability-contracts.sh` | Offline contract tests for Collector resilience, cardinality, privacy, runbook coverage, and Compose opt-in. |
| `../../scripts/synthetic-health-journey.sh` | Authenticated synthetic journey emitting the availability SLI as a Prometheus textfile. |
| `../../scripts/test-chaos-profile-isolation.sh` | Static guardrails proving the chaos seam is reachable only through the isolated profile. |
| `../../docs/telemetry-configuration-and-trace-context.md` | Application-side contract: `aisdlc.telemetry` configuration model, resource/metric allowlists, and W3C trace-context propagation implemented in the management server. |

## Collector Operational Contract

The Collector configuration uses the standard `receivers`, `processors`, `exporters`, and `service.pipelines` model. A configured component does not become active until it is present in the pipeline; deployment validation must therefore check both syntax and the effective pipeline.[1]

The reference deploys Collector Contrib because it uses the `filter` and `transform` processors. The transform stage removes known sensitive attributes, replaces all received log bodies with a stable redaction token, and removes high-cardinality metric attributes. It is a defense-in-depth boundary: the application must also avoid generating prohibited attributes. OpenTelemetry recommends transformations for data quality, governance, cost, and security, and cautions that advanced transformations can affect Collector performance.[2]

The Collector health endpoint must bind on a private loopback or management network. OTLP receivers must bind only inside an authenticated service network and require a client certificate. Backend credentials are injected at deployment time; no secret is committed in this repository.

| Required environment variable | Meaning | Example policy |
|---|---|---|
| `OTEL_OTLP_GRPC_ENDPOINT`, `OTEL_OTLP_HTTP_ENDPOINT` | Private receiver endpoints | Private cluster DNS/IP and non-public port only |
| `OTEL_TLS_CERT_FILE`, `OTEL_TLS_KEY_FILE`, `OTEL_TLS_CLIENT_CA_FILE` | Receiver mTLS material | Read-only mounts from approved secret manager |
| `OTEL_MEMORY_LIMIT_MIB`, `OTEL_MEMORY_SPIKE_LIMIT_MIB` | Memory limiter bounds | Derived from pod/container limit and load test |
| `DEPLOYMENT_ENVIRONMENT` | Stable resource environment value | `production`, `staging`, or `development`; no tenant/project value |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_AUTH_TOKEN` | Approved backend endpoint and credential | Secret injection only; rotate without repository change |
| `OTEL_EXPORTER_CA_FILE`, `OTEL_EXPORTER_SERVER_NAME` | Exporter TLS validation | Private CA bundle and expected server identity |

Run `otelcol-contrib validate --config=infra/observability/otelcol-gateway.yaml` using the **exact pinned distribution** selected for the deployment. Then use a test exporter to prove that `Authorization`, cookies, raw database statements, raw log bodies, user/tenant/project identifiers, prompts, model outputs, and tool arguments are absent. Do not interpret successful YAML parsing as a security test.

## CI and Local Validation

Run the following command from the repository root to execute the same Collector and Prometheus rule checks used by the CI job:

```sh
sh scripts/validate-observability-config.sh
```

The validator is intentionally fail-closed. It requires Docker, `openssl`, both configuration files, and `OTELCOL_IMAGE`/`PROMETHEUS_IMAGE` values containing an immutable `@sha256:` digest. It mounts only the target configuration and ephemeral one-day TLS material, disables container networking, uses a read-only container filesystem, and supplies non-secret validation-only environment values. The Collector command follows the documented `otelcol validate --config=...` form, while Prometheus validates alert and recording rules with `promtool check rules`.[1] [4]

CI also runs the script contract test before the image-backed validation. The contract test does not replace the real tool checks; it ensures mutable image references, missing isolation flags, and command drift are detected deterministically even when an image registry is unavailable.

## SLI Metric and Burn-Rate Contract

The rules require two future P3.1 application metrics. They do not invent observed data.

| Metric | Required labels | Contract |
|---|---|---|
| `aisdlc_sli_events_total` | `service`, `environment`, `journey`, `outcome` | Counter for a defined service journey; `outcome` is exactly `good` or `bad`. No tenant, project, user, session, trace, prompt, or evidence labels are allowed. |
| `aisdlc_slo_target_ratio` | `service`, `environment`, `journey`, `window` | One gauge per SLO definition. Its 30-day value is the target ratio, for example `0.999` for a 99.90% SLO. |

The fast page uses the common 14.4 burn-rate pair over five minutes and one hour; the sustained ticket uses the 6 burn-rate pair over 30 minutes and six hours. Both are proposals to be calibrated after the 28-day observe-only baseline. The Google SRE workbook recommends combining burn rates and windows to balance precision, recall, detection time, and reset time rather than alerting only on a short-window error rate.[3]

The integrity alert is separate from availability budgeting. A single audit-chain or evidence-digest integrity failure is a governance incident with zero tolerated occurrences; it must page the owning team and preserve only authorized identifiers in the incident system.

## References

[1] [OpenTelemetry Collector Configuration](https://opentelemetry.io/docs/collector/configuration/)

[2] [OpenTelemetry: Transforming Telemetry](https://opentelemetry.io/docs/collector/transforming-telemetry/)

[3] [Google SRE Workbook: Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/)

[4] [Prometheus: promtool command-line reference](https://prometheus.io/docs/prometheus/latest/command-line/promtool/)
