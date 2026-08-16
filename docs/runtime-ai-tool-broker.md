# P3.3 Tool Broker: Capability Grants

**Status:** Implemented and disabled by default. The internal surface exists only when a deployment enables it.
**Scope:** Tenant-scoped single-use tool capability grants, canonical argument fingerprinting, explicit approval linkage for high-impact tools, and digest-only persistence.

This implements the tool-broker half of the P3.3 decision model in [`p3-reliability-and-runtime-ai-governance-design.md`](p3-reliability-and-runtime-ai-governance-design.md) §5.4–§5.6. The broker authorizes and accounts for a tool action; it does **not** execute one. Outbound tool dispatch, tool credentials, and release-impacting actions remain outside the delivered scope, and the platform invariant that a human approval is mandatory at every delivery decision point is unchanged.

## Why the Grant Is Bound to Arguments

A tool name is not a sufficient unit of authorization. A model can propose a benign call, obtain permission, and then execute a different one — the propose/execute gap that OWASP describes for prompt injection.[1] The broker therefore authorizes an exact argument set: it canonicalizes the arguments, fingerprints them with SHA-256, and binds the grant to that fingerprint. Redemption recomputes the fingerprint from the arguments actually presented and refuses any difference.

Canonicalization orders object members by key and preserves array order, because member order is not part of a JSON value but element order is. The same arguments therefore always produce the same fingerprint, and a reordered payload is not treated as a new authorization.

## Grant Lifecycle

| Stage | Control | Failure outcome |
|---|---|---|
| Issue | Tool must be registered and active for the project; `RuntimeAiBrokerService.authorizeTool` runs the workload check, the approval requirement, and the CEL decision | `TOOL_NOT_ALLOWLISTED`, `HUMAN_APPROVAL_REQUIRED`, or the policy reason code; no grant row is written |
| Issue | A `HIGH_IMPACT` capability requires a linked approved request, enforced independently by the broker, by this service, and by a table check constraint | `HUMAN_APPROVAL_REQUIRED` |
| Issue | Lifetime bounded to 1–300 seconds, default 60 | `IllegalArgumentException` before any authorization work |
| Issue | A 32-byte secret is generated; only its SHA-256 is stored and the secret is returned exactly once | Reading the database yields no usable grant |
| Redeem | One conditional `UPDATE` matches nonce digest, project, workload subject, argument fingerprint, `ISSUED` status, and an unexpired deadline | Two concurrent replays cannot both win; the loser is diagnosed, not redeemed |
| Redeem | A miss is diagnosed after the fact | `GRANT_UNKNOWN`, `GRANT_ALREADY_REDEEMED`, `GRANT_EXPIRED`, `GRANT_SUBJECT_MISMATCH`, `GRANT_ARGUMENT_MISMATCH` |
| Redeem | Receipt digest is `SHA-256(nonce digest \| argument fingerprint)`, so a receipt proves the redeemer held the secret | Recorded on the grant and in the audit event |
| Revoke | Project owner only, and only while the grant is still `ISSUED` | A redeemed grant is history and is never rewritten |

An expired grant found during diagnosis is transitioned to `EXPIRED`, so the record reflects why it can no longer be used.

## What Is Stored

`runtime_ai_tool_grants` (Flyway `V18`) carries `tenant_id` and `project_id`, and holds the capability reference, workload subject, agent session, runtime decision link, approval request link, capability scope, tool manifest digest, argument fingerprint, grant nonce digest, status, reason code, and lifecycle timestamps.

No raw prompt, model output, or tool argument is written — to the table or to the audit event. The grant secret is never written at all. The policy context is derived inside the broker from bounded facts (tool name, impact level, argument fingerprint, approval linkage) rather than accepted from the caller, so a workload cannot feed the policy engine its own evidence.

## API

The workload surface is internal and exists only when `AISDLC_RUNTIME_AI_TOOL_BROKER_ENABLED=true`. It sits under `/internal/runtime-ai/**`, which requires `ROLE_agent_runtime` and is outside the browser CORS policy — see [`runtime-ai-workload-identity.md`](runtime-ai-workload-identity.md).

| Endpoint | Caller | Purpose |
|---|---|---|
| `POST /internal/runtime-ai/projects/{projectId}/tool-grants` | Agent workload | Authorize an argument set; 201 with the one-time secret, or 403 with a reason code |
| `POST /internal/runtime-ai/projects/{projectId}/tool-grants/redemptions` | Agent workload | Redeem once for the same arguments; 200 with a receipt digest, or 403 |
| `POST /api/v1/projects/{projectId}/runtime-ai-broker/tool-grants/{grantId}/revocations` | Project owner | Revoke an unredeemed grant; 204, or 409 when it is no longer `ISSUED` |

The workload subject always comes from the validated token, never from the request body, so a caller cannot obtain or redeem another workload's grant.

## Verification

```sh
mvn -pl management-server test
```

`RuntimeAiToolBrokerServiceTest`, `RuntimeAiToolBrokerControllerTest`, and the `V18` guard in `AuditMigrationTest` cover fingerprint canonicalization, unregistered tools, policy and approval denial, the high-impact approval rule, the absence of raw argument values in both the insert parameters and the audit payload, single-use redemption, each replay and mismatch reason code, revocation, and the schema constraints.

## References

[1] [OWASP GenAI Security Project, LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

[2] [NIST AI 600-1, Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)
