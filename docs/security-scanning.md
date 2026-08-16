# Security Scanning

AI-SDLC uses independent security signals rather than a single NVD-backed dependency scanner. This design avoids an operational dependency on an NVD API key while preserving fail-closed checks for newly introduced, actionable security findings.

| Security concern | Control | Primary data source | CI behavior |
|---|---|---|---|
| Open-source dependency vulnerabilities | OSV-Scanner | OSV advisory database | Blocks newly introduced vulnerabilities on pull requests and known vulnerabilities on the default branch. |
| Dependencies, filesystem, secrets, Dockerfiles, and Compose/IaC | Trivy | Trivy vulnerability database and configured upstream advisory sources | Blocks HIGH or CRITICAL findings that are not explicitly documented as accepted risk. |
| Application code and workflow security | CodeQL | GitHub CodeQL query suite | Uploads SARIF findings to GitHub code scanning; protects Java, JavaScript, Go, and GitHub Actions workflows. |
| Dependency freshness | Dependabot | GitHub Advisory Database and ecosystem registries | Opens bounded, reviewable update pull requests for supported manifests and workflow actions. |

The CI workflow must use minimum `contents: read` permission by default and grant `security-events: write` only to SARIF-producing jobs. OSV scans run on pull requests and on every push to `main`; the full OSV reusable workflow also gates tagged releases. CodeQL runs on pull requests, pushes to `main`, manual dispatch, and a weekly schedule, so newly published code-security advisories can be detected without source changes.

> OSV-Scanner provides reusable GitHub workflows for pull-request comparison scans and full scans. Its pull-request workflow reports newly introduced vulnerabilities, and its full workflow can fail when known vulnerabilities are found. [1]

> CodeQL supports Java/Kotlin, JavaScript/TypeScript, Go, and GitHub Actions workflows, which covers the managed server, React Islands, CLI, and workflow surface in this repository. [2]

The enforced severity threshold for Trivy is HIGH and CRITICAL. The CI policy reads the generated SARIF and blocks only `error`-level findings, which Trivy maps from that threshold; MEDIUM/LOW findings are retained as evidence and uploaded to code scanning without blocking delivery.

`.trivyignore.yaml` is an intentionally empty, repository-managed template that is passed explicitly to every Trivy scan with `--ignorefile`. It contains no active accepted-risk entries by default. An entry may be uncommented or added only when an accepted-risk exception is approved. Each entry must identify the advisory, affected package or artifact, business/technical rationale, approving reviewer, accountable owner, approval date, and an explicit expiry date. The scanner-supported fields are `id`, optional `paths`/`purls`, `expired_at`, and `statement`; the required review metadata is recorded in the structured `statement` value. The exception must be reviewed before expiry, removed as soon as remediation is available, and never used to suppress a scanner-operational failure. Suppression does not delete SARIF evidence or remove the original finding from audit history.

Run `sh scripts/test-trivy-ignorefile.sh` on a workstation with the Trivy CLI and network access to verify that Trivy honors a temporary, time-bounded YAML exception against a real HIGH/CRITICAL finding. The test creates its exception file in a temporary directory, deletes it on exit, and never adds a live suppression to the repository template.

Any active exception is validated before every Trivy scan by `node scripts/validate-trivy-ignore-expiry.mjs .trivyignore.yaml`. The validator fails closed for expired dates, malformed dates, missing ownership/review metadata, placeholder IDs, or unsupported sections; it emits an operational warning when an exception will expire within 30 days. Run `sh scripts/test-trivy-ignore-expiry.sh` to prove that a future-dated governed exception is accepted and an expired exception fails.

Container-image scan results are retained as CI evidence. Base-image findings must be remediated through a digest-pinned upstream runtime update, a verified minimal-runtime replacement, or a deterministic distribution security upgrade during the image build; they must not be suppressed merely because they originate in an upstream image.

## References

[1]: https://google.github.io/osv-scanner/github-action/ "OSV-Scanner GitHub Action"
[2]: https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql "GitHub Docs: Code scanning with CodeQL"
[3]: https://trivy.dev/docs/latest/tutorials/integrations/github-actions/ "Trivy documentation: GitHub Actions"
[4]: https://docs.github.com/en/code-security/reference/code-scanning/workflow-configuration-options "GitHub Docs: CodeQL workflow configuration options"
