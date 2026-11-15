# Windows Install Without Docker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the toolkit as one Windows zip that installs and runs with no Docker and no admin rights, and remove Docker from the repository.

**Architecture:** A Java `Launcher` (behind `.cmd` one-liners) owns a portable PostgreSQL 17 with pgvector and starts one Spring process (`server` profile) that serves the UI, REST API and MCP on one HTTPS port. Tests start a throwaway native Postgres through the same `LocalPostgres` class instead of Testcontainers.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI MCP, Flyway, picocli, JGit, Jimfs (test), PostgreSQL 17 + pgvector 0.8, Temurin JRE, WinSW 2.12, GitHub Actions `windows-latest`.

**Spec:** `docs/superpowers/specs/2026-11-14-windows-no-docker-design.md`

## Global Constraints

- Shipped dependencies Apache, MIT, BSD, EDL or PostgreSQL License; anything else gets an ADR exception (Temurin: GPLv2 + Classpath Exception).
- Secrets reach the application only as environment variables. Nothing is fetched at install or runtime.
- No live LLM in any test.
- Stored member and file paths use `/` on every OS.
- No Docker, Compose, Helm, Jib or Testcontainers anywhere after Task 8.
- Bundle downloads pinned by URL and SHA-256.
- Commits: `git -c user.name="Bala" -c user.email="balakrishna.pininti@gmail.com" commit`, message ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Install path containing spaces (`C:\Users\x\Tool Kit\`): every `.cmd` and child process must quote it. Pinned by the Windows smoke test (Task 10).
2. Source checked out with CRLF line endings: discovery results must match the LF checkout. Pinned by comparing the Windows CardDemo summary with `testdata/snapshots/carddemo-summary.json` (Task 10).
3. Postgres left running after `stop` or a crash: `start` must detect a stale `postmaster.pid` and recover. Test in Task 6.
4. Port 8443 or 55432 already in use: `start` fails with a message naming the port. Test in Task 6.
5. Extension `vector` missing: migrations succeed, search is lexical-only, doctor warns. Test in Task 3.

---

### Task 1: Paths stored with `/`

**Files:** Create `core/src/main/java/com/modtoolkit/core/PathKeys.java`; modify the 7 `relativize(...)` call sites in `discovery/InventoryScanner`, `discovery/modern/TargetIndexer` (2), `rulebook/ConventionAnalyzer` (2), `rulebook/RulebookLint` (2); test `core/src/test/java/com/modtoolkit/core/PathKeysTest.java`, `cli/src/test/java/com/modtoolkit/cli/NoRawRelativizeTest.java`.

**Interfaces:** Produces `static String PathKeys.key(Path root, Path file)` (name elements joined with `/`) and `static boolean PathKeys.under(Path root, Path file, Set<String> dirNames)`.

- [ ] Test: on `Jimfs.newFileSystem(Configuration.windows())`, `key(C:\src, C:\src\app\cbl\X.cbl)` is `app/cbl/X.cbl`; on Unix config the same. Include/exclude globs from `DiscoverOptions` (`**/*`, `**/.git/**`, `**/*.lst`) match a Windows relative path.
- [ ] Test: `NoRawRelativizeTest` scans `*/src/main/java` and fails on `relativize(` outside `PathKeys.java`.
- [ ] Implement, replace call sites, run `./mvnw -q -pl core,discovery,rulebook,cli -am test`. Commit.

### Task 2: No external git; configurable node and zowe

**Files:** `discovery/modern/LinkCandidates.java` (JGit), `discovery/pom.xml` (add `org.eclipse.jgit:org.eclipse.jgit`), `discovery/modern/TargetIndexer.java`, `verify/ZoweCapture.java` caller in `cli/CaptureCommand.java`, `core/config/ToolkitConfig.java` (`Zos.command`), `scripts/license-check.py` if EDL needs adding.

**Interfaces:** `TargetIndexer` uses `System.getenv("TOOLKIT_NODE")` else `node`. Zowe command = `zos.command` else `zowe.cmd` on Windows (`os.name` starts with `Windows`) else `zowe`.

- [ ] Test: existing commit-reference test (target-sample fixture git repo) passes with JGit; a new unit test for the zowe default on a faked `os.name`.
- [ ] Implement; license gate passes. Commit.

### Task 3: pgvector optional

**Files:** `core/.../db/migration/V1__data_model.sql` (drop `CREATE EXTENSION vector` and `embedding vector` column), create `core/src/main/java/db/migration/V5__optional_vector.java` (Flyway `BaseJavaMigration`), create `core/.../VectorSupport.java`, modify `retrieval/EmbeddingIndexer`, `retrieval/HybridSearch`, `cli/StatusCommand`, `cli/ReindexCommand`, `core/config/Doctor`.

**Interfaces:** `VectorSupport.available(JdbcTemplate)` is true when column `chunk.embedding` exists. V5 runs `CREATE EXTENSION IF NOT EXISTS vector` and `ALTER TABLE chunk ADD COLUMN embedding vector` only when `pg_available_extensions` lists `vector`. If `vector` becomes installable later, `toolkit reindex` adds the column (same SQL as V5).

- [ ] Test (needs Task 4's `TestPostgres`; order Task 4 first if implementing sequentially): migrate a database where `vector` is unavailable (simulate with a Postgres whose extension dir lacks it — the test extension can start one from a copy without `vector*`; if that is impractical, unit-test V5 against a mocked `pg_available_extensions` result) → migration succeeds, lexical search answers, `Doctor` returns WARN `vector`.
- [ ] Existing retrieval ITs still pass with vector present. Commit.

### Task 4: Native Postgres in place of Testcontainers

**Files:** Create `core/src/main/java/com/modtoolkit/core/local/LocalPostgres.java`; create `core/src/test/java/com/modtoolkit/core/testdb/TestPostgres.java` and publish core's test-jar; replace the `@Container PostgreSQLContainer` field in the 9 ITs (`DoctorAndMigrationIT`, `DiscoverJobIT`, `CardDemoCorpusIT`, `KnowledgePassIT`, `VerificationIT`, `RetrievalEvalIT`, `McpContractIT`, `RecordedKnowledgeIT`, `ApiIT`); remove Testcontainers from every pom.

**Interfaces:**
- `LocalPostgres(Path pgHome, Path dataDir, int port)`: `boolean initialized()`, `void init(String superuser, String password)` (initdb, scram, listen 127.0.0.1 only), `void start(Duration timeout)` (recovers a stale `postmaster.pid`, fails naming the port if it is taken), `void stop()`, `boolean running()`, `void createDatabase(String name)`, `String jdbcUrl(String db)`. Binaries resolved as `pgHome/bin/<name>[.exe]`.
- `TestPostgres.get()` returns one server per JVM (temp data dir, free port, stopped by shutdown hook) and `TestPostgres.newDatabase()` returns an object with `getJdbcUrl()`, `getUsername()`, `getPassword()`. `pgHome` from `TOOLKIT_TEST_PG_HOME`, else `pg_config --bindir`'s parent.

- [ ] Test: `LocalPostgresIT` init, start, stale-pid recovery, port-taken message, stop leaves no process.
- [ ] Swap the 9 ITs; `./mvnw verify` with Docker stopped passes all tests. Commit.

### Task 5: One HTTPS server process

**Files:** Create `cli/src/main/resources/application-server.yml`, `core/.../security/SpaFallback` (controller or filter), `core/.../security/BodyLimitFilter`; modify `SecurityConfig` (permit static and SPA routes, headers), `ToolkitApp` (`server()`), `ServeCommand` (runs `server`; `mcp http` stays for stdio-less use but is no longer used by installs).

**Interfaces:** Properties `toolkit.ui.dir` (filesystem folder of the Angular build), `TOOLKIT_HTTPS_PORT` (default 8443), `TOOLKIT_TLS_KEYSTORE`, `TOOLKIT_TLS_PASSWORD`, optional PEM `TOOLKIT_TLS_CERT`/`TOOLKIT_TLS_KEY`.

- [ ] Test `ServerProfileIT` (random port, self-signed keystore made in the test): `/` and `/review` return `index.html` without a token; `/api/auth/me` without token 401 JSON; MCP initialize + `tools/list` returns 19 tools with a token, 401 without; `/actuator/health` 200; CSP, HSTS, nosniff present; 3 MB POST rejected; admin row created once.
- [ ] Existing `ApiIT`, `McpContractIT` pass. Commit.

### Task 6: Launcher and `.cmd` entry points

**Files:** Create `cli/src/main/java/com/modtoolkit/cli/launch/{Launcher,EnvFile,Home}.java`, `SetupCommand`, `StartCommand`, `StopCommand`; `deploy/windows/{toolkit.cmd,start.cmd,stop.cmd}`; register commands in `ToolkitMain`.

**Interfaces:**
- `Home`: the install root from `TOOLKIT_HOME` (set by the `.cmd` files to `%~dp0`). Layout per the spec.
- `toolkit setup --source <dir> [--client name] [--port 8443]`: writes `config\toolkit.yaml`, `config\toolkit.env` (generated DB and admin passwords; ACL to current user on Windows via `AclFileAttributeView`, `600` elsewhere), keystore `data\tls\toolkit.p12` via `keytool`, initdb `data\pg`, creates role and database.
- `toolkit start [--foreground]`: starts Postgres, then a child JVM `serve` with env from `toolkit.env` merged under real env, logs to `data\logs`, pid in `data\toolkit.pid`, waits for `/actuator/health`. Prints the URL.
- `toolkit stop`: stops the server JVM, then Postgres.
- Any other command: ensures Postgres is running, runs the command in a child JVM with the env file applied, returns its exit code.
- `.cmd`: `@"%~dp0runtime\jre\bin\java.exe" -DTOOLKIT_HOME="%~dp0." -jar "%~dp0lib\toolkit.jar" %*` (start/stop pass `start`/`stop`).

- [ ] Test `LauncherIT` on the Mac with Homebrew Postgres as runtime: setup → start → health 200 → `doctor` via launcher passes database and source → stop → no process on either port → start again keeps data; port-taken message; stale pid recovery.
- [ ] Commit.

### Task 7: Windows bundle, docs and ADR

**Files:** Create `scripts/build-windows-bundle.py` (downloads pinned Temurin JRE, EDB PostgreSQL 17 zip, Node 24 zip, WinSW 2.12.0 with SHA-256; takes `--pgvector DIR` and `--ui DIR`; trims pgAdmin/StackBuilder/docs/symbols; writes `LICENSES/` inventory listing every DLL and its license source; writes SBOM), `deploy/windows/service/toolkit.xml`, rewrite `deploy/INSTALL.md` for Windows, ADR `docs/adr/0008-windows-bundle-no-docker.md`.

- [ ] Run the script on the Mac with a pgvector DLL from Task 10's CI artifact (until then `--pgvector none` makes a lexical-only bundle); unzip and check layout. Commit.

### Task 8: Remove Docker from the repository

**Files:** Delete `deploy/compose`, `deploy/helm`, `deploy/base`, `deploy/ui`, `scripts/build-images.sh`, `scripts/compose-smoke.sh`, `scripts/build-airgap-bundle.sh`, `.dockerignore`, Jib profile in `cli/pom.xml`; change `scripts/env.sh` (no Docker), `ui/e2e/stack.sh` (native Postgres through `TOOLKIT_TEST_PG_HOME`), `scripts/validate-corpora.py`, `crosscheck-corpora.py`, `mcp-probe.py` (psql/JDBC on a native server, not `docker exec`); update `README.md`, `docs/STATUS.md`.

- [ ] `grep -ri docker` over tracked files returns only history notes in docs/evidence and the ADR. `./mvnw verify` and `npm run e2e` pass with Docker stopped. Commit.

### Task 9: Service mode

**Files:** `deploy/windows/service/toolkit.xml` (WinSW: `executable` = bundled java, arguments `start --foreground`, stop via `toolkit stop`, log rotation), INSTALL.md section (admin needed; install, start, stop, uninstall).

- [ ] Covered by the Windows smoke test. Commit.

### Task 10: Windows CI

**Files:** `.github/workflows/ci.yml`: Linux job runs `./mvnw verify` with PGDG `postgresql-17` + `postgresql-17-pgvector` and `TOOLKIT_TEST_PG_HOME=/usr/lib/postgresql/17`; UI job uses the same; new `windows` job: download pinned EDB 17 zip, build pgvector with `nmake /F Makefile.win` in the VS developer shell, upload `pgvector-win-x64` artifact, build the jar and UI, run `build-windows-bundle.py`, unzip into a path with a space, run `scripts/windows-smoke.py`, upload the bundle as an artifact.

**`scripts/windows-smoke.py`:** setup with CardDemo checked out `core.autocrlf=true`; start; doctor; discover; index; assert no stored path contains `\`; compare summary with `testdata/snapshots/carddemo-summary.json`; `vector` extension present; token; MCP initialize, 19 tools, `who_calls` with citations, 401 without token; UI `/` and `/review`; stop leaves no `java.exe`/`postgres.exe` from the bundle; restart keeps data; WinSW install, start, health, stop, uninstall; delete the folder.

- [ ] Push to `balapininti/legacy-modern` (public, owner-approved) and iterate until all jobs pass. Record the run in `docs/evidence/windows.md`.
