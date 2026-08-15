# AI-SDLC CLI

`aisdlc` is a local, deterministic governance validator. It performs filesystem inspection, hashing, serialization and HTTPS calls only. It **does not invoke an AI model**, transmit source files for model inference, or permit a bare/governance-bypass mode.

## Invariants

| Invariant | Enforcement |
|---|---|
| Pinned model provenance | `validate` requires `--model provider/model@immutable-revision`; floating aliases and `latest` are rejected. |
| No bare execution | `--bare` always returns an error, even when all artifacts are otherwise valid. |
| Human decisions remain human | The CLI can create evidence and review requests but cannot submit a final review or exception decision. |
| Retry-safe evidence sync | Every `sync` requires an explicit idempotency key and resubmits the same payload/key when retrying transient failures. |

## Configuration and Authentication

Initialize a repository-scoped configuration file and review it with the same care as any governance artifact:

```bash
aisdlc init \
  --project "<project-uuid>" \
  --api-url "https://control.example.com" \
  --spec-dir "." \
  --kit-version "core@1.2.0" \
  --model "provider/model@immutable-revision"
```

This generates `.aisdlc.yml`, which contains project routing and governance provenance but **never credentials**. A build uses this file by default, or a different path via `--config`.

For developers and CI identities, the Keycloak CLI client uses OAuth 2.0 client credentials. The client realm intentionally does not enable password grants. Obtain a token with an injected secret rather than committing credentials:

```bash
export AISDLC_CLIENT_SECRET="…"
aisdlc login \
  --token-url "https://auth.example.com/realms/ai-sdlc/protocol/openid-connect/token"
```

The command stores a short-lived token in the platform configuration directory using `0600` file permissions. At sync time the lookup order is `--token`, `AISDLC_ACCESS_TOKEN`, then the stored token. CI should prefer the environment variable and avoid persisting credentials on runners.

## Deterministic Validation

```bash
aisdlc validate --config .aisdlc.yml --format json --out validation-result.json
```

The validator checks for `constitution.md`, `spec.md` and `tasks.md`; rejects empty artifacts; requires a version/revision declaration in the constitution; requires Markdown structure in the specification; and requires checkbox tasks in the task list. It produces a stable SHA-256 digest over a sorted Spec Kit file tree as evidence. A non-passing result exits with code `1`; invocation/configuration errors exit with code `2`.

## CI Output and Sync

The `--format` option produces JSON (the sync payload), JUnit XML, or SARIF 2.1.0:

```bash
aisdlc validate --format junit --out aisdlc.junit.xml || true
aisdlc validate --format sarif --out aisdlc.sarif || true
aisdlc validate --format json --out validation-result.json
aisdlc sync --result validation-result.json --idempotency-key "${GITHUB_RUN_ID}-${GITHUB_SHA}"
```

`sync` retries transport failures, HTTP `429`, and 5xx responses with bounded exponential backoff and respects a bounded numeric `Retry-After` response. Validation failures other than those transient responses do not retry. HTTP `409` is reported as a likely idempotency conflict with the server response body to support operator diagnosis.

Use `aisdlc status --json` for a local, non-network diagnostic of config and the last JSON validation result.
