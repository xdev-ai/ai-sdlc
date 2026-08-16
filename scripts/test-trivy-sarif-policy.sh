#!/usr/bin/env sh
set -eu

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

cat > "$tmpdir/warning.sarif" <<'EOF'
{"version":"2.1.0","runs":[{"results":[{"ruleId":"fixture-warning","level":"warning","message":{"text":"Medium fixture"}}]}]}
EOF

cat > "$tmpdir/error.sarif" <<'EOF'
{"version":"2.1.0","runs":[{"results":[{"ruleId":"fixture-error","level":"error","message":{"text":"High fixture"}}]}]}
EOF

node scripts/enforce-trivy-sarif-policy.mjs "$tmpdir/warning.sarif"

if node scripts/enforce-trivy-sarif-policy.mjs "$tmpdir/error.sarif"; then
  echo "Expected error-level SARIF finding to fail the Trivy policy." >&2
  exit 1
fi
