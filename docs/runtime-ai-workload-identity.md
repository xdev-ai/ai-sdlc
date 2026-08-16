# Runtime AI Workload Identity and Provider Proxy Rollout

**Status:** Implemented and disabled by default. Enabling the internal surface is an explicit deployment decision.
**Scope:** Keycloak `agent_runtime` identity, resource-server audience and authorized-party enforcement, the internal agent-runtime-only provider-invocation endpoint, and the secret-manager binding for `ProviderCredentialResolver`.

The tool-grant half of the same internal surface is described in [`runtime-ai-tool-broker.md`](runtime-ai-tool-broker.md).

This closes the rollout precondition recorded in [`p3-provider-proxy-execution-design.md`](p3-provider-proxy-execution-design.md): the provider adapter existed but had no authenticated caller and no credential source. It does not widen the adapter's scope — tool brokering, post-flight approval mutation, and payload retention remain out of scope.

## Workload Identity

`agent_runtime` is a realm role in [`infra/keycloak/ai-sdlc-realm.json`](../infra/keycloak/ai-sdlc-realm.json), held by the `aisdlc-agent-runtime` confidential service-account client. Two rules keep a workload from becoming a human principal:

1. A token carrying `agent_runtime` receives exactly one authority, `ROLE_agent_runtime`. It never receives `ROLE_admin`, `ROLE_developer`, `ROLE_reviewer`, or `ROLE_viewer`.
2. A token carrying `agent_runtime` **and** any human realm role is treated as an impersonation attempt. The validator rejects it, and the authority converter independently grants nothing, so neither identity is granted even if one layer is bypassed.

Human callers are correspondingly kept off the runtime surface: `/api/**` now requires one of the four human authorities rather than merely an authenticated principal, and `/internal/runtime-ai/**` requires `ROLE_agent_runtime`.

## Audience and Authorized Party

`RuntimeTokenValidator` runs inside the resource-server `JwtDecoder`, after the standard issuer, signature, and expiry validation.

| Property | Environment variable | Default | Effect |
|---|---|---|---|
| `aisdlc.security.audience.runtime` | `AISDLC_RUNTIME_AUDIENCE` | empty | Audience a runtime token must carry. **While empty, every `agent_runtime` token is rejected**, so the internal surface cannot be reached by accident. |
| `aisdlc.security.audience.control-plane` | `AISDLC_CONTROL_PLANE_AUDIENCE` | empty | When set, every human token must carry this audience. Empty preserves the behaviour of a realm whose clients do not yet emit an audience mapper. |
| `aisdlc.security.audience.runtime-authorized-party` | `AISDLC_RUNTIME_AUTHORIZED_PARTY` | empty | When set, a runtime token's `azp` must match it, which pins runtime access to one Keycloak client. |

A human token that carries the runtime audience is also rejected once the runtime audience is configured, which prevents audience confusion in the other direction. The realm file ships the matching mappers: `aisdlc-management` for the portal and CLI clients, `aisdlc-runtime` for the agent-runtime client.

Recommended production values are `AISDLC_RUNTIME_AUDIENCE=aisdlc-runtime`, `AISDLC_CONTROL_PLANE_AUDIENCE=aisdlc-management` once the realm mappers are deployed, and `AISDLC_RUNTIME_AUTHORIZED_PARTY=aisdlc-agent-runtime`.

## Internal Provider-Invocation Endpoint

```
POST /internal/runtime-ai/projects/{projectId}/provider-invocations
Idempotency-Key: <uuid>
```

The endpoint bean exists only when `AISDLC_RUNTIME_AI_PROVIDER_PROXY_ENABLED=true`. It is deliberately outside `/api/**` and outside the browser CORS policy, so no browser origin can reach it.

The workload subject comes from the validated token subject, never from the request body, so a caller cannot dispatch as another workload; `RuntimeAiBrokerService` still requires that subject to be a registered, active workload for the project. The request body carries the agent session, provider, pinned model, request fingerprint, policy context, and an opaque provider payload. The idempotency key is a header, matching the platform convention, and is forwarded upstream by the adapter.

