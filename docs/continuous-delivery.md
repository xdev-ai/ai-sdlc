# Continuous Integration and Release Delivery

The repository contains two independent GitHub Actions workflows. They execute on GitHub-hosted runners, while production deployment remains an operator-controlled promotion step after the quality gates pass.

| Workflow | Trigger | Required verification | Output |
|---|---|---|---|
| `CI` | Push to `main`, pull request to `main`, or manual dispatch | Maven verification on Java 25, Go 1.24 test/build and format, Vite production build, PR dependency review, OWASP dependency scan | Maven/OWASP reports when applicable |
| `Release` | Signed release-tag push (`v*`) or manual dispatch of an existing tag | Maven verification and static Go cross-compilation | Management server JAR, portal JAR, Linux/Darwin CLI binaries and `SHA256SUMS` |

The dependency scan invokes OWASP Dependency-Check’s Maven integration. Configure `NVD_API_KEY` as a repository secret; this is a required security-gate prerequisite, not an optional optimization. The workflow fails before scanning with an explicit remediation message when the secret is absent and never echoes the secret. Dependency-Check is a software composition analysis tool that identifies known vulnerable components using dependency evidence and associated CVE data.[1]

Release artifacts contain SHA-256 checksums. Before an artifact is introduced to any deployment registry, operators must verify its checksum against the `SHA256SUMS` file published with the GitHub release.

```bash
sha256sum --check SHA256SUMS
```

The CI workflow intentionally fails on a CVSS score of `9` or greater. Lower severity findings still publish a report and require a risk decision; this avoids treating automated CPE matching as the sole authority for a production decision.

## References

[1] [OWASP Dependency-Check — official project documentation](https://owasp.org/www-project-dependency-check/)
