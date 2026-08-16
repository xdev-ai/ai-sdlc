# Security Scan Report — 2026-08-16

**Scope:** AI-SDLC repository security evidence from GitHub Actions CI run [#31924976967](https://github.com/xdev-ai/ai-sdlc/actions/runs/31924976967), commit [`1ccfac8`](https://github.com/xdev-ai/ai-sdlc/commit/1ccfac8). The run completed successfully. This report is a point-in-time evidence summary; it does not replace the live GitHub Code Scanning view.

## Executive Summary

The latest successful CI run completed the OSV full-repository dependency scan and three Trivy scans: repository filesystem, management-server production image, and portal production image. The retained SARIF evidence contains **10 total results**, with **zero `error`-level results**. Under the documented policy, error-level SARIF results represent blocking HIGH/CRITICAL findings, so the Trivy fail-closed gate passed. The OSV SARIF report contains **zero results**.

## Scan Evidence

| Scanner and scope | Evidence artifact | Results | Error | Warning | Note | Policy outcome |
|---|---|---:|---:|---:|---:|---|
| Trivy filesystem: dependencies, secrets, and IaC | `trivy-filesystem.sarif` | 5 | 0 | 3 | 2 | Passed; no blocking result |
| Trivy management-server image | `trivy-management-server.sarif` | 0 | 0 | 0 | 0 | Passed |
| Trivy portal image | `trivy-portal.sarif` | 5 | 0 | 5 | 0 | Passed; no blocking result |
| OSV full repository dependency scan | `results.sarif` | 0 | 0 | 0 | 0 | Passed |
| **Total** | Four SARIF reports | **10** | **0** | **8** | **2** | **Passed** |

The Trivy filesystem evidence references `CVE-2026-54515`, `CVE-2026-59889`, `GHSA-mhm7-754m-9p8w`, and `DS-0026`. The portal-image evidence references `CVE-2026-49844`, `CVE-2026-54515`, `CVE-2026-59889`, and `GHSA-mhm7-754m-9p8w`. These records are retained as non-blocking SARIF evidence and must remain visible in GitHub Code Scanning; they are not silently suppressed.

> The enforcement contract intentionally blocks only SARIF `error` findings. Scanner operational errors still fail the workflow, while `warning` and `note` findings remain available for triage without blocking the release path.

## Control Coverage and Operating Notes

The report confirms that the security pipeline has four independent control paths. OSV Scanner covers dependency vulnerabilities from the OSV database. Trivy covers repository dependencies, secrets, infrastructure-as-code configuration, and the two production images. CodeQL scans supported source languages and GitHub Actions configuration independently. Dependabot opens update pull requests for supported dependency ecosystems.

No active accepted-risk exception was present in `.trivyignore.yaml` for this run. Any future exception must be approved, time-bounded, attributable, and independently validated before it can become active. The exception does not erase SARIF history or scanner-operation failures.

## Reproduction

The raw SARIF reports can be downloaded from the `trivy-security-reports` and `OSV Scanner SARIF file` artifacts attached to CI run #31924976967. The repository utility below summarizes one or more SARIF reports deterministically:

```bash
node scripts/summarize-security-sarif.mjs \
  trivy-filesystem.sarif \
  trivy-management-server.sarif \
  trivy-portal.sarif \
  results.sarif
```

## References

[1]: https://github.com/xdev-ai/ai-sdlc/actions/runs/31924976967 "AI-SDLC CI run #31924976967"
[2]: https://trivy.dev/docs/latest/ "Trivy documentation"
[3]: https://google.github.io/osv-scanner/ "OSV-Scanner documentation"
[4]: https://docs.github.com/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning "GitHub SARIF support for code scanning"
