# Runtime AI Governance CEL Policy Samples

These files are executable `PolicyAsCodeController` request payloads. They are **examples only**: they start as `dryRunDefault: true`, must be created as `DRAFT`, must pass all fixtures, and require the existing owner-controlled activation workflow before an enforcement evaluation can use them.

| Bundle | Decision point | Minimum control enforced |
|---|---|---|
| `workload-model-allowlist.json` | Pre-flight | Authenticated tenant/project workload identity, approved provider/model, version pin, idempotency, and correlation. |
| `input-tool-containment.json` | Pre-flight | Approved data classification, no injection/secrets signal, bounded allowlisted read-only tools, and no egress. The gateway computes `all_requested_tools_allowlisted` deterministically from the signed tool inventory before CEL evaluation. |
| `output-approval-gate.json` | Post-flight | Classified non-sensitive output, digest-linked evidence, and human quorum approval for release-impacting actions. |
| `emergency-override.json` | Override | Emergency justification, future expiry, independent approval, quorum, audit evidence, and incident reference. |

Each expression produces only a Boolean decision. An absent, malformed, non-Boolean, or CEL evaluation error is a denial at the gateway integration boundary. Do not alter this sample set to allow a runtime action when evidence, an approval, a version pin, an identity binding, or a classification signal is unavailable.

The JUnit test `RuntimeAiGovernancePolicySamplesTest` loads every JSON sample and evaluates every fixture through the platform’s real `PolicyExpressionEngine`. Add a positive and a negative fixture whenever the sample policy changes.
