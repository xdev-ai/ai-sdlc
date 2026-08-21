# Sandbox Docker Compose and OIDC Login

This guide starts a **disposable local test topology** containing PostgreSQL, Keycloak, MinIO, the management server, the identity gateway, and the SSR portal. It is intended for an interactive browser verification of the Keycloak authorization-code flow. It does not replace a TLS-terminated production deployment.

> The launcher intentionally does not create, read, or store an environment file. Supply fresh, disposable secrets from the current shell only. Never reuse these values for production, and never commit them.

## Prerequisites

Docker Engine and Docker Compose v2 are required. The default task sandbox does not provide Docker, so run this topology on an authorized Docker host, a Cloud Computer, or a local machine. The stack binds only to `127.0.0.1` by default: it is available to the local browser but not published on every network interface.

## Prepare one disposable shell session

The following creates non-production values in the current shell. It does not write a `.env` file. Enter the test user password interactively because it will be used to sign into Keycloak as `platform-admin`.

```bash
cd /path/to/ai-sdlc

export POSTGRES_USER=aisdlc_sandbox
export POSTGRES_DB=aisdlc
export KEYCLOAK_ADMIN=admin
export POSTGRES_PASSWORD="$(openssl rand -base64 36)"
export KEYCLOAK_ADMIN_PASSWORD="$(openssl rand -base64 36)"
export PORTAL_CLIENT_SECRET="$(openssl rand -base64 36)"
export CLI_CLIENT_SECRET="$(openssl rand -base64 36)"
export AGENT_RUNTIME_CLIENT_SECRET="$(openssl rand -base64 36)"
export AISDLC_GITHUB_WEBHOOK_SECRET="$(openssl rand -hex 32)"
export AISDLC_NOTIFICATION_ENCRYPTION_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
export AISDLC_EVIDENCE_S3_ACCESS_KEY=aisdlc-sandbox
export AISDLC_EVIDENCE_S3_SECRET_KEY="$(openssl rand -base64 36)"
read -rsp 'Disposable platform-admin password: ' LOCAL_ADMIN_PASSWORD; echo
export LOCAL_ADMIN_PASSWORD
```

| Value | Scope | Handling rule |
| --- | --- | --- |
| `LOCAL_ADMIN_PASSWORD` | Keycloak user `platform-admin` | Enter interactively; use only for the local browser test. |
| `PORTAL_CLIENT_SECRET` | `aisdlc-portal` authorization-code client | Keycloak realm import and portal must receive the same shell value. |
| `CLI_CLIENT_SECRET` and `AGENT_RUNTIME_CLIENT_SECRET` | Non-browser service clients | Required to make the full imported realm deterministic; do not use them in a browser. |
| PostgreSQL, MinIO, encryption and webhook values | Disposable infrastructure | Required by dependent services; rotate by destroying the local stack. |

## Start and verify the topology

```bash
scripts/run-sandbox-stack.sh up
```

The launcher validates Compose interpolation, waits for service readiness, validates the public Keycloak discovery document, probes management-server readiness inside its private network, and follows the portal authorization redirect as far as the real Keycloak login page. It does **not** submit credentials automatically.

Open the portal in the same machine's browser:

```text
http://localhost:8080/app
```

Sign in with username `platform-admin` and the value supplied as `LOCAL_ADMIN_PASSWORD`. The realm admits only the declared redirect URIs for `localhost` and `127.0.0.1`; use one of those loopback hosts rather than a public tunnelling URL. On a successful sign-in, the portal exchanges the code on the internal gateway URL, validates the ID-token signature through the internal JWK set, and returns to the saved `/app` request.

## Operational commands

| Command | Effect |
| --- | --- |
| `scripts/run-sandbox-stack.sh status` | Show the disposable stack's service state. |
| `scripts/run-sandbox-stack.sh logs portal` | Read the latest portal logs. Do not paste logs containing secrets into tickets. |
| `scripts/run-sandbox-stack.sh verify` | Re-run health, discovery and login-form reachability checks. |
| `scripts/run-sandbox-stack.sh down` | Stop containers but retain disposable volumes for troubleshooting. |
| `scripts/run-sandbox-stack.sh reset` | Remove containers and volumes of this sandbox project; this deletes test data. |

## Troubleshooting

| Symptom | Check | Safe response |
| --- | --- | --- |
| `Docker CLI is required` | The environment is the default task sandbox. | Use an authorized Docker host; do not attempt to install privileged Docker services in the default sandbox. |
| Portal redirects to an unavailable `auth.localhost` | `scripts/run-sandbox-stack.sh status` should show `identity-gateway` running on `127.0.0.1:8180`. | Start the Compose topology; do not replace the issuer with an arbitrary external URL. |
| Keycloak rejects the redirect URI | Confirm the browser uses `http://localhost:8080/app` or `http://127.0.0.1:8080/app`. | Do not broaden the realm to wildcard public redirect URIs. |
| Portal shows session recovery after sign-in | Inspect `scripts/run-sandbox-stack.sh logs portal` and `logs keycloak`. | Confirm the portal and realm received the same `PORTAL_CLIENT_SECRET`; restart with a fresh, consistent shell session. |
