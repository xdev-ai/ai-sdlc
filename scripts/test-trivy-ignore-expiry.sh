#!/usr/bin/env sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/future.yaml" <<'YAML'
vulnerabilities:
  - id: CVE-2099-0001
    expired_at: 2099-12-31
    statement: "rationale=fixture only; owner=security; approver=reviewer; approved_at=2099-01-01; ticket=SEC-1"
misconfigurations: []
secrets: []
licenses: []
YAML

node "$root_dir/scripts/validate-trivy-ignore-expiry.mjs" "$tmp_dir/future.yaml" 2026-08-16

cat > "$tmp_dir/expired.yaml" <<'YAML'
vulnerabilities:
  - id: CVE-2000-0001
    expired_at: 2026-08-15
    statement: "rationale=fixture only; owner=security; approver=reviewer; approved_at=2026-01-01; ticket=SEC-2"
misconfigurations: []
secrets: []
licenses: []
YAML

if node "$root_dir/scripts/validate-trivy-ignore-expiry.mjs" "$tmp_dir/expired.yaml" 2026-08-16; then
  echo 'Expected expired exception validation to fail.' >&2
  exit 1
fi

echo 'Trivy ignore-file expiry validator regression tests passed.'
