# Supply-Chain Security and Release Provenance

## Scope

This document defines the AI-SDLC release-evidence contract for software bill of materials (SBOM), artifact digests, build provenance, and signature evidence. It supplements, rather than replaces, OSV, Trivy, CodeQL, checksum, and immutable Evidence Repository controls.

## Design Sources

The implementation uses the CycloneDX Maven plugin `makeAggregateBom` goal at Maven `verify` to generate a reactor-wide JSON SBOM containing direct and transitive dependencies. CycloneDX `2.9.x` supports schema `1.6` and JSON output.[^cyclonedx]

SLSA describes provenance as verifiable information that connects an artifact to where, when, and how it was produced. A build-provenance record therefore keeps the artifact digest, source repository and revision, build system and run URL, signer identity, and attestation reference.[^slsa]

GitHub artifact attestations require `contents: read`, `id-token: write`, and `attestations: write`; GitHub's current `actions/attest@v4` can attest a binary with `subject-path` and an SBOM with `subject-path` plus `sbom-path`.[^github-attest] New workflows use `actions/attest@v4`, because `actions/attest-build-provenance` version 4 is a wrapper around it.[^attest-wrapper]

Sigstore keyless signing binds a short-lived certificate to an OpenID Connect identity and records signing events in the Rekor transparency log. This can provide additional signature evidence for OCI artifacts or artifacts whose distribution channel supports Cosign verification.[^sigstore]

## Platform Data Contract

| Record | Required evidence | Trust statement |
|---|---|---|
| `SbomAsset` | Immutable Evidence Repository asset, parsed format/version, component count, document SHA-256 | The stored file was accepted only after digest verification and schema-specific parsing. |
| `ProvenanceRecord` | Artifact digest, source repo/revision, build identity, signature method, signer identity | A submitted claim begins as `DECLARED`; the platform does not falsely report cryptographic verification. |
| Verified provenance | Reviewer/owner decision and verification note, optionally immutable verification evidence | `VERIFIED` is a governed human conclusion after independent validation such as `gh attestation verify` or `cosign verify-attestation`. |

The release workflow produces SBOM and provenance attestations only after OSV and Trivy gates pass. It retains the SBOM, checksum, and attestation-verification output as release evidence. No workflow secret is persisted in a provenance record.

The workflow additionally supports an opt-in keyless Sigstore signature over `SHA256SUMS`. Set the repository variable `AISDLC_COSIGN_ENABLED` to `true` only after the release-owner identity and monitoring process are established. The signed checksum manifest covers the released files listed in it; GitHub attestations provide individual artifact provenance. The bundle is uploaded as `SHA256SUMS.cosign.bundle` with the release assets.

## Verification Procedure

For GitHub build attestations, retrieve the released artifact and run:

```bash
gh attestation verify PATH/TO/ARTIFACT -R xdev-ai/ai-sdlc
```

For a CycloneDX SBOM attestation, use the appropriate CycloneDX predicate type supported by the producing workflow and save the structured output as governed verification evidence. A reviewer must then record an explicit verification decision in the platform; successful CI alone does not bypass the human approval invariant.

## References

[^cyclonedx]: [CycloneDX Maven Plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin/)
[^slsa]: [SLSA Provenance](https://slsa.dev/provenance)
[^github-attest]: [GitHub Docs: Establish provenance for builds](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations/using-artifact-attestations-to-establish-provenance-for-builds)
[^attest-wrapper]: [`actions/attest-build-provenance` repository](https://github.com/actions/attest-build-provenance)
[^sigstore]: [Sigstore Cosign signing overview](https://docs.sigstore.dev/cosign/signing/overview/)
