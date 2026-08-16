# GitHub App Manifest

This directory contains the deploy-time GitHub App manifest template for AI-SDLC source-control governance. Before registering an app, replace `${AISDLC_PUBLIC_URL}` with the HTTPS public origin of the deployed control plane. The resulting webhook target is `${AISDLC_PUBLIC_URL}/api/v1/webhooks/github`.

Configure the same high-entropy value in GitHub's webhook secret field and the deployment secret `AISDLC_GITHUB_WEBHOOK_SECRET`. Do not use a URL, private key, installation ID, or webhook secret from this repository as an operational value.

The manifest requests only read access to repository delivery context and write access to Checks. It subscribes only to the events handled by the control plane. See [`docs/github-scm-integration.md`](../../docs/github-scm-integration.md) for the verification, secret rotation, and incident-response procedures.
