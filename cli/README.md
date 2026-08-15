# AI-SDLC CLI

The CLI is a **deterministic validation boundary**. It does not call an LLM, does not make governance decisions, and cannot run in bare mode. It requires an explicitly revision-pinned model reference solely as provenance metadata.

```bash
go run ./cmd/aisdlc validate \
  --spec-dir ./spec-kit \
  --kit-version core@1.0.0 \
  --model anthropic/claude-sonnet@2026-01-15 \
  --out validation-result.json

AISDLC_ACCESS_TOKEN="${TOKEN}" go run ./cmd/aisdlc sync \
  --result validation-result.json \
  --api-url https://control.example.com \
  --project 00000000-0000-0000-0000-000000000000 \
  --idempotency-key ci-run-0001
```

`sync` posts to the Spring Boot API’s `/api/v1/cli/projects/{projectId}/validation-runs` endpoint using an OAuth2 access token and an idempotency key. The API records the evidence, all findings and a linked immutable audit event in one transaction.

