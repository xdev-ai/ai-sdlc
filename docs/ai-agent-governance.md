# AI-Agent Governance

## Purpose and Non-Negotiable Controls

AI-agent governance records the provenance of AI-assisted changes without allowing an agent to make a delivery decision. The platform preserves these invariants:

1. The deterministic validator never invokes an AI model.
2. The CLI must use an explicit model pin and must not use `--bare`.
3. A human approval is required at every delivery decision point.

This module is an evidence ledger and governance gate. It is not an agent runtime, a prompt execution service, or a mechanism to grant an AI system production access.

## Recorded Evidence

| Record | Required provenance | What is deliberately excluded |
|---|---|---|
| Prompt template | Stable key, semantic version, SHA-256 fingerprint, classification, source reference | Prompt content is not copied into the governance ledger. |
| Agent session | Agent identity, provider, model and model version, session fingerprint, optional context/tool-invocation digests | Raw context and tool-call payloads are not persisted. |
| Generated change | Change reference, generated-change SHA-256, policy decision/reference, optional validation/evidence IDs | No synthetic policy pass or agent-generated approval is accepted. |
| Human decision | Approval request link, quorum state, individual human approvers | An agent identity can never satisfy the approval quorum. |

All SHA-256 values are lower-case 64-character hexadecimal digests. A session fingerprint is idempotent within a project, and a generated-change digest is idempotent within an agent session. Retries therefore cannot create duplicate provenance or duplicate approval requests.

## Governance Flow

1. An authorized project owner, developer, or reviewer registers a versioned prompt fingerprint when a reusable prompt template is in scope.
2. An authorized member declares an agent session and records its provider/model version and bounded digests.
3. The system permits an eligible generated-change declaration only while the session is `DECLARED`.
4. A `FAIL` policy decision is rejected before any approval request is created. `PASS` and `WARN` decisions create a governed approval request.
5. The existing approval orchestration service collects a human decision, quorum, delegation, SLA reminders, escalation, and immutable audit trail.
6. An authorized member completes or blocks the session. Blocking requires owner or reviewer authority.

> A declared agent session or a recorded provenance item is not approval to merge, release, or deploy. The linked human approval status remains the only decision evidence.

## API Surface

All endpoints are project scoped and require a JWT subject with project membership.

| Endpoint | Method | Use |
|---|---|---|
| `/api/v1/projects/{projectId}/agent-governance/prompt-templates` | `POST`, `GET` | Register or list versioned prompt fingerprints. |
| `/api/v1/projects/{projectId}/agent-governance/sessions` | `POST`, `GET` | Declare or list idempotent agent sessions. |
| `/api/v1/projects/{projectId}/agent-governance/sessions/{sessionId}/complete` | `POST` | Complete a declared session. |
| `/api/v1/projects/{projectId}/agent-governance/sessions/{sessionId}/block` | `POST` | Block a session; requires owner or reviewer role. |
| `/api/v1/projects/{projectId}/agent-governance/sessions/{sessionId}/evidence` | `POST` | Declare a policy-eligible generated change and create linked human approval. |
| `/api/v1/projects/{projectId}/agent-governance/evidence` | `GET` | List generated-change provenance and linked approval state. |

The SSR portal exposes the same controls at **AI-agent governance**. Browser forms are authenticated server side; access tokens are not stored in the browser.

## Operational Guidance

Use a provider-neutral `agentIdentity` that identifies the accountable automation configuration, not a human actor. Always capture an immutable model version where the provider supports it. Keep source prompt content in the approved source/evidence repository and store only its fingerprint in this ledger. For sensitive context, use only a digest and classify the prompt template conservatively.

When a policy fails, remediate the change or correct the policy evidence before declaring it. Do not re-label a failed decision as a warning to bypass human review. If a model or toolchain is retired or has a security incident, block active sessions and use the audit record plus evidence ledger to locate affected generated changes.
