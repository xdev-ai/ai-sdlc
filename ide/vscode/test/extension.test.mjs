import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";

test("extension manifest only invokes documented deterministic CLI and portal commands", async () => {
  const manifest = JSON.parse(await readFile(new URL("../package.json", import.meta.url)));
  assert.deepEqual(manifest.contributes.commands.map(command => command.command), ["aisdlc.validate", "aisdlc.openPortal"]);
  assert.equal(manifest.engines.vscode, "^1.95.0");
});
