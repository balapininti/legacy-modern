// Serves the production build and forwards /api and /v3 to the API, the way a real reverse proxy would.
// Usage: node serve.mjs <dist dir> <port> <api port>
import { createReadStream, statSync } from 'node:fs';
import { createServer, request } from 'node:http';
import { extname, join, normalize, sep } from 'node:path';

const [root, port, apiPort] = process.argv.slice(2);
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.ico': 'image/x-icon', '.json': 'application/json', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.map': 'application/json' };

createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/v3/')) {
    const upstream = request({ host: '127.0.0.1', port: apiPort, path: req.url, method: req.method, headers: req.headers }, (r) => {
      res.writeHead(r.statusCode, r.headers);
      r.pipe(res);
    });
    upstream.on('error', () => {
      res.writeHead(502, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { code: 'BAD_GATEWAY', message: 'the API is not answering', hint: 'check that it is running' } }));
    });
    req.pipe(upstream);
    return;
  }
  const file = normalize(join(root, decodeURIComponent(url.pathname)));
  let target = join(root, 'index.html'); // client-side routes fall back to the app shell
  if (file.startsWith(normalize(root) + sep)) {
    try {
      if (statSync(file).isFile()) target = file;
    } catch {
      // not a file: fall back
    }
  }
  res.writeHead(200, { 'content-type': TYPES[extname(target)] ?? 'application/octet-stream' });
  createReadStream(target).pipe(res);
}).listen(Number(port), '127.0.0.1');
