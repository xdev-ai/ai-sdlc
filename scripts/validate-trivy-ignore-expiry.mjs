#!/usr/bin/env node
import { readFile } from 'node:fs/promises';

const [ignoreFile = '.trivyignore.yaml', todayArgument] = process.argv.slice(2);
const today = todayArgument ?? new Date().toISOString().slice(0, 10);

if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
  console.error(`Invalid validation date: ${today}`);
  process.exit(2);
}

const lines = (await readFile(ignoreFile, 'utf8')).split(/\r?\n/);
const entries = [];
let section = null;
let entry = null;

const finishEntry = () => {
  if (entry) entries.push(entry);
  entry = null;
};

for (let index = 0; index < lines.length; index += 1) {
  const line = lines[index];
  const lineNumber = index + 1;
  const sectionMatch = line.match(/^([a-z_]+):\s*(?:\[\])?\s*$/);
  if (sectionMatch) {
    finishEntry();
    section = sectionMatch[1];
    continue;
  }

  const idMatch = line.match(/^\s*-\s+id:\s*(.+?)\s*$/);
  if (idMatch) {
    finishEntry();
    entry = { section, line: lineNumber, id: idMatch[1].replace(/^['"]|['"]$/g, '') };
    continue;
  }

  if (!entry) continue;
  const propertyMatch = line.match(/^\s+([a-z_]+):\s*(.*?)\s*$/);
  if (propertyMatch) {
    entry[propertyMatch[1]] = propertyMatch[2].replace(/^['"]|['"]$/g, '');
  }
}
finishEntry();

const requiredStatementFields = ['rationale=', 'owner=', 'approver=', 'approved_at=', 'ticket='];
const failures = [];
const warnings = [];

for (const entry of entries) {
  const label = `${entry.section ?? 'unknown'}:${entry.id} (line ${entry.line})`;
  if (!entry.section || !['vulnerabilities', 'misconfigurations', 'secrets', 'licenses'].includes(entry.section)) {
    failures.push(`${label}: unsupported or missing ignore-file section`);
  }
  if (!entry.id || /YYYY|placeholder/i.test(entry.id)) {
    failures.push(`${label}: id must be a concrete scanner rule or advisory identifier`);
  }
  if (!entry.expired_at || !/^\d{4}-\d{2}-\d{2}$/.test(entry.expired_at)) {
    failures.push(`${label}: expired_at must use YYYY-MM-DD`);
  } else if (entry.expired_at <= today) {
    failures.push(`${label}: exception expired on ${entry.expired_at}`);
  } else {
    const expiry = Date.parse(`${entry.expired_at}T00:00:00Z`);
    const now = Date.parse(`${today}T00:00:00Z`);
    if (expiry - now <= 30 * 24 * 60 * 60 * 1000) {
      warnings.push(`${label}: exception expires within 30 days on ${entry.expired_at}`);
    }
  }
  if (!entry.statement) {
    failures.push(`${label}: statement must contain accepted-risk governance metadata`);
  } else {
    for (const field of requiredStatementFields) {
      if (!entry.statement.includes(field)) {
        failures.push(`${label}: statement is missing ${field}`);
      }
    }
  }
}

for (const warning of warnings) console.warn(`WARNING: ${warning}`);
if (failures.length > 0) {
  console.error('Trivy ignore-file validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Trivy ignore-file expiry validation passed: ${entries.length} active exception(s), validation date ${today}.`);
