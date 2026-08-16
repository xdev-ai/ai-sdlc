# P3.1: OpenTelemetry Agent Packaging and Governance Instrumentation

**Status:** Implemented and disabled by default. No deployment exports telemetry until an operator enables it.
**Scope:** Sprint 1 agent deployment profile and Sprint 2 domain instrumentation. Collector deployment, SLI recording rules, dashboards, and burn-rate alerting remain open.

Builds on the configuration model and trace-context contract in [`telemetry-configuration-and-trace-context.md`](telemetry-configuration-and-trace-context.md).

## Agent Packaging

Both runtime images carry a pinned OpenTelemetry Java agent:

| Property | Value |
|---|---|
| Version | `2.16.0` |
| SHA-256 | `1b0246d3e60b608b07836a9656e1a97bb7d084b088111ef34ecd47483acebcf5` |
| Location in image | `/opt/opentelemetry/opentelemetry-javaagent.jar`, root-owned, mode `0444` |

The agent is downloaded in a separate build stage and verified with `sha256sum -c`, so a compromised or truncated download fails the image build rather than shipping an unverified agent into a governed runtime. Both values are build arguments, so an upgrade is a reviewable one-line change with a new digest.

## Conditional Attachment

`ENTRYPOINT` is [`infra/observability/entrypoint-with-optional-agent.sh`](../infra/observability/entrypoint-with-optional-agent.sh), not a bare `java -jar`. Its contract:

| Condition | Behaviour |
|---|---|
| `AISDLC_TELEMETRY_ENABLED` unset or `false` | Plain JVM: no `-javaagent`, no `otel.*` system property, no exporter. Byte-for-byte the previous startup behaviour. |
| Enabled, agent readable, endpoint set | Attaches the agent and pins service name, namespace, `deployment.environment.name`, the telemetry contract version, the OTLP endpoint and protocol, `parentbased_traceidratio` sampling, and `tracecontext,baggage` propagation. |
| Enabled, agent missing or unreadable | Exit 78. A deployment must not believe it is observed when it is not. |
| Enabled, `AISDLC_TELEMETRY_EXPORTER_ENDPOINT` empty | Exit 78, for the same reason. |

The application owns the resource identity: the entrypoint passes `otel.service.name` and `otel.resource.attributes` explicitly rather than letting the agent infer them, so the exported resource matches `TelemetryAttributeContract`.

## Instrumented Operations

`GovernanceTelemetry` adds manual spans, a duration histogram, and one service-level indicator event per operation. Automatic instrumentation from the agent covers Spring MVC, JDBC, and outbound HTTP; these are the domain operations it cannot infer.

| Component | Operation | Journey | Reliability question |
|---|---|---|---|
| `PolicyEvaluationService` | `aisdlc.policy.evaluate` | `policy-decision-latency` | Are governance policies evaluated within budget? |
| `ApprovalOrchestrationService` | `aisdlc.approval.transition` | `approval-orchestration` | Are mandatory human decisions completing? |
| `EvidenceRepositoryService` | `aisdlc.evidence.write` | `evidence-durability` | Can governed evidence be persisted? |
| `ScmIntegrationService` | `aisdlc.scm.ingest` | `scm-ingestion-freshness` | Are webhooks accepted and processed once? |
| `NotificationService` | `aisdlc.notification.dispatch` | `notification-timeliness` | Are governance notifications arriving? |
| `AuditService` | `aisdlc.audit.append` | `audit-correctness` | Is governance evidence continuously appendable? |
| `AuditLedgerHealthIndicator` | `aisdlc.health.audit_ledger` | `control-plane-availability` | Is the audit dependency healthy? |

Outcomes come from the bounded vocabulary `success`, `rejected`, `failed`, `timeout`. A thrown failure is classified without exposing its message: a timeout type maps to `timeout`, `SecurityException` and `IllegalArgumentException` map to `rejected`, everything else to `failed`. The cause chain is walked with a cycle guard.

### Metric Names

| Instrument | OTel name | Prometheus name after export |
|---|---|---|
| SLI counter | `aisdlc.sli.events` | `aisdlc_sli_events_total` |
| Operation duration | `aisdlc.operation.duration` | `aisdlc_operation_duration_milliseconds` |

`aisdlc_sli_events_total` is the metric the recording rules in [`p3-slo-burn-rate-rules.yaml`](../infra/observability/p3-slo-burn-rate-rules.yaml) consume, with labels `service`, `environment`, `journey`, `outcome` and `outcome` restricted to `good` or `bad`. The label set is asserted on every emission, so a future change cannot silently widen metric cardinality.

## Fail-Open Behaviour

Instrumentation observes; it never changes a result.

- Without an agent attached, `GlobalOpenTelemetry` resolves to a no-op implementation, so the default build and every existing test path are unaffected.
- The recording path catches its own failures. A telemetry error cannot turn a healthy governance operation into an error, and a rejected outcome value is dropped rather than thrown.
- Operation results and exceptions pass through unchanged — the original exception instance is rethrown, not wrapped.

Governance evidence continues to fail closed through the audit, policy, and approval paths; see [`resilience-fault-injection.md`](resilience-fault-injection.md).

## Verification

```sh
mvn -pl management-server test
sh scripts/test-agent-entrypoint.sh
```

`GovernanceTelemetryTest` covers result pass-through, single invocation, unwrapped rethrow for checked and unchecked failures, the outcome vocabulary including deep and self-referencing cause chains, fail-open on an invalid outcome, and the SLI label contract.

`scripts/test-agent-entrypoint.sh` drives the entrypoint with a recording `java` stub, so it proves which JVM arguments each configuration produces without starting a JVM or contacting a network. It also asserts that both Dockerfiles pin and verify an agent digest. CI runs it in the observability configuration job.

## References

[1] [OpenTelemetry Java, Automatic Instrumentation](https://opentelemetry.io/docs/zero-code/java/agent/)

[2] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[3] [OpenTelemetry, Prometheus Compatibility](https://opentelemetry.io/docs/specs/otel/compatibility/prometheus_and_openmetrics/)
