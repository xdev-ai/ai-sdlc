#!/usr/bin/env sh
# Verifies a temporary, time-bounded Trivy YAML exception against a real image.
# This script never modifies the repository's .trivyignore.yaml template.
set -eu

if ! command -v trivy >/dev/null 2>&1; then
  echo "Trivy CLI is required. Install Trivy and rerun this test." >&2
  exit 127
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM
image_ref="${TRIVY_EXCEPTION_TEST_IMAGE:-alpine:3.16}"
before="$work_dir/before.json"
after="$work_dir/after.json"
ignorefile="$work_dir/exception.yaml"

echo "Scanning $image_ref to select a real vulnerability for temporary exception verification..."
trivy image --scanners vuln --severity HIGH,CRITICAL --format json --output "$before" "$image_ref"

vulnerability_id="$(node scripts/select-trivy-finding.mjs "$before")" || {
  echo "No HIGH/CRITICAL vulnerability was found in $image_ref; choose a test image with a current finding." >&2
  exit 1
}

cat > "$ignorefile" <<EOF
vulnerabilities:
  - id: $vulnerability_id
    expired_at: 2099-12-31
    statement: "rationale=temporary local exception test; owner=security-engineering; approver=test-harness; approved_at=2026-08-16; ticket=LOCAL-TEST"
misconfigurations: []
secrets: []
licenses: []
EOF

trivy image --scanners vuln --severity HIGH,CRITICAL --format json --output "$after" --ignorefile "$ignorefile" "$image_ref"

node scripts/verify-trivy-ignore-result.mjs "$before" "$after" "$vulnerability_id"
