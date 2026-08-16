import { readFileSync } from 'node:fs';

const reportPaths = process.argv.slice(2);

if (reportPaths.length === 0) {
  console.error('Usage: node scripts/enforce-trivy-sarif-policy.mjs <report.sarif> [...]');
  process.exit(2);
}

let findings = 0;
let invalidReports = 0;

for (const reportPath of reportPaths) {
  try {
    const report = JSON.parse(readFileSync(reportPath, 'utf8'));
    const results = Array.isArray(report.runs)
      ? report.runs.flatMap((run) => Array.isArray(run.results) ? run.results : [])
      : [];
    const blockingResults = results.filter((result) => result.level === 'error');
    findings += blockingResults.length;

    for (const result of blockingResults) {
      const location = result.locations?.[0]?.physicalLocation?.artifactLocation?.uri ?? 'unknown location';
      const message = result.message?.text ?? result.ruleId ?? 'Trivy finding';
      console.error(`::error file=${location}::${reportPath}: ${message}`);
    }
  } catch (error) {
    invalidReports += 1;
    console.error(`::error::Unable to read Trivy SARIF report ${reportPath}: ${error.message}`);
  }
}

if (invalidReports > 0 || findings > 0) {
  console.error(`Trivy policy blocked ${findings} HIGH/CRITICAL finding(s) across ${reportPaths.length} SARIF report(s).`);
  process.exit(1);
}

console.log(`Trivy policy passed: no HIGH/CRITICAL findings in ${reportPaths.length} SARIF report(s).`);
