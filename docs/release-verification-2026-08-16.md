# Release Verification Report — 2026-08-16

## Scope

The active delivery worktree is `/home/ubuntu/ai-sdlc-new` on `xdev-ai/ai-sdlc` `main` at commit `b7860a9`. This report verifies every independently buildable artifact shipped by that repository.

`/home/ubuntu/ai-sdlc` is an older local clone of the same remote at `14e368b`; it was not treated as a separate product release because it is behind the active `main` worktree. `/home/ubuntu/ai-sdlc-rebuild` is not a Git repository. `/home/ubuntu/ai-sdlc-platform` is a separate Manus web application repository and is not a module of `xdev-ai/ai-sdlc`.

## Results

| Artifact | Verification command | Result |
|---|---|---|
| Java reactor: management server, SSR portal, Java SDK | `mvn --batch-mode --no-transfer-progress verify` | Passed |
| Deterministic Go CLI | `go test ./... && go build ./cmd/aisdlc` | Passed |
| Terraform provider | `gofmt -d .` and `go test ./...` | Passed |
| TypeScript SDK | `npm run build && npm test` | Passed |
| VS Code extension | `npm test` | Passed |
| React Islands frontend | `npm run build` | Passed |
| Production/security guardrails | `scripts/verify-production.sh`, Trivy ignore expiry and SARIF policy tests | Passed |

No build or test failure was found in any independently buildable artifact of the active `xdev-ai/ai-sdlc` release. The repository-specific instructions, configuration, build commands, and verification steps are documented in [`module-usage-and-verification.md`](module-usage-and-verification.md).

## UI Evidence

[`screenshots/portal-landing-en.png`](screenshots/portal-landing-en.png) was captured from the running SSR portal at 1440×1100. It verifies the public landing surface, English localisation, governance model, and control-plane entry point.

Authenticated control-plane pages are intentionally not represented by fabricated static captures. They require an active Keycloak OIDC session plus the management-server API and data services. Their functional evidence is the verified reactor, targeted controller/service tests, and Docker Compose smoke gate. The screenshot scope and runtime prerequisites are stated in [`screenshots/README.md`](screenshots/README.md).
