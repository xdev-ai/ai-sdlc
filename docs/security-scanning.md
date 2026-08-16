# Security Scanning

AI-SDLC uses independent security signals rather than a single NVD-backed dependency scanner. This design avoids an operational dependency on an NVD API key while preserving fail-closed checks for newly introduced, actionable security findings.

| Security concern | Control | Primary data source | CI behavior |
|---|---|---|---|
| Open-source dependency vulnerabilities | OSV-Scanner | OSV advisory database | Blocks newly introduced vulnerabilities on pull requests and known vulnerabilities on the default branch. |
| Dependencies, filesystem, secrets, Dockerfiles, and Compose/IaC | Trivy | Trivy vulnerability database and configured upstream advisory sources | Blocks HIGH or CRITICAL findings that are not explicitly documented as accepted risk. |
| Application code and workflow security | CodeQL | GitHub CodeQL query suite | Uploads SARIF findings to GitHub code scanning; protects Java, JavaScript, Go, and GitHub Actions workflows. |
| Dependency freshness | Dependabot | GitHub Advisory Database and ecosystem registries | Opens bounded, reviewable update pull requests for supported manifests and workflow actions. |

The CI workflow must use minimum `contents: read` permission by default and grant `security-events: write` only to SARIF-producing jobs. Scans run on pull requests and `main`; CodeQL and full OSV coverage also run on a weekly schedule so newly published advisories can be detected without source changes.

> OSV-Scanner provides reusable GitHub workflows for pull-request comparison scans and full scans. Its pull-request workflow reports newly introduced vulnerabilities, and its full workflow can fail when known vulnerabilities are found. [1]

> CodeQL supports Java/Kotlin, JavaScript/TypeScript, Go, and GitHub Actions workflows, which covers the managed server, React Islands, CLI, and workflow surface in this repository. [2]

The enforced severity threshold for Trivy is HIGH and CRITICAL. A finding can be suppressed only through a reviewed, time-bounded entry in `.trivyignore.yaml` that records the advisory identifier, rationale, owner, and expiry date. Suppression does not delete SARIF evidence or remove the finding from audit history.

Container-image scan results are retained as CI evidence. Base-image findings must be remediated through a digest-pinned upstream runtime update, a verified minimal-runtime replacement, or a deterministic distribution security upgrade during the image build; they must not be suppressed merely because they originate in an upstream image.

## References

[1]: https://google.github.io/osv-scanner/github-action/ "OSV-Scanner GitHub Action"
[2]: https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql "GitHub Docs: Code scanning with CodeQL"
[3]: https://trivy.dev/docs/latest/tutorials/integrations/github-actions/ "Trivy documentation: GitHub Actions"
[4]: https://docs.github.com/en/code-security/reference/code-scanning/workflow-configuration-options "GitHub Docs: CodeQL workflow configuration options"
