#!/usr/bin/env node
// Regenerates src/app/api-client from the OpenAPI document.
//   node scripts/regen-api.mjs                     regenerate from the saved ui/openapi.json
//   node scripts/regen-api.mjs http://host:port    save a fresh copy from that server first (no auth needed)
//
// Two normalizations make the saved copy stable and usable. The "servers" entry names whatever port the document was
// fetched from, and springdoc writes responses as */*, for which the generator would ask for a blob; the API's bodies are all JSON.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '..');
const saved = join(ui, 'openapi.json');
const base = process.argv[2];
const doc = base ? await (await fetch(`${base.replace(/\/$/, '')}/v3/api-docs`)).json() : JSON.parse(readFileSync(saved, 'utf8'));

delete doc.servers;
for (const path of Object.values(doc.paths)) {
  for (const op of Object.values(path)) {
    for (const res of Object.values(op.responses ?? {})) {
      if (res.content?.['*/*']) {
        res.content['application/json'] = res.content['*/*'];
        delete res.content['*/*'];
      }
    }
  }
}
writeFileSync(saved, JSON.stringify(doc, null, 2) + '\n');
execFileSync(join(ui, 'node_modules/.bin/ng-openapi-gen'), ['-c', 'ng-openapi-gen.json'], { cwd: ui, stdio: 'inherit' });
