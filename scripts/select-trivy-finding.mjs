import { readFileSync } from "node:fs";

const [reportPath] = process.argv.slice(2);
if (!reportPath) {
  throw new Error("Usage: node scripts/select-trivy-finding.mjs <report.json>");
}

const report = JSON.parse(readFileSync(reportPath, "utf8"));
for (const result of report.Results ?? []) {
  for (const finding of result.Vulnerabilities ?? []) {
    if (finding.VulnerabilityID) {
      process.stdout.write(finding.VulnerabilityID);
      process.exit(0);
    }
  }
}

process.exit(1);
