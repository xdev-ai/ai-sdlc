# Policy Registry synchronization

The public Registry is a derived, read-only view of the YAML packs under `spec-kit/validators/rules/`. The core repository owns rule IDs, severity, check names, arguments, messages, versions, and source revision. The portal owns only bilingual presentation and remediation guidance keyed to stable rule IDs.

## Event-driven contract

The `Sync Policy Registry` workflow runs only after a push to `main` changes YAML in `spec-kit/validators/rules/`, or when an authorized maintainer starts it manually. It checks out the exact core revision and portal `main`, regenerates `client/src/data/policyPacks.generated.ts`, type-checks and builds the portal, then commits only the changed generated snapshot. The ordinary Pages workflow deploys that commit.

## Required repository secret

Create `PORTAL_REGISTRY_SYNC_TOKEN` in **xdev-ai/ai-sdlc** Actions secrets. Use a fine-grained, short-lived token restricted to **xdev-ai/ai.xdev.asia** with **Contents: Read and write** only. Do not expose it to pull-request workflows, do not store it in source files, and rotate it according to the organization secret-management policy.

## Integrity boundary

The workflow is one-way: YAML changes derive portal facts; portal changes cannot alter policy source. A new pack or rule ID still needs a matching bilingual guidance entry in `policyPacks.ts`; the portal build fails rather than presenting an unreviewed control without owned remediation text.
