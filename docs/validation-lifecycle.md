# Validation Finding and Evidence Lifecycle

Validation results originate with the deterministic CLI, but their operational handling is a governed human workflow in the control plane. The lifecycle deliberately separates immutable execution evidence from mutable operational metadata.

## Immutable and Mutable Fields

| Record | Immutable data | Governed lifecycle data |
|---|---|---|
| Validation run | Idempotency key, CLI version, Spec Kit version, model pin, status and ingest timestamp | None; a run is a historical execution fact. |
| Finding | Rule code, message, severity, source path and line | Triage status, note, actor and timestamp. |
| Evidence | Type, URI and SHA-256 digest | Retention deadline. |

The evidence URI is displayed as an external reference; its digest remains the verification anchor. Adjusting a retention deadline does not change the referenced object or digest.

## Finding Triage

Authorized project members transition a finding from `OPEN` to one of `ACKNOWLEDGED`, `RESOLVED`, `FALSE_POSITIVE`, or `ACCEPTED_RISK`. A rationale is required for `FALSE_POSITIVE` and `ACCEPTED_RISK`, so non-remediation remains explicit and auditable. Each successful transition writes `VALIDATION_FINDING_TRIAGED` to the organization audit ledger with actor, target and outcome metadata.

The API uses a project- and run-scoped resource path and validates that the finding belongs to the supplied run. This prevents a caller from updating a finding through a different project context. The SSR portal presents the same workflow from a selected immutable validation run and applies normal CSRF protection.

## Evidence Retention

Evidence retention is set as a future UTC instant. The database checks that it falls after the evidence creation time and indexes the deadline to support a future controlled cleanup worker. No automatic delete process is included in the application: deletion policy must be configured as a separately authorized retention operation, preserving the audit and legal-hold review boundary.

Every retention update writes `VALIDATION_EVIDENCE_RETENTION_SET` to the audit ledger. Operators should preserve audit-event rows even when evidence objects expire, retaining the digest and lifecycle record needed to explain historical validation.

## Operational Use

1. Open **Validations** in the SSR portal, filter runs and select **Inspect**.
2. Review the immutable code, severity, source location and evidence digest.
3. Record a human triage decision with a concise rationale where required.
4. Set or correct the evidence retention deadline according to the organization’s approved retention schedule.
5. Verify both changes through the organization audit ledger and its hash-chain verification endpoint.
