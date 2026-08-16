import assert from "node:assert/strict";
import test from "node:test";
import { AiSdlcClient, AiSdlcApiError } from "../dist/index.js";

test("client uses the project-scoped v1 route and bearer authorization", async () => {
  let received;
  const client = new AiSdlcClient({ baseUrl: "https://control.example/", accessToken: "token", fetch: async (url, init) => { received = { url, init }; return new Response(JSON.stringify({ id: "risk", score: 10, band: "LOW", formulaVersion: "risk.v1", computedAt: "2026-08-16T00:00:00Z" }), { status: 200 }); } });
  assert.equal((await client.getLatestRiskScore("project")).score, 10);
  assert.equal(received.url, "https://control.example/api/v1/projects/project/risk-intelligence/latest");
  assert.equal(received.init.headers.Authorization, "Bearer token");
});

test("client exposes RFC 9457 compatible error status to callers", async () => {
  const client = new AiSdlcClient({ baseUrl: "https://control.example", accessToken: "token", fetch: async () => new Response(JSON.stringify({ type: "https://example.invalid/problem" }), { status: 403 }) });
  await assert.rejects(() => client.getLatestRiskScore("project"), error => error instanceof AiSdlcApiError && error.status === 403);
});
