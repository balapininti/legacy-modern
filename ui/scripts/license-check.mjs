#!/usr/bin/env node
// Fails when an npm package the UI ships or names directly is not Apache, MIT or BSD licensed (ADR 0003).
// Transitive build tooling is listed, because it does not ship, but it does not fail the check.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '..');
const OK = /^\(?(apache-2\.0|mit|mit-0|0bsd|bsd-2-clause|bsd-3-clause)\b/i;
const pkg = JSON.parse(readFileSync(join(ui, 'package.json'), 'utf8'));
const lock = JSON.parse(readFileSync(join(ui, 'package-lock.json'), 'utf8'));

function licenseOf(path) {
  const entry = lock.packages[path];
  if (entry?.license) return entry.license;
  const file = join(ui, path, 'package.json');
  return existsSync(file) ? (JSON.parse(readFileSync(file, 'utf8')).license ?? 'unknown') : 'unknown';
}

// What ships: the dependencies and everything they pull in.
const shipped = new Set();
const walk = (name) => {
  const path = `node_modules/${name}`;
  if (shipped.has(path) || !lock.packages[path]) return;
  shipped.add(path);
  Object.keys(lock.packages[path].dependencies ?? {}).forEach(walk);
};
Object.keys(pkg.dependencies).forEach(walk);
const direct = new Set([...shipped, ...Object.keys(pkg.devDependencies).map((n) => `node_modules/${n}`)]);

const bad = [...direct].filter((p) => !OK.test(licenseOf(p))).map((p) => `${p}: ${licenseOf(p)}`);
const tooling = Object.keys(lock.packages).filter((p) => p && !direct.has(p) && !lock.packages[p].optional && !OK.test(licenseOf(p)));
const byLicense = {};
for (const p of tooling) (byLicense[licenseOf(p)] ??= []).push(p.replace(/^.*node_modules\//, ''));

console.log(`${direct.size} shipped or direct packages checked, ${bad.length} violations`);
bad.forEach((b) => console.log(`  ${b}`));
console.log(`build tooling only, does not ship: ${tooling.length} packages under other licenses`);
for (const [l, names] of Object.entries(byLicense)) console.log(`  ${l}: ${names.length}`);
process.exit(bad.length ? 1 : 0);
