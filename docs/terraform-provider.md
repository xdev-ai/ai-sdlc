# Terraform Provider

The provider source resides in `infra/terraform-provider`. It uses Terraform Plugin Framework and exposes a small, auditable configuration surface. It never stores AI-SDLC access tokens in state: `token` is sensitive and may instead be injected using `AISDLC_TOKEN`.

```hcl
terraform {
  required_providers {
    aisdlc = {
      source = "xdev-ai/aisdlc"
      version = "0.1.0"
    }
  }
}

provider "aisdlc" {
  api_url    = var.aisdlc_api_url
  project_id = var.project_id
  # token = var.aisdlc_token # prefer AISDLC_TOKEN in CI
}

resource "aisdlc_notification_channel" "release_governance" {
  type          = "GENERIC_WEBHOOK"
  name          = "release-governance"
  destination   = "https://receiver.example/aisdlc"
  shared_secret = var.webhook_secret
  enabled       = true
}

data "aisdlc_risk_snapshot" "latest" {}
```

`terraform destroy` disables a notification channel instead of deleting it. This preserves delivery/audit evidence and is intentional. Rotation should create a replacement channel, verify the receiving system, then disable the prior channel.

Build and test locally:

```bash
cd infra/terraform-provider
go test ./...
go build ./...
```
