# Enterprise Deployment: Helm and GitOps

**Status:** Chart and GitOps references published. Not yet installed against a real cluster by this repository's CI.
**Scope:** `todo.md:128` — Helm chart, GitOps reference configuration, hardened defaults, and upgrade/rollback guidance.

## What the chart does and does not own

The chart deploys the two stateless workloads: `management-server` and `portal`.

PostgreSQL, Keycloak, and object storage are **deliberately not subcharts**. They are stateful, security-critical dependencies whose lifecycle must not be coupled to an application upgrade — a `helm rollback` must never be able to roll back a database. Point the chart at existing instances through `config` and `existingSecrets`.

The chart also creates **no Secret of its own**, and the contract test asserts that. Credentials come from Secrets the platform team manages out of band, referenced by name. A chart that templates a secret value puts that value in release history and in every `helm get values` output.

## Hardened defaults

Everything below is the default. `scripts/test-helm-hardening.sh` fails the build if any of it is weakened.

| Control | Default |
|---|---|
| User | Non-root, UID/GID 10001, `fsGroup` 10001 |
| Root filesystem | Read-only, with a memory-backed `/tmp` bounded at 256Mi |
| Capabilities | All dropped, no privilege escalation, `seccompProfile: RuntimeDefault` |
| Service account | Created, token **not** mounted — the control plane calls no Kubernetes API |
| Network | Default-deny ingress and egress; egress opened only to DNS, PostgreSQL, Keycloak, object storage, and OTLP when telemetry is on |
| Management API | `ClusterIP` only, reachable solely from the portal pods. It has no Ingress by design |
| Ingress | Disabled; when enabled, TLS cannot be turned off |
| Images | Digests only. A mutable tag fails the render |
| Telemetry | Disabled, matching the application default. Enabling it without an exporter endpoint fails the render |
| Runtime AI proxy and tool broker | Disabled, matching the application defaults |
| Availability | 2 replicas each, PodDisruptionBudget, resource requests and limits |

Two guards are worth calling out because they refuse to install rather than warn:

- **A mutable image tag is rejected.** `latest` makes a rollback unreproducible and lets a rebuild silently change what runs.
- **Telemetry without an exporter endpoint is rejected**, matching the container entrypoint. A deployment must not believe it is observed when it is not.

## Installing

```sh
helm upgrade --install ai-sdlc infra/helm/ai-sdlc \
  --namespace ai-sdlc \
  --values environments/production/values.yaml \
  --atomic --timeout 10m
```

`--atomic` matters: without it a failed upgrade leaves a partially applied control plane, which is worse than the previous release still running.

Required values with no default: both image digests, `config.database.host`, both Keycloak URIs, and the `existingSecrets` references. The chart fails to render when any is missing, rather than installing something broken that reports success.

## GitOps

Two equivalent references in `infra/gitops/`; use one, not both against the same namespace.

| File | Notes |
|---|---|
| `argocd-application.yaml` | Pinned to a tag, server-side apply, `prune` and `selfHeal` together so an in-cluster edit is reverted rather than retained. `Secret.data` is in `ignoreDifferences` so a sync never fights the platform team's credential rotation. |
| `flux-helmrelease.yaml` | Pinned to a tag, with `upgrade.remediation.strategy: rollback` so a failed upgrade reverts on its own. |

Both point at a **tag or commit, never a branch**. A branch lets an unreviewed push reach production.

## Upgrading

1. Read the changelog entry and check for a Flyway migration in the release. A migration is forward-only; see the rollback limits below.
2. Update the image digests and the chart revision in the config repository. Do not edit the release in the cluster.
3. Apply with `--atomic`, or let the GitOps controller sync.
4. Watch readiness, not just rollout status. Readiness includes the database and the audit ledger, so a pod that cannot record governance evidence is kept out of the Service.
5. Confirm the audit chain verifies after the upgrade before treating it as complete.

## Rolling back

```sh
helm rollback ai-sdlc <revision> --wait --timeout 10m
```

**A rollback returns the application, not the database.** Flyway migrations are forward-only, and the audit ledger is append-only at the database level. Before rolling back across a release that carried a migration:

- Confirm the previous application version tolerates the current schema. Additive migrations usually allow this; a migration that drops or narrows a column does not.
- If it does not, restore from a verified backup and accept the data loss window, following the PostgreSQL restore procedure in [`production-operations.md`](production-operations.md). Do not attempt to reverse a migration by hand against a live audit ledger.
- Never delete or edit audit rows to make an older version start. That is a governance incident in itself.

Rolling back the portal alone is always safe; it holds no state.

## Verification

```sh
helm lint infra/helm/ai-sdlc
sh scripts/test-helm-hardening.sh
```

The hardening test asserts the values-level defaults with no tooling, then renders the chart with Helm to check the manifest-level properties and to prove both refusals actually refuse. CI runs it on every pull request.

## Verified on a real cluster

The chart has been installed on a live Kubernetes cluster (kind, v1.36.1) against a local registry so image digests are genuine manifest digests rather than local tags.

Confirmed by the API server and by the running pods, not by the templates:

| Check | Result |
|---|---|
| Both workloads admitted and Ready | `management-server` 1/1, `portal` 1/1 |
| Images resolved **by digest** | `…/management-server@sha256:aa708c…`, `…/portal@sha256:e920d8…` |
| `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities: [ALL]` dropped, `seccompProfile: RuntimeDefault` | all applied |
| `automountServiceAccountToken` | `false` |
| Resource limits | applied |
| Services | all `ClusterIP`; no Ingress, no NodePort |
| Flyway against in-cluster PostgreSQL | 18 migrations applied, latest `V18` |
| Readiness including `db` and `auditLedger` | `{"status":"UP"}` |

### Two chart defects the install found

Neither was visible to `helm lint` or `helm template`.

1. **`volumes` was nested inside `containers`** in the portal deployment. Server-side apply rejected it: `field not declared in schema`. Rendering produced valid YAML, just not a valid Deployment.
2. **The portal referenced the management-server's digest.** The chart rendered and linted cleanly and would have deployed the management server under the portal's name. Only an actual image pull exposed it. `scripts/test-helm-hardening.sh` now asserts each workload references its own digest.

Still not exercised: an ingress controller, a real CNI enforcing the NetworkPolicies (kind's default CNI does not), and Keycloak or object storage in-cluster.
