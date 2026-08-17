# AI-SDLC CLI

This directory contains the deterministic Go validator for the AI-SDLC platform. It is intentionally dependency-free and does not call AI services. The CLI validates governed artifacts locally, emits CI-friendly evidence, and synchronizes the evidence with the Spring Boot control plane using a Keycloak service identity and idempotency key.

Read the operational command reference in [`../docs/integrations-and-sdks.md#ai-sdlc-cli`](../docs/integrations-and-sdks.md#ai-sdlc-cli). The core safety guarantees are: revision-pinned model provenance is mandatory, `--bare` is prohibited, and final review/exception decisions are always taken by a human in the control plane.

```bash
go test ./...
go build ./cmd/aisdlc
./aisdlc init --project '<uuid>' --api-url 'https://control.example.com' --model 'provider/model@revision'
./aisdlc validate --config .aisdlc.yml --format json --out validation-result.json
```
