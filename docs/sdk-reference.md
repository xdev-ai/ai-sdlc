# SDK Reference

## Versioning

The public integration surface uses stable `/api/v1` routes and RFC 9457 problem responses. The Java and TypeScript SDKs are versioned independently using semantic versioning. A client release never silently changes its generated OpenAPI contract; upgrade the client major version when the API contract makes a breaking change.

## Java

The `sdk` Maven module uses `sdk/openapi/aisdlc-integration-v1.yaml` as its source of truth. Generate the Java client during the Maven build:

```bash
mvn -pl sdk generate-sources package
```

The generated artifact is `ai.xdev:aisdlc-java-sdk`. It targets the developer integration routes for SCM event ledgers, notification channels, approval queues, and risk intelligence. Supply a bearer token per request; do not embed a static token in source code or generated configuration.

## TypeScript

The hand-maintained TypeScript client is in `sdk/typescript` and deliberately exposes a narrow, tested subset of stable v1 routes:

```bash
cd sdk/typescript
npm install
npm run build
npm test
```

```ts
import { AiSdlcClient } from "@xdev-ai/aisdlc-sdk";

const client = new AiSdlcClient({
  baseUrl: "https://control.example",
  accessToken: process.env.AISDLC_TOKEN!
});
const score = await client.getLatestRiskScore("project-uuid");
```

Clients surface unsuccessful responses as `AiSdlcApiError`, which includes the HTTP status and parsed RFC 9457 response body. Never retry authorization, validation, or policy errors automatically; retry only transient transport failures and rate-limit responses with bounded backoff.
