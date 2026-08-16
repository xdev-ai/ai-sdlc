# Continuous Integration and Release Delivery

The repository contains two independent GitHub Actions workflows. They execute on GitHub-hosted runners, while production deployment remains an operator-controlled promotion step after the quality gates pass.

| Workflow | Trigger | Required verification | Output |
|---|---|---|---|
| `CI` | Push to `main`, pull request to `main`, or manual dispatch | Maven verification on Java 25, Go 1.24 test/build and format, Vite production build, dependency review, OSV dependency scan, and Trivy source/production-image scan | Maven, Trivy, and SARIF reports when applicable |
| `CodeQL` | Push/PR to `main`, manual dispatch, and weekly schedule | Security-extended and security-and-quality analysis for Java, JavaScript/TypeScript, Go, and GitHub Actions | Code-scanning alerts and SARIF evidence |
| `Release` | Signed release-tag push (`v*`) or manual dispatch of an existing tag | OSV and Trivy release-security gates, Maven verification, and static Go cross-compilation | Management server JAR, portal JAR, Linux/Darwin CLI binaries, security reports, and `SHA256SUMS` |

The security pipeline does not require an NVD API key. OSV-Scanner provides dependency-vulnerability scanning and compares newly introduced vulnerabilities on pull requests while running a full scan on `main` and release workflows. Trivy scans dependencies, secrets, Dockerfiles, Compose/IaC configuration, and the two production images; HIGH and CRITICAL findings fail the gate. CodeQL analyzes Java, JavaScript/TypeScript, Go, and GitHub Actions source. Dependabot provides recurring update pull requests for Maven, npm, Go modules, and GitHub Actions. See [`security-scanning.md`](security-scanning.md) for remediation, suppression, evidence, and source-policy details.[1] [2] [3]

Release artifacts contain SHA-256 checksums. Before an artifact is introduced to any deployment registry, operators must verify its checksum against the `SHA256SUMS` file published with the GitHub release.

```bash
sha256sum --check SHA256SUMS
```

The Trivy security gate intentionally fails on HIGH and CRITICAL actionable findings. OSV blocks newly introduced dependency vulnerabilities in pull requests and known vulnerabilities on protected release paths. A lower-severity finding remains visible in uploaded SARIF evidence and requires a documented risk decision where it affects the release posture.

## References

[1] [OSV-Scanner GitHub Action](https://google.github.io/osv-scanner/github-action/)

[2] [Trivy GitHub Actions integration](https://trivy.dev/docs/latest/tutorials/integrations/github-actions/)

[3] [GitHub CodeQL code scanning](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql)
