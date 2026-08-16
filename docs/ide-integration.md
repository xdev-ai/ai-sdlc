# IDE Integration

The VS Code extension manifest lives in `ide/vscode`. It provides two commands that call only existing, deterministic interfaces:

| Command | Behavior |
|---|---|
| `AI-SDLC: Validate Workspace` | Runs `aisdlc validate --format junit` in the selected workspace and streams output to the AI-SDLC channel. |
| `AI-SDLC: Open Governance Portal` | Opens the configured `aisdlc.portalUrl` in the default browser. |

Configure `aisdlc.cliPath` when the CLI is not on `PATH`. Configure `aisdlc.portalUrl` with the portal address appropriate to the current project. The extension does not execute an AI model, capture prompt text, make approval decisions, or store access tokens.

Use the supplied validation command as a pre-commit feedback loop. CI and the AI-SDLC control plane remain authoritative for policy gates and human approval requirements.
