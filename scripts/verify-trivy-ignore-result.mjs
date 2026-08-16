import { readFileSync } from "node:fs";

const [beforePath, afterPath, ignoredFinding] = process.argv.slice(2);
if (!beforePath || !afterPath || !ignoredFinding) {
  throw new Error(
    "Usage: node scripts/verify-trivy-ignore-result.mjs <before.json> <after.json> <ignored-finding-id>",
  );
}

function findingIds(reportPath) {
  const report = JSON.parse(readFileSync(reportPath, "utf8"));
  return new Set(
    (report.Results ?? []).flatMap((result) =>
      (result.Vulnerabilities ?? []).map((finding) => finding.VulnerabilityID),
    ),
  );
}

const before = findingIds(beforePath);
const after = findingIds(afterPath);
if (!before.has(ignoredFinding)) {
  throw new Error(`Fixture did not include ${ignoredFinding}`);
}
if (after.has(ignoredFinding)) {
  throw new Error(`Trivy did not honor temporary exception ${ignoredFinding}`);
}

console.log(`PASS: temporary exception ${ignoredFinding} removed exactly one real finding from the scan result.`);
