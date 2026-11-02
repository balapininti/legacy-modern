#!/usr/bin/env node
// Inventory-level Angular facts from the TypeScript compiler API: components, routes, services and their API calls.
// Reads files as data; it never loads or runs target code. Output: one JSON document on stdout.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const root = path.resolve(process.argv[2]);
let ts;
for (const c of [process.env.TOOLKIT_TS_MODULE, path.join(__dirname, 'node_modules/typescript')]) {
  if (!c) continue;
  try { ts = require(c); break; } catch (e) { /* try the next */ }
}
if (!ts) { console.error('typescript module not found; run npm ci in tools/ng-extract'); process.exit(3); }

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'dist' || e.name.startsWith('.')) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.ts') && !e.name.endsWith('.spec.ts') && !e.name.endsWith('.d.ts')) out.push(p);
  }
  return out;
}

const units = [];
const httpVerbs = new Set(['get', 'post', 'put', 'delete', 'patch']);

function textOf(n, sf) { return n ? n.getText(sf) : null; }
function line(sf, pos) { return sf.getLineAndCharacterOfPosition(pos).line + 1; }
function decorators(n) { return (ts.canHaveDecorators(n) && ts.getDecorators(n)) || []; }
function stringValue(n) {
  if (!n) return null;
  if (ts.isStringLiteralLike(n)) return n.text;
  if (ts.isTemplateExpression(n)) return n.head.text + n.templateSpans.map(s => '{}' + s.literal.text).join('');
  return null;
}

for (const file of walk(root, [])) {
  const src = fs.readFileSync(file, 'utf8');
  const sf = ts.createSourceFile(file, src, ts.ScriptTarget.Latest, true);
  const rel = path.relative(root, file);
  const hash = crypto.createHash('sha256').update(src).digest('hex');
  const add = (kind, name, node, attrs) => units.push({ kind, name, path: rel, line_start: line(sf, node.getStart(sf)), line_end: line(sf, node.getEnd()), content_hash: hash, attrs });

  const visit = (n) => {
    if (ts.isClassDeclaration(n) && n.name) {
      for (const d of decorators(n)) {
        if (!ts.isCallExpression(d.expression)) continue;
        const deco = textOf(d.expression.expression, sf);
        if (deco === 'Component' || deco === 'Injectable') {
          const http = [];
          const injected = [];
          const scan = (m) => {
            if (ts.isConstructorDeclaration(m)) {
              for (const p of m.parameters) if (p.type) injected.push(textOf(p.type, sf));
            }
            if (ts.isCallExpression(m) && ts.isPropertyAccessExpression(m.expression) && httpVerbs.has(m.expression.name.text)) {
              const obj = textOf(m.expression.expression, sf);
              const url = stringValue(m.arguments[0]);
              if (url && /http/i.test(obj)) http.push({ verb: m.expression.name.text.toUpperCase(), url, line: line(sf, m.getStart(sf)) });
            }
            ts.forEachChild(m, scan);
          };
          scan(n);
          const arg = d.expression.arguments[0];
          const props = {};
          if (arg && ts.isObjectLiteralExpression(arg)) {
            for (const p of arg.properties) if (ts.isPropertyAssignment(p) && p.name) props[textOf(p.name, sf)] = stringValue(p.initializer);
          }
          add(deco === 'Component' ? 'ng_component' : 'ng_service', n.name.text, n, { selector: props.selector || null, template_url: props.templateUrl || null, http_calls: http, injects: injected });
        }
      }
    }
    if (ts.isVariableDeclaration(n) && n.type && /\bRoutes?\b/.test(textOf(n.type, sf)) && n.initializer && ts.isArrayLiteralExpression(n.initializer)) {
      const route = (a) => {
        for (const el of a.elements) {
          if (!ts.isObjectLiteralExpression(el)) continue;
          let rp = null, comp = null, kids = null;
          for (const p of el.properties) {
            if (!ts.isPropertyAssignment(p)) continue;
            const k = textOf(p.name, sf);
            if (k === 'path') rp = stringValue(p.initializer);
            if (k === 'component') comp = textOf(p.initializer, sf);
            if (k === 'children' && ts.isArrayLiteralExpression(p.initializer)) kids = p.initializer;
          }
          if (rp !== null) add('ng_route', rp === '' ? '/' : rp, el, { component: comp });
          if (kids) route(kids);
        }
      };
      route(n.initializer);
    }
    ts.forEachChild(n, visit);
  };
  visit(sf);
}
process.stdout.write(JSON.stringify({ units }));
