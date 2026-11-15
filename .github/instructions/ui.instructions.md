---
applyTo: "ui/**"
---

# Angular web UI

Angular 21, standalone components, Angular Material, Cytoscape.js. Node 24 and npm (`packageManager: npm@11.11.0`).
See `ui/README.md` for the commands.

## Layout

- `src/app/pages/*.page.ts`: one routed page per file, lazy-loaded from `src/app/app.routes.ts`. Role-gated routes use
  `requireRole(...)` from `core/http.ts`; the roles are `admin`, `developer`, `sme-reviewer`.
- `src/app/core/`: auth (`auth.service.ts`, `oidc.service.ts`), HTTP setup (`http.ts`), notifications, `Remote`
  (loading/error state) with `StateComponent`, and `api.ts`.
- `src/app/api-client/`: **generated** by ng-openapi-gen from `ui/openapi.json`. Never edit by hand; it is rewritten
  with `removeStaleFiles`. The generated services type bodies loosely because the controllers return maps, so
  `core/api.ts` wraps them in an `Api` service and declares the real shapes as TypeScript interfaces. Update those
  interfaces when a controller's result changes.

## Conventions

- Components: `ChangeDetectionStrategy.OnPush`, signals (`signal`, `computed`), `inject()`, inline `template` and
  `styles`, built-in control flow (`@for`, `@if`).
- Elements the e2e test reads carry `data-testid` or data attributes (`data-unit`, `data-rank`, `data-status`). Keep
  them when you restyle.
- Prettier: `printWidth` 100, single quotes (`.prettierrc`).
- No external calls from the browser except the toolkit's own origin and the configured OIDC issuer (the server's
  Content-Security-Policy allows only those; `SecurityConfig`).

## REST changes

1. Change the controller in `api/`.
2. Run a server with plain HTTP against a migrated database:
   `java -jar cli/target/toolkit.jar serve --plain-http` with `TOOLKIT_DB_URL`, `TOOLKIT_DB_USER`, `TOOLKIT_DB_PASSWORD`
   set (port `TOOLKIT_API_PORT`, default 8080).
3. `npm run regen-api -- http://localhost:8080` saves `/v3/api-docs` to `ui/openapi.json` (normalized: no `servers`,
   `*/*` bodies become JSON) and regenerates `src/app/api-client`. Without a URL it regenerates from the saved file.
   On Windows the script's call to `node_modules/.bin/ng-openapi-gen` is expected to fail (no `.cmd` extension;
   unverified); then run `npx ng-openapi-gen -c ng-openapi-gen.json` after it has written `openapi.json`.
4. Commit `openapi.json` and the generated client. The e2e test "the saved OpenAPI copy matches what the server
   publishes" fails when the operation list differs.

## Build, check, prove

- `npm ci`, `npm run build` (output `dist/ui/browser`, which the bundle ships as `ui\` and the server serves through
  `SpaFallback` from `TOOLKIT_UI_DIR` or `<TOOLKIT_HOME>/ui`).
- `npm start` serves on 4200 and proxies `/api` and `/v3` to `localhost:8080` (`proxy.conf.json`).
- `npm run licenses`: a shipped or direct dependency must be Apache, MIT or BSD (`scripts/license-check.mjs`).
  Build-only tooling is listed, not failed (81 packages, mostly ISC and BlueOak, are an open owner decision).
- `npm run e2e` (`e2e/run.sh`, bash): builds, starts a seeded stack (`e2e/stack.sh`: native PostgreSQL through
  `scripts/dev-pg.sh`, the real jar, replayed LLM recordings), runs the 12 Playwright tests in `e2e/m8.spec.ts`, stops
  the stack. Needs the jar built, corpora fetched, `npx playwright install chromium`, and `TOOLKIT_TEST_PG_HOME`.
  It is proved on Linux CI only; on Windows it needs Git Bash and is unverified there.
