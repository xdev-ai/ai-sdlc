# Security

The scanning gates, what they have actually caught, the supply-chain controls, and dependency decisions made deliberately rather than by inertia.

- [Security Scanning](#security-scanning)
- [Supply-Chain Security and Release Provenance](#supply-chain-security-and-release-provenance)
- [Production Dependency Decisions](#production-dependency-decisions)
- [Security Scan Report — 2026-08-16](#security-scan-report-2026-08-16)

## Security Scanning

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

### References

[1]: https://google.github.io/osv-scanner/github-action/ "OSV-Scanner GitHub Action"
[2]: https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql "GitHub Docs: Code scanning with CodeQL"
[3]: https://trivy.dev/docs/latest/tutorials/integrations/github-actions/ "Trivy documentation: GitHub Actions"
[4]: https://docs.github.com/en/code-security/reference/code-scanning/workflow-configuration-options "GitHub Docs: CodeQL workflow configuration options"

---

### Customer identifiers must not reach a public repository

Every repository in the `xdev-ai` organisation is public, and publication is not reversible by
deletion: a removed branch keeps its commits reachable through the API, and anything merged stays
in history. The rule therefore covers commit messages, PR titles and bodies, comments, javadoc and
test fixtures — not only committed files, which is the gap that let real customer document codes
into three commit messages while a path-only gate reported clean.

Illustrate designs with invented identifiers (`SPEC-042`, `DOC-001`). `scripts/verify-no-confidential.sh`
enforces this: it refuses tracked or staged files under `local/` and `private/`, and scans staged
content, unpushed commit messages and a prepared commit message against an operator-maintained term
list at `local/confidential-terms.txt` (gitignored — it lists the strings being protected). Install
`git config core.hooksPath .githooks` so the check runs before a commit exists; the CI job is the
backstop that cannot be skipped.

The term check is deliberately an explicit list rather than a heuristic. A scanner with false
positives gets switched off, and one with false negatives is worse than none because it turns an
unchecked risk into a checked box.

## Supply-Chain Security and Release Provenance

### Scope

This document defines the AI-SDLC release-evidence contract for software bill of materials (SBOM), artifact digests, build provenance, and signature evidence. It supplements, rather than replaces, OSV, Trivy, CodeQL, checksum, and immutable Evidence Repository controls.

### Design Sources

The implementation uses the CycloneDX Maven plugin `makeAggregateBom` goal at Maven `verify` to generate a reactor-wide JSON SBOM containing direct and transitive dependencies. CycloneDX `2.9.x` supports schema `1.6` and JSON output.[^cyclonedx]

SLSA describes provenance as verifiable information that connects an artifact to where, when, and how it was produced. A build-provenance record therefore keeps the artifact digest, source repository and revision, build system and run URL, signer identity, and attestation reference.[^slsa]

GitHub artifact attestations require `contents: read`, `id-token: write`, and `attestations: write`; GitHub's current `actions/attest@v4` can attest a binary with `subject-path` and an SBOM with `subject-path` plus `sbom-path`.[^github-attest] New workflows use `actions/attest@v4`, because `actions/attest-build-provenance` version 4 is a wrapper around it.[^attest-wrapper]

Sigstore keyless signing binds a short-lived certificate to an OpenID Connect identity and records signing events in the Rekor transparency log. This can provide additional signature evidence for OCI artifacts or artifacts whose distribution channel supports Cosign verification.[^sigstore]

### Platform Data Contract

| Record | Required evidence | Trust statement |
|---|---|---|
| `SbomAsset` | Immutable Evidence Repository asset, parsed format/version, component count, document SHA-256 | The stored file was accepted only after digest verification and schema-specific parsing. |
| `ProvenanceRecord` | Artifact digest, source repo/revision, build identity, signature method, signer identity | A submitted claim begins as `DECLARED`; the platform does not falsely report cryptographic verification. |
| Verified provenance | Reviewer/owner decision and verification note, optionally immutable verification evidence | `VERIFIED` is a governed human conclusion after independent validation such as `gh attestation verify` or `cosign verify-attestation`. |

The release workflow produces SBOM and provenance attestations only after OSV and Trivy gates pass. It retains the SBOM, checksum, and attestation-verification output as release evidence. No workflow secret is persisted in a provenance record.

The workflow additionally supports an opt-in keyless Sigstore signature over `SHA256SUMS`. Set the repository variable `AISDLC_COSIGN_ENABLED` to `true` only after the release-owner identity and monitoring process are established. The signed checksum manifest covers the released files listed in it; GitHub attestations provide individual artifact provenance. The bundle is uploaded as `SHA256SUMS.cosign.bundle` with the release assets.

### Verification Procedure

For GitHub build attestations, retrieve the released artifact and run:

```bash
gh attestation verify PATH/TO/ARTIFACT -R xdev-ai/ai-sdlc
```

For a CycloneDX SBOM attestation, use the appropriate CycloneDX predicate type supported by the producing workflow and save the structured output as governed verification evidence. A reviewer must then record an explicit verification decision in the platform; successful CI alone does not bypass the human approval invariant.

### References

[^cyclonedx]: [CycloneDX Maven Plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin/)
[^slsa]: [SLSA Provenance](https://slsa.dev/provenance)
[^github-attest]: [GitHub Docs: Establish provenance for builds](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations/using-artifact-attestations-to-establish-provenance-for-builds)
[^attest-wrapper]: [`actions/attest-build-provenance` repository](https://github.com/actions/attest-build-provenance)
[^sigstore]: [Sigstore Cosign signing overview](https://docs.sigstore.dev/cosign/signing/overview/)

---

## Production Dependency Decisions

This record captures externally verified dependencies introduced by the production-hardening workstream. Versions are pinned in the management-server Maven module to keep builds reproducible.

| Dependency | Pinned version | Purpose | Decision basis |
|---|---:|---|---|
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | `3.1.0` | Generates the protected OpenAPI document and browser documentation for the Spring MVC control plane. | The project documentation states Spring Boot 4 support and publishes the corresponding Maven coordinate.[1] |
| `com.bucket4j:bucket4j_jdk17-core` | `8.19.0` | Provides token-bucket limits for API endpoints. | Maven Central lists the JDK 17 core artifact and the selected version.[2] |
| `net.logstash.logback:logstash-logback-encoder` | `9.0` | Emits structured JSON logs and preserves MDC correlation identifiers. | The project release information identifies version 9.0 as a Java 17+ compatible major release.[3] |
| `software.amazon.awssdk:bom`, `s3`, `url-connection-client` | `2.29.52` | Provides the S3-compatible Evidence Repository adapter and short-lived presigned downloads without exposing provider SDKs to domain code. | AWS documents BOM import for aligned SDK versions and recommends including only the required service/HTTP modules.[4] |

The current rate limiter is intentionally in-memory and applies to each server instance. The operational runbook must require a distributed backend before horizontally scaling the control plane so rate policies remain globally consistent.

### References

[1]: https://springdoc.org/ "springdoc-openapi: Spring Boot 4 support and Maven setup"
[2]: https://central.sonatype.com/artifact/com.bucket4j/bucket4j_jdk17-core "Maven Central: Bucket4j JDK 17 Core 8.19.0"
[3]: https://github.com/logfellow/logstash-logback-encoder/releases "Logstash Logback Encoder releases"
[4]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html "AWS SDK for Java 2.x: Maven setup and BOM alignment"

---

## Security Scan Report — 2026-08-16

**Scope:** AI-SDLC repository security evidence from GitHub Actions CI run [#31924976967](https://github.com/xdev-ai/ai-sdlc/actions/runs/31924976967), commit [`1ccfac8`](https://github.com/xdev-ai/ai-sdlc/commit/1ccfac8). The run completed successfully. This report is a point-in-time evidence summary; it does not replace the live GitHub Code Scanning view.

### Executive Summary

The latest successful CI run completed the OSV full-repository dependency scan and three Trivy scans: repository filesystem, management-server production image, and portal production image. The retained SARIF evidence contains **10 total results**, with **zero `error`-level results**. Under the documented policy, error-level SARIF results represent blocking HIGH/CRITICAL findings, so the Trivy fail-closed gate passed. The OSV SARIF report contains **zero results**.

### Scan Evidence

| Scanner and scope | Evidence artifact | Results | Error | Warning | Note | Policy outcome |
|---|---|---:|---:|---:|---:|---|
| Trivy filesystem: dependencies, secrets, and IaC | `trivy-filesystem.sarif` | 5 | 0 | 3 | 2 | Passed; no blocking result |
| Trivy management-server image | `trivy-management-server.sarif` | 0 | 0 | 0 | 0 | Passed |
| Trivy portal image | `trivy-portal.sarif` | 5 | 0 | 5 | 0 | Passed; no blocking result |
| OSV full repository dependency scan | `results.sarif` | 0 | 0 | 0 | 0 | Passed |
| **Total** | Four SARIF reports | **10** | **0** | **8** | **2** | **Passed** |

The Trivy filesystem evidence references `CVE-2026-54515`, `CVE-2026-59889`, `GHSA-mhm7-754m-9p8w`, and `DS-0026`. The portal-image evidence references `CVE-2026-49844`, `CVE-2026-54515`, `CVE-2026-59889`, and `GHSA-mhm7-754m-9p8w`. These records are retained as non-blocking SARIF evidence and must remain visible in GitHub Code Scanning; they are not silently suppressed.

> The enforcement contract intentionally blocks only SARIF `error` findings. Scanner operational errors still fail the workflow, while `warning` and `note` findings remain available for triage without blocking the release path.

### Control Coverage and Operating Notes

The report confirms that the security pipeline has four independent control paths. OSV Scanner covers dependency vulnerabilities from the OSV database. Trivy covers repository dependencies, secrets, infrastructure-as-code configuration, and the two production images. CodeQL scans supported source languages and GitHub Actions configuration independently. Dependabot opens update pull requests for supported dependency ecosystems.

No active accepted-risk exception was present in `.trivyignore.yaml` for this run. Any future exception must be approved, time-bounded, attributable, and independently validated before it can become active. The exception does not erase SARIF history or scanner-operation failures.

### Reproduction

The raw SARIF reports can be downloaded from the `trivy-security-reports` and `OSV Scanner SARIF file` artifacts attached to CI run #31924976967. The repository utility below summarizes one or more SARIF reports deterministically:

```bash
node scripts/summarize-security-sarif.mjs \
  trivy-filesystem.sarif \
  trivy-management-server.sarif \
  trivy-portal.sarif \
  results.sarif
```

### References

[1]: https://github.com/xdev-ai/ai-sdlc/actions/runs/31924976967 "AI-SDLC CI run #31924976967"
[2]: https://trivy.dev/docs/latest/ "Trivy documentation"
[3]: https://google.github.io/osv-scanner/ "OSV-Scanner documentation"
[4]: https://docs.github.com/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning "GitHub SARIF support for code scanning"