| Outcome | HTTP status | Body |
|---|---|---|
| `COMPLETE` | 200 | Reason code, digests, attempt count, decision ID, and the provider response returned to the authorized caller |
| `BLOCKED` | 403 | Reason code and digests; no provider response, and no outbound call was made |
| `BLOCKED` with `DUPLICATE_REQUEST` | 409 | A replayed idempotency key is a conflict, not an authorization failure, so a safe retry is not reported as a governance block |
| `FAILED` | 502 | Reason code, attempt count, and digests; the provider response is withheld |

Only digests reach the audit record. The provider response is never persisted.

## Provider Credential Binding

`ProviderCredentialResolver` is bound by `RuntimeAiCredentialConfiguration`:

- With no `AISDLC_RUNTIME_AI_CREDENTIAL_MOUNT_PATH`, the fail-closed resolver stays in place and every dispatch is blocked with `PROVIDER_CREDENTIAL_UNAVAILABLE`.
- With a mount path configured, `MountedSecretProviderCredentialResolver` reads material from that read-only directory. The application fails to start if the path is not an existing directory.

A read-only mount is the provider-neutral delivery mechanism that every approved secret manager already supports — a Vault Agent, a secrets-store CSI driver, or a Kubernetes Secret all project material into a directory. Substituting a direct secret-manager SDK is one additional implementation of the interface and requires no other change.

| Rule | Behaviour |
|---|---|
| Reference format | Only `mount:<name>` with `[a-z0-9][a-z0-9._-]{0,62}`; anything else, including a path separator or `..`, is refused before touching the filesystem. |
| Containment | The resolved path is normalized and must remain inside the mount. |
| File shape | Regular file only; a symbolic link is refused, and size must be non-zero and bounded (8 KiB for a credential, 256 KiB for a keystore). |
| Permissions | On a POSIX filesystem, material readable or writable by group or others is refused. |
| Credential value | Trimmed; a blank value, or one containing a control character, is refused so it cannot be used for header injection. |
| mTLS | `<name>.p12` (PKCS#12) plus `<name>.p12.pass`, both under the same rules. A profile requiring mTLS fails closed when the identity is missing or unusable. |
| Disclosure | No secret value appears in a log line, exception message, API response, or audit record. The password buffer is zeroed after use. |

## Rollout Order

1. Import the updated realm, then create one service-account workload holding only `agent_runtime`.
2. Set `AISDLC_RUNTIME_AUDIENCE` and `AISDLC_RUNTIME_AUTHORIZED_PARTY`, and verify a human token is still accepted and a runtime token is rejected by `/api/**`.
3. Register the workload subject and the provider profile for one internal project through the existing owner-only broker endpoints.
4. Mount the provider credential and confirm the application starts and the fail-closed resolver is no longer in use.
5. Set `AISDLC_RUNTIME_AI_PROVIDER_PROXY_ENABLED=true` for that deployment only, against a fake or isolated read-only provider.
6. Enable `AISDLC_CONTROL_PLANE_AUDIENCE` once every human client emits the audience mapper.

## Verification

```sh
mvn -pl management-server test
```

`RuntimeTokenValidatorTest`, `SecurityConfigTest`, `RuntimeAiCredentialConfigurationTest`, `MountedSecretProviderCredentialResolverTest`, and `RuntimeAiProviderProxyControllerTest` cover the unconfigured-audience rejection, audience confusion in both directions, authorized-party pinning, the mixed-identity token, the mount reference and permission rules, symlink and traversal refusal, mTLS failure closure, secret non-disclosure, and the endpoint's subject binding and response shaping.

## References

[1] [OWASP GenAI Security Project, LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

[2] [NIST AI 600-1, Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)

[3] [Keycloak, Audience Support](https://www.keycloak.org/docs/latest/server_admin/index.html)
