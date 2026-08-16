#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const files = process.argv.slice(2);

if (files.length === 0) {
  console.error('Usage: node scripts/summarize-security-sarif.mjs <report.sarif> [...]');
  process.exit(2);
}

const summaries = await Promise.all(files.map(async (file) => {
  const sarif = JSON.parse(await readFile(file, 'utf8'));
  const runs = Array.isArray(sarif.runs) ? sarif.runs : [];
  const results = runs.flatMap((run) => Array.isArray(run.results) ? run.results : []);
  const levelCounts = { error: 0, warning: 0, note: 0, none: 0 };
  const rules = new Set();

  for (const result of results) {
    const level = result.level ?? 'none';
    levelCounts[level] = (levelCounts[level] ?? 0) + 1;
    if (result.ruleId) rules.add(result.ruleId);
  }

  return {
    file: path.basename(file),
    runs: runs.length,
    results: results.length,
    levels: levelCounts,
    uniqueRules: [...rules].sort(),
  };
}));

const totals = summaries.reduce((total, summary) => {
  total.runs += summary.runs;
  total.results += summary.results;
  for (const [level, count] of Object.entries(summary.levels)) {
    total.levels[level] = (total.levels[level] ?? 0) + count;
  }
  return total;
}, { runs: 0, results: 0, levels: { error: 0, warning: 0, note: 0, none: 0 } });

console.log(JSON.stringify({ summaries, totals }, null, 2));
