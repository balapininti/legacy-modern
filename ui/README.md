# Toolkit web UI

Angular standalone components, Angular Material and Cytoscape.js, for operators and SMEs. See Module 7 of `docs/spec/build-spec.md`.

| Command | What it does |
| --- | --- |
| `npm start` | Dev server on port 4200, proxying `/api` to `localhost:8080` (`proxy.conf.json`) |
| `npm run build` | Production build into `dist/ui/browser` |
| `npm run regen-api [-- http://host:port]` | Regenerates `src/app/api-client` from `openapi.json`, saving a fresh copy from a running server first when given one |
| `npm run licenses` | Fails on a shipped or direct npm dependency that is not Apache, MIT or BSD |
| `npm run e2e` | The M8 proof: build, seed a throwaway stack (`e2e/stack.sh`), run `e2e/m8.spec.ts` in headless Chromium, stop the stack |

From a git worktree without `.tools` and `.cache`, name the checkout that has them: `TOOLKIT_CHECKOUT=/path/to/checkout npm run e2e`. The jar must be built first (`mvn -B -q -DskipTests -DskipITs package`) and Chromium installed once (`npx playwright install chromium`). The e2e screenshots land in `e2e/screenshots`.
