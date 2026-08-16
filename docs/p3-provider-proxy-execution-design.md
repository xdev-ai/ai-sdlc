# P3.3 Provider Proxy Execution Design

## Purpose and Scope

This document defines the first executable provider-adapter slice of Runtime AI Governance. The adapter is an internal runtime boundary that dispatches an already-authorized request to one registered provider profile. It does not choose a provider, mutate a policy, inspect or persist raw prompts or responses, execute tools, or replace the existing human-approval workflow.

The implementation is deliberately provider-neutral. A provider-specific JSON request shape is treated as opaque in transit and must be validated by the project policy before proxy dispatch. The adapter returns the provider response only to the authenticated runtime caller. It stores and audits metadata, digests, counts, policy-decision references, and failure reason codes—not prompt, context, response, authorization material, or TLS private-key data.

> A provider dispatch is permitted only after workload identity, provider/model allowlist, budget enforcement, and CEL pre-flight authorization have succeeded. Any missing dependency, malformed request, authentication ambiguity, unavailable credential, profile change, TLS failure, timeout, or evidence-write failure blocks dispatch.

## Execution Contract

The proxy receives a project, workload subject, agent session, provider, pinned model, request fingerprint, policy context, an idempotency key, and an in-memory JSON payload. It uses the existing `RuntimeAiBrokerService.preflight` operation before resolving the active provider profile. A non-`ALLOW` pre-flight decision returns no network response and never opens an outbound connection.

| Control | Enforcement rule | Persistent evidence |
| --- | --- | --- |
| Workload identity | The subject must be an active identity registered for the project. | Existing runtime governance decision/audit record. |
| Model and endpoint allowlist | The `(project, provider, model)` profile must be active; the exact HTTPS endpoint is stored in the profile. Arbitrary request URLs are not accepted. | Profile ID and endpoint digest. |
| Model pin | The request model must equal the profile model. Mutable aliases are not accepted by this adapter. | Provider/model fields and request fingerprint. |
| Credential isolation | Database rows store opaque secret references only. A separate runtime resolver obtains a short-lived authorization value and optional `SSLContext`; callers, database queries, logs, exceptions, HTTP response metadata, and API responses never include secret values. | Secret-reference digest only. |
| mTLS | If the profile requires mTLS, the resolver must return a non-null client `SSLContext`. A missing or unusable TLS identity blocks the request. | mTLS-required flag and failure reason only. |
| Retry | At most the configured `max_attempts` (1–3), with bounded exponential backoff. A retry is allowed only for 408, 429, or 5xx responses and only when a validated idempotency key is forwarded upstream. | Attempt count and final response/status digest. |
| Timeout | The configured timeout governs the whole HTTP request. A timeout is a failed dispatch, never an authorization success or implicit retry without idempotency. | `PROVIDER_TIMEOUT` plus elapsed time. |
| Evidence | Request and response digests use SHA-256. The audit event records identifiers, decisions, profile, status, attempts, and digests. | Immutable `audit_events` record; no payload retention. |

## Profile and Secret Boundary

Migration V17 will extend `runtime_ai_provider_profiles` with `endpoint_uri`, `mtls_reference`, and `require_mtls`. The endpoint is validated at configuration time and again at dispatch time: it must be HTTPS, contain no user-info or fragment, and carry no query-string controlled by a caller. The runtime request cannot supply an endpoint, custom headers, a credential reference, a trust store, or arbitrary TLS options.

`ProviderCredentialResolver` is a narrow internal interface. It accepts a stored opaque reference and returns a transient credential material object containing an authorization header value and, when requested, a client `SSLContext`. The production deployment must bind this interface to the approved secret manager. The default implementation fails closed; it does not read plaintext credentials from the database, process arguments, request body, or an unaudited fallback file. Test doubles use in-memory tokens and certificates only.

The initial adapter uses JDK `HttpClient` behind an injectable `ProviderHttpTransport`. This separates governance tests from networking and lets the test suite use a fake transport rather than a production model or an external provider. `HttpClient` follows no redirects, uses an explicit per-request deadline, and creates a client configured with the optional mTLS context for that single dispatch.

## Idempotency and Retry Semantics

The caller supplies a bounded UUID idempotency key. The adapter forwards the same value as `Idempotency-Key` on each attempt. It does not automatically retry any request without that key, a client error other than 408, or a provider response that cannot prove safe retry behaviour. A transport exception or timeout is retried only when the key is present and attempts remain; otherwise it is returned as a deterministic failed result.

The initial slice records evidence for every completed dispatch attempt, but does not cache or replay raw provider responses. End-to-end response replay and durable invocation idempotency require a separate encrypted response-retention decision and are out of scope. A client that cannot tolerate an uncertain upstream result after a timeout must use its own approved workflow rather than assuming the provider did not receive the request.

## Failure Matrix

| Failure | Outbound network call | Result reason code | Notes |
| --- | --- | --- | --- |
| Unknown workload, inactive profile, deny pre-flight, or unavailable budget/policy | No | Existing broker reason | Governance remains fail closed. |
| Endpoint invalid or profile changes after pre-flight | No | `PROVIDER_PROFILE_UNAVAILABLE` | The profile is re-read immediately before dispatch. |
| Credential/mTLS resolver unavailable | No | `PROVIDER_CREDENTIAL_UNAVAILABLE` or `PROVIDER_MTLS_UNAVAILABLE` | No fallback to database or caller-supplied secret. |
| Timeout or transport failure | Yes, only after pre-flight | `PROVIDER_TIMEOUT` or `PROVIDER_TRANSPORT_FAILURE` | Bounded retry only with an idempotency key. |
| Retryable HTTP response exhausted | Yes | `PROVIDER_RETRY_EXHAUSTED` | Response body is not persisted. |
| Non-retryable HTTP response | Yes | `PROVIDER_HTTP_<status>` | The response is returned only to the authorized runtime caller. |
| Audit/evidence write failure | No future release or retry | `PROVIDER_EVIDENCE_FAILURE` | The current response is treated as failed and not released. |

## Rollout and Test Gates

The adapter is feature-gated and ships with no real provider profile, no provider secret, and no default endpoint. Unit tests use a fake transport and fake credential resolver. The test matrix covers pre-flight denial without a transport invocation, exact endpoint use, credential isolation, mTLS-required failure, retryable and non-retryable response handling, timeout handling, attempt cap, idempotency-key forwarding, digest-only evidence, and failure of audit persistence.

An authenticated runtime API is not exposed until the Keycloak service-account audience and `agent_runtime` authority checks are enforced at the resource boundary. The initial operational rollout is restricted to one internal project with a fake or isolated read-only provider. No production deploy, merge, destructive tool, or provider credential is included in the repository or test environment.

## References

[1] [OpenTelemetry, Semantic Conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/)

[2] [NIST AI 600-1, Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)

[3] [SPIFFE, Secure Production Identity Framework for Everyone](https://spiffe.io/)
