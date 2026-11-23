# Project context: the handbook for resuming work

As of 2026-11-15, `main` at commit 9d16228. Short rules and commands are in
[.github/copilot-instructions.md](../.github/copilot-instructions.md); this file holds the rest. The operator runbook is
[deploy/INSTALL.md](../deploy/INSTALL.md) and is not repeated here.

Contents: [History](#history-and-decisions) · [Proved and not proved](#what-is-proved-and-what-is-not) ·
[Known gaps](#known-gaps) · [Owner decisions](#open-owner-decisions) · [Developing on Windows](#developing-on-windows) ·
[Configuration](#configuration-reference) · [Data model](#data-model) · [MCP contract](#mcp-contract) ·
[CLI](#cli-commands) · [Recipes](#recipes) · [Copilot coding agent](#github-copilot-coding-agent) ·
[Rough edges](#rough-edges-found-and-not-yet-fixed)

## History and decisions

| Date | What happened |
|---|---|
| 2026-11-01 | Build plan written (`docs/superpowers/plans/2026-11-01-mainframe-modernization-toolkit.md`) from the spec (`docs/spec/build-spec.md`). M0 skeleton, ADRs 0001 to 0005 |
| 2026-11-02 | Milestones M0 to M9 built, with Docker Compose and Helm packaging. Final validation on the owner's 10 repositories. ADRs 0006, 0007 |
| 2026-11-14 | The owner: no Docker; the client machine is Windows. Containers removed, Windows zip bundle designed and built (ADR 0008, `docs/superpowers/specs/2026-11-14-windows-no-docker-design.md`, `docs/superpowers/plans/2026-11-14-windows-no-docker.md`). pgvector made optional (V5) |
| 2026-11-15 | Pushed to `github.com/balapininti/legacy-modern`. `ci.yml` and `windows.yml` green on `main` at 9d16228. Pre-release `v0.1.0-windows-preview1` published with the zip |

### Architecture decision records

| ADR | Decision and why |
|---|---|
| [0001](adr/0001-vendor-proleap-and-antlr-4-13.md) | ProLeap COBOL parser (MIT) is not on Maven Central and targets ANTLR 4.7, which cannot share a JVM with our 4.13 runtime. It is rebuilt from a pinned commit with a patch and committed to `third_party/repo` |
| [0002](adr/0002-replace-cb2xml-and-jrecord.md) | cb2xml and JRecord are LGPL, so copybook byte layouts are computed in-house (`CopybookLayout`), pinned by tests and cross-checked against GnuCOBOL |
| [0003](adr/0003-license-gate-and-log4j2.md) | Log4j2 replaces Logback (EPL/LGPL). `scripts/license-check.py` fails on non-permissive dependencies unless listed in `scripts/license-exceptions.txt` |
| [0004](adr/0004-sql-tokenizer-for-cobol-identifiers.md) | PostgreSQL's text parser splits `1000-MAIN-PARA`, so `cobol_tsvector`/`cobol_tsquery` tokenize in SQL. No stemming, no C extension |
| [0005](adr/0005-hand-written-jcl-bms-ddl-parsers.md) | JCL, BMS, DDL and CSD are column and position structured, so they get small hand-written readers, not ANTLR grammars. CSD added so transactions link to programs |
| [0006](adr/0006-koopa-fallback-flagged.md) | Valid COBOL that stock ProLeap rejects: small grammar patches, else the Koopa island parser as a fallback. Koopa-parsed members are flagged `FALLBACK PARSER koopa` because facts may be missing |
| [0007](adr/0007-retrieval-ranking.md) | Retrieval ranking choices, each from reading misses: stop words, IDF, identifier parts (V4), RRF k=5, semantic weight 0.7 or 0.35. No reranker. The numbers were tuned on the same 50 questions |
| [0008](adr/0008-no-docker-windows-bundle.md) | No Docker: one Windows zip (Temurin JRE, EDB PostgreSQL 17, pgvector built by CI, Node, WinSW), one HTTPS process, `.cmd` files calling a Java launcher. Tests use native PostgreSQL. pgvector optional. Lists the license exceptions the owner must accept |

## What is proved and what is not

Full detail: [STATUS.md](STATUS.md) and [evidence/](evidence/).

| Milestone | Result | Evidence |
|---|---|---|
| M0 skeleton | Met | [M0](evidence/M0.md) |
| M1 discovery | 100% of CardDemo parsed; 1.68M synthetic lines in 80 s | [M1](evidence/M1.md) |
| M2 MCP | Half met: Claude Code proved; VS Code Copilot never tried | [M2](evidence/M2.md) |
| M3 JCL, BMS, DDL | Met on CardDemo with hand-checked edges | [M3](evidence/M3.md) |
| M3b existing modern code | Met on the sample repository | [M3b](evidence/M3b.md) |
| M4 knowledge | Met on recordings; the real local run covered the programs only | [M4](evidence/M4.md) |
| M5 retrieval | recall@5 0.84 on the tuned 50, 0.80 on first contact with 30 held-out | [M5](evidence/M5.md) |
| M6 rulebook | Half met: Claude Code followed the generated file; Copilot never read it | [M6](evidence/M6.md) |
| M7 verification | Met through GnuCOBOL captures, not a mainframe | [M7](evidence/M7.md) |
| M8 web UI | 12 Playwright tests in Chromium; real SSO not tested | [M8](evidence/M8.md) |
| M9 packaging | Measured on the Compose install that ADR 0008 removed; the Windows bundle replaces it | [M9](evidence/M9.md), [windows](evidence/windows.md) |
| Owner's 10 repositories | 2 of 328 COBOL programs fail to parse (BRICKS_TS) | [final validation](evidence/final-validation.md) |

The Windows bundle ([evidence/windows.md](evidence/windows.md)), on GitHub's `windows-latest`:

- `pgvector` 0.8.7 builds with `nmake` against EDB PostgreSQL 17.12-1 and loads.
- `bundle`: a 213.7 MB zip (454.6 MB unpacked), every download checked against its SHA-256.
- `smoke`: 20 checks pass, from unzip into a folder with a space through setup, start, discover, index, MCP over
  HTTPS with checked citations, stop, restart, WinSW service mode, and deleting the folder. CardDemo checked out with
  CRLF gives the same 19 summary fields as the LF snapshot. A machine without the VC++ Redistributable is simulated
  by hiding the System32 DLLs.
- `test`: `mvnw.cmd verify`, 248 tests, 0 failed, 1 skipped (a POSIX shell stand-in for the Zowe CLI).

Never exercised: GitHub Copilot in VS Code (the main gap for this client), a real LPAR (Zowe, FTP JES, Enterprise
COBOL), a real identity provider, hosted chat providers (Azure OpenAI, Bedrock, OpenAI, Anthropic are constructed,
not called; only local Ollama was used), a hosted embedding model, a standard non-administrator Windows user, a
machine that truly never had the VC++ runtime, `certutil -user` with a person clicking Yes, Windows 10 and Server 2019.

## Known gaps

- PL/I, REXX and Assembler are inventoried only. DSF's 1,473 PL/I programs get no facts.
- BRICKS_TS's own `.map` format and DDL inside Jinja templates (`.j2`) are not read.
- Koopa-only members may have incomplete facts (46 across the 10 repositories). They are flagged.
- The captures diff view in the UI does not exist.
- Nobody has reviewed real model output for accuracy at scale. The real knowledge run stopped at about half of its
  270 units; `knowledge run` resumes it.
- Without CSD definitions, programs started only by a transaction appear as dead-code candidates.
- Retrieval numbers depend on the embedding model; a client's model must be measured with `toolkit eval`.

## Open owner decisions

| Item | Where | Status |
|---|---|---|
| Temurin 21 JRE in the bundle: GPL-2.0 with Classpath Exception, shipped unmodified | ADR 0008 | Exception to accept |
| `libintl-9.dll` (gettext) and `libiconv-2.dll` from the EDB zip: LGPL; PostgreSQL programs import them | ADR 0008 | Exception to accept; publishing their source tarballs with each release would remove the written offer |
| `jakarta.annotation-api`: EPL-2.0 or GPL-2.0 with CE, required by Spring | ADR 0003, `scripts/license-exceptions.txt` | Exception to accept |
| 81 build-only npm packages outside the permissive list (61 ISC, 16 BlueOak-1.0.0, one each Python-2.0, CC-BY-4.0, CC-BY-3.0, CC0-1.0); they do not ship | `ui/scripts/license-check.mjs` output | To review |
| `KnowledgeOptions` adds a token budget and per-token prices the spec does not name | `knowledge/.../KnowledgeOptions.java` | To accept |
| cb2xml and JRecord (LGPL) replaced by in-house layout code | ADR 0002 | Done; owner to note |

## Developing on Windows

The client develops on Windows with no Docker and possibly no administrator rights. Everything below installs per
user by unzipping.

### Minimum tools

| Tool | Why | Notes |
|---|---|---|
| Git for Windows | Clone; Git Bash runs `scripts/fetch-corpora.sh` | Per-user install works |
| JDK 21 | Build and tests | The enforcer requires `[21,22)`. Any vendor; Temurin 21 as a zip needs no admin. Set `JAVA_HOME` |
| Maven | Use `mvnw.cmd` | Downloads Maven 3.9.16 into `%USERPROFILE%\.m2\wrapper` on first run. `mvnw.cmd` uses PowerShell internally (present on Windows) |
| Node 24 and npm | `ui`, and `tools/ng-extract` when building the bundle | The zip from nodejs.org needs no admin |
| Python 3.12 (3.9+) | `build-windows-bundle.py`, `windows-smoke.py`, evaluation scripts | Standard library only |
| PostgreSQL 17 + pgvector | Every `*IT` test | Below |

### Maven Central or a corporate mirror

`mvnw.cmd` needs `repo.maven.apache.org` twice: for Maven itself (`.mvn/wrapper/maven-wrapper.properties`) and for
dependencies. Behind a proxy or mirror:

- Maven itself: set `MVNW_REPOURL` to the mirror's base URL (with `MVNW_USERNAME`/`MVNW_PASSWORD` if it needs a login),
  or install Maven 3.9 yourself and run `mvn` instead of `mvnw.cmd`.
- Dependencies: `%USERPROFILE%\.m2\settings.xml`:

  ```xml
  <settings>
    <mirrors>
      <mirror>
        <id>corp</id>
        <!-- external:* leaves the repository's file-based "vendored" repo (third_party/repo) alone. -->
        <mirrorOf>external:*</mirrorOf>
        <url>https://artifacts.example.internal/maven2</url>
      </mirror>
    </mirrors>
  </settings>
  ```

  Do not use `<mirrorOf>*</mirrorOf>`: it also redirects the `vendored` file repository in `pom.xml`, and the build then
  cannot find the ProLeap and Koopa jars. `*,!vendored` is the equivalent explicit form. Add a `<server>` with the same
  `id` for credentials, and `<proxies>` for an HTTP proxy.

### PostgreSQL for the tests

Tests start their own throwaway server per test JVM (`TestPostgres`, through the production `LocalPostgres`). They find
PostgreSQL in `TOOLKIT_TEST_PG_HOME` (the folder holding `bin\pg_ctl.exe` and `bin\initdb.exe`), else `pg_config` on
the PATH. What they run: `initdb`, `pg_ctl`, and for the tests without pgvector (`VectorSupportIT`, `NoVectorIT`)
`pg_config`, which `TestPostgres.withoutVector` uses to mirror the installation without the `vector*` files.

**Recommended: the full EDB zip, as `windows.yml` does** (no admin; `curl` and `tar` ship with Windows 10 and later):

```bat
curl -L -o pg.zip https://get.enterprisedb.com/postgresql/postgresql-17.12-1-windows-x64-binaries.zip
certutil -hashfile pg.zip SHA256
rem expect a2cd8a774389347b70be7748ac4a37ef43cb6ee539c5fc3e85ae264b15e4a04c
mkdir C:\pgtest
tar -xf pg.zip -C C:\pgtest --exclude "pgsql/pgAdmin 4/*" --exclude "pgsql/doc/*" --exclude "pgsql/StackBuilder/*"
copy pgvector-win-x64\lib\vector.dll C:\pgtest\pgsql\lib\
copy pgvector-win-x64\share\extension\vector* C:\pgtest\pgsql\share\extension\
set "TOOLKIT_TEST_PG_HOME=C:\pgtest\pgsql"
```

pgvector publishes no Windows binaries. Get a `pgvector-win-x64` folder (layout `lib\vector.dll`,
`share\extension\vector*`, `LICENSE.pgvector`) without Visual Studio in one of two ways:

- From CI: the `pgvector` job of any `windows.yml` run uploads artifact `pgvector-win-x64`.
  `gh run download <run-id> -n pgvector-win-x64 -D pgvector-win-x64` (while the artifact is retained).
- From an extracted bundle (built against the same PostgreSQL 17.12-1):

  ```bat
  mkdir pgvector-win-x64\lib pgvector-win-x64\share\extension
  copy "C:\Tool Kit\toolkit\runtime\pgsql\lib\vector.dll" pgvector-win-x64\lib\
  copy "C:\Tool Kit\toolkit\runtime\pgsql\share\extension\vector*" pgvector-win-x64\share\extension\
  copy "C:\Tool Kit\toolkit\LICENSES\pgvector\LICENSE" pgvector-win-x64\LICENSE.pgvector
  ```

**Can `TOOLKIT_TEST_PG_HOME` point at a bundle's `toolkit\runtime\pgsql`?** Partly. It has `pg_ctl.exe`,
`initdb.exe`, `pg_trgm`, `plpgsql` and `vector`, so most ITs should work, but it has no `pg_config.exe`, so
`VectorSupportIT` and `NoVectorIT` fail with "pg_config.exe not found". Not tried end to end (unverified). Use the
EDB zip.

**The Visual C++ runtime.** The EDB programs import `VCRUNTIME140.dll` (ICU also `MSVCP140.dll`). The install's
launcher adds `runtime\jre\bin` to their PATH; `TestPostgres` does not (it never calls `LocalPostgres.libraryPath`).
So on a machine without the Visual C++ 2015-2022 Redistributable, test PostgreSQL fails with 0xC0000135. Options:
install the Redistributable (needs an administrator; any Visual Studio install also brings it), or put a folder that
holds `vcruntime140.dll`, `vcruntime140_1.dll` and `msvcp140.dll` on the PATH before `mvnw.cmd`, for example a
bundle's `runtime\jre\bin`, or a Temurin JDK's `bin` if it holds them. Child processes inherit the PATH, so this should
work; it is unverified (CI runners have the Redistributable).

Also: PostgreSQL started by an administrator drops its rights and then cannot read a folder only Administrators can
read (0xC0000135 again). Keep `C:\pgtest` and the temp folder readable by your own account.

### Build and test, cmd.exe

```bat
cd /d C:\dev\legacy-modern
set "JAVA_HOME=C:\dev\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "TOOLKIT_TEST_PG_HOME=C:\pgtest\pgsql"
mvnw.cmd -B -q -DskipTests -DskipITs package
mvnw.cmd -B verify
```

Corpora: in Git Bash, `scripts/fetch-corpora.sh` (CI runs it this way on Windows). Without Git Bash, the same as plain
git in cmd.exe:

```bat
git init .cache\corpora\carddemo
git -C .cache\corpora\carddemo remote add origin https://github.com/aws-samples/aws-mainframe-modernization-carddemo
git -C .cache\corpora\carddemo fetch --depth 1 origin 59cc6c2fd7ebd7ef7925cad552a01a4b8b6e4d5e
git -C .cache\corpora\carddemo checkout FETCH_HEAD
```

Without the corpus, `CardDemoCorpusIT`, `McpContractIT`, `ParserDifferentialIT` and others skip rather than fail.

The Windows `verify` takes about 14 minutes on the CI runner (the Linux one about 10). Run a subset with
`-pl <module> -am` and `-Dit.test=...` (see the copilot instructions). The bash helper `scripts/it.sh` has no cmd
equivalent; its one line is the same `mvn verify` call.

### Line endings

- `.gitattributes` forces `*.cmd` to CRLF and leaves everything else to `core.autocrlf`.
- The Windows `test` job checks out with `core.autocrlf=false` (LF, as on Linux), and that is the proved
  configuration for the Java suite. With `core.autocrlf=true` it is unverified; prefer
  `git config core.autocrlf false` in this clone. The snapshot test normalizes `\r\n` in its comparison
  (`CardDemoCorpusIT.snapshotOfTheGraphIsUnchanged`), and discovery of a CRLF CardDemo matches the LF snapshot
  (smoke test), but other tests may still compare files.
- The `.cache\corpora` checkouts follow your global `core.autocrlf` too.

### Bash and Python scripts on Windows

| Script | On Windows |
|---|---|
| `fetch-corpora.sh` | Git Bash (proved in CI), or the git commands above |
| `env.sh` | Not needed: set `JAVA_HOME` and PATH yourself |
| `it.sh` | Use the `mvnw.cmd verify -pl ... -Dit.test=...` line |
| `dev-pg.sh`, `ui/e2e/*.sh` | Git Bash; unverified on Windows. The UI e2e is proved on Linux CI only |
| `license-check.py` | Fails: it starts `mvn` by bare name and Windows Python cannot run `mvn.cmd`. Rely on `ci.yml`, or run the goal yourself: `mvnw.cmd -q -B -DskipTests org.codehaus.mojo:license-maven-plugin:2.5.0:aggregate-add-third-party -Dlicense.excludedScopes=test,provided -Dlicense.outputDirectory=.cache\lic -Dlicense.thirdPartyFilename=third-party.txt` and read `.cache\lic\third-party.txt` |
| `build-windows-bundle.py`, `windows-smoke.py` | Python on Windows; both proved in CI |
| `validate-corpora.py`, `mcp-probe.py`, `crosscheck-corpora.py` | Need the `dev-pg.sh` server; unverified on Windows |

### Running a development build

- Plain jar: `java -jar cli\target\toolkit.jar serve --plain-http` with `TOOLKIT_DB_URL`, `TOOLKIT_DB_USER` and
  `TOOLKIT_DB_PASSWORD` set, and `TOOLKIT_UI_DIR=ui\dist\ui\browser` for the UI. Port 8080 (`TOOLKIT_API_PORT`).
- Inside an extracted install: `stop.cmd`, copy `cli\target\toolkit.jar` over the install's `lib\toolkit.jar` (and
  `ui\dist\ui\browser` over its `ui\`), `start.cmd`. The launcher always starts the install's `lib\toolkit.jar`
  (`Home.jar()`), so running your jar with `-DTOOLKIT_HOME` alone still starts the old one.

### Easiest path: let GitHub do it

Fork or push a branch: `windows.yml` builds pgvector, builds the bundle, smoke-tests it and runs the whole suite on
`windows-latest` on every push, with no secrets needed. Download the zip from the run's `toolkit-windows-x64` artifact.
Private repositories spend Actions minutes, Windows minutes at a higher rate.

## Configuration reference

- `config\toolkit.yaml`: written by `toolkit.cmd setup`; every block with example values is in
  [deploy/windows/config/toolkit.yaml.example](../deploy/windows/config/toolkit.yaml.example) (`client`, `source`,
  `llm`, `zos`, `target`, `scope`, `ui`). Loaded by `ToolkitConfig`, checked by `ConfigValidator`, probed by `doctor`.
- `config\toolkit.env`: `NAME=value` lines, read by `EnvFile`, merged under real environment variables by
  `Launcher.environment`, handed to child processes. Written with owner-only ACLs.

Environment variables the code reads (from `System.getenv`, `Environment.getProperty` and `${...}` in
`cli/src/main/resources/application*.yml`):

| Variable | Read by | Default / meaning |
|---|---|---|
| `TOOLKIT_HOME` | `Home` (system property wins) | Install folder; set by the `.cmd` files. Unset: developer mode |
| `TOOLKIT_CONFIG` | launcher, `ToolkitMcpConfig`, `AdminController`, `-c` default in children | `<home>\config\toolkit.yaml` |
| `TOOLKIT_DB_URL`, `TOOLKIT_DB_USER`, `TOOLKIT_DB_PASSWORD` | `application.yml` | `jdbc:postgresql://localhost:5432/toolkit`, `toolkit`, `toolkit`; the launcher sets the URL from `TOOLKIT_PG_PORT` |
| `TOOLKIT_DB_POOL` | `application.yml` | Hikari pool size, 12 |
| `TOOLKIT_PG_PORT` | launcher | 55432 |
| `TOOLKIT_PG_HOME` | `Home.pgHome` | `<home>\runtime\pgsql` |
| `TOOLKIT_PG_SUPERUSER_PASSWORD` | `SetupCommand` | Setup only; never passed to children |
| `TOOLKIT_HTTPS_PORT` | `application-server.yml`, launcher | 8443 |
| `TOOLKIT_API_PORT` | `serve --plain-http` | 8080 |
| `TOOLKIT_MCP_PORT` | `application-mcp.yml` (`toolkit mcp http`) | 8081 |
| `TOOLKIT_TLS_KEYSTORE`, `TOOLKIT_TLS_PASSWORD` | `ServerTls`, launcher | PKCS12 made by setup, `<home>\data\tls\toolkit.p12` |
| `TOOLKIT_TLS_CERT`, `TOOLKIT_TLS_KEY` | `ServerTls`, launcher | PEM pair from the client's CA, instead of the keystore |
| `TOOLKIT_ADMIN_PASSWORD` | `AuthController` | Web UI `admin` password, generated by setup |
| `TOOLKIT_OIDC_ISSUER` | `application-*.yml` (`toolkit.oidc.issuer`) | Unset: no SSO |
| `TOOLKIT_OIDC_CLIENT` | `AuthController` | OIDC client id shown to the UI |
| `TOOLKIT_UI_DIR` | `application-server.yml`, `SpaFallback` | Unset: `<home>\ui` when it has `index.html` |
| `TOOLKIT_JAVA_OPTS` | launcher | Extra JVM options for the child, e.g. `-Xmx8g` |
| `TOOLKIT_NODE`, `TOOLKIT_NG_EXTRACT` | `TargetIndexer` | `node` on the PATH; bundled `tools\ng-extract\extract.js` |
| `TOOLKIT_RULES_DIR` | `PlanningTools` | Client override folder for mapping rules |
| `TOOLKIT_USER` | `ToolkitMcpConfig` | Identity of stdio MCP callers; default the OS user |
| `TOOLKIT_LAUNCHED`, `TOOLKIT_LAUNCHER_PID` | launcher, `ServeCommand` | Internal: marks a child, lets the server exit with its launcher |
| Names in `toolkit.yaml` (`llm.api_key_env`, `zos.user_env`, `zos.password_env`, `ui.oidc_client_id_env`) | `LlmFactory`/`EmbedderFactory`, `CaptureCommand`, `ConfigValidator` | For example `TOOLKIT_LLM_KEY`, `TOOLKIT_ZOS_USER`, `TOOLKIT_ZOS_PASS` |
| `COMPUTERNAME` | `SetupCommand` | Certificate host name |
| `TOOLKIT_TEST_PG_HOME` | `TestPostgres`, `dev-pg.sh`, `evalpg.py` | Development and tests only |
| `TOOLKIT_EVAL_PG_HOST/PORT/USER/PASSWORD`, `TOOLKIT_DEV_PG_DIR`, `TOOLKIT_DEV_PG_MAX_CONNECTIONS` | `dev-pg.sh`, `evalpg.py` | Dev server, port 55433 |
| `TOOLKIT_JAR` | `validate-corpora.py`, `mcp-probe.py` | `cli/target/toolkit.jar` |
| `TOOLKIT_CHECKOUT`, `TOOLKIT_CORPUS` | `ui/e2e/stack.sh` | Checkout with `.cache`, corpus folder |

System property `-Dtoolkit.log=err` switches the log format to JSON (`log4j2.xml`).

## Data model

Migrations in `core/src/main/resources/db/migration` and `core/src/main/java/db/migration`. Line numbers are 1-based
on normalized text, with the original line beside them.

| Migration | Adds |
|---|---|
| V1 data model | Extension `pg_trgm`; the tables below; functions `cobol_tokens`, `cobol_tsvector`, `cobol_tsquery`; append-only trigger function on `audit_log`; roles `admin`, `developer`, `sme-reviewer` |
| V2 | Spring Batch tables (`BATCH_*`), used by discovery and the knowledge pass |
| V3 | `knowledge_failure`; a unique identity index on `knowledge_artifact`; `graph_edge.owner_member_id` |
| V4 | `cobol_tsvector` also indexes identifier parts; re-tokenizes `chunk` (ADR 0007) |
| V5 (Java) | `vector` extension and `chunk.embedding`, only where the server offers pgvector (`VectorSupport.enable`) |

| Area | Tables (V1 unless noted) |
|---|---|
| Source and parse facts | `member`, `program`, `copybook`, `copy_use`, `paragraph`, `data_item`, `condition_value`, `dialect_feature`, `sql_stmt`, `cics_cmd`, `unresolved_call`, `missing_copybook` |
| Jobs, screens, tables | `jcl_job`, `jcl_step`, `jcl_dd`, `bms_map`, `bms_field`, `db2_table`, `db2_column` |
| Graph | `graph_node`, `graph_edge` |
| Modern code and migration | `modern_unit`, `migration_link`, `migration_status`, `migration_decision`, `rule_coverage`, `program_implementation`, `deviation` |
| Knowledge and LLM | `knowledge_artifact`, `llm_cache`, `llm_run`, `knowledge_failure` (V3) |
| Retrieval | `chunk` (`tsv`; `embedding` from V5 when pgvector is there) |
| Verification | `capture`, `fixture` |
| Security and audit | `app_user`, `role`, `user_role`, `api_token`, `audit_log` |

## MCP contract

Implemented in `mcp/src/main/java/com/modtoolkit/mcp`; frozen names in `cli/src/test/.../ContractTools.java`.

| Group (class) | Tools |
|---|---|
| Estate (`EstateTools`) | `estate_map` |
| Legacy (`LegacyTools`) | `inventory_summary`, `search`, `get_program`, `get_source`, `get_copybook`, `who_calls`, `calls_from`, `business_rules`, `table_usage` |
| Flow (`FlowTools`) | `job_flow`, `screen_flow`, `trace_field` |
| Modern (`ModernTools`) | `get_modern_impl`, `gap_report`, `deviations`, `record_deviation` |
| Planning (`PlanningTools`) | `migration_status`, `record_decision`, `explain_feature` |

`estate_map` is where an agent starts on an estate it does not know: `area` is `overview` (default), `jobs`,
`transactions`, `screens`, `programs` or `data`, and `name_prefix` narrows a large estate. It reads discovery facts
only, so it answers before the knowledge pass has run. `inventory_summary` is counts only. Where a summary or rules
are missing, `get_program` says so and tells the agent to read the code with `get_source` and cite the lines.

Prompts (`McpExtras`): `analyze_program`, `plan_migration`, `translate_program`, `write_characterization_tests`.
Resource templates: `legacy://program/{name}`, `legacy://copybook/{name}`, `legacy://job/{name}` (served through
`get_program`, `get_copybook`, `job_flow`).

Transports: Streamable HTTP at `/mcp` inside `toolkit serve` (bearer token from `toolkit token` or the Admin page;
OIDC JWTs when configured), `toolkit mcp http` (MCP only, port 8081), and `toolkit mcp stdio`. What
`McpContractIT` enforces is listed in `.github/instructions/mcp-tools.instructions.md`.

## CLI commands

`toolkit.jar` (picocli, `ToolkitMain`): `setup`, `start`, `stop` (install only), `doctor`, `init`, `discover`,
`index`, `reindex`, `status`, `knowledge run|match`, `eval`, `rulebook analyze|generate`, `capture import|submit`,
`tests generate|report`, `token`, `serve`, `mcp http|stdio`, `synth`. `toolkit --help` and `<command> --help` list
the options.

## Recipes

### Add an MCP tool

1. Add a `ToolDef` to the fitting `ToolSet` in `mcp/` (or a new `@Component` implementing `ToolSet`). Return items
   with `member` and `line`; throw `ToolError` for expected failures. Do not page, check roles or audit in the handler.
2. Add the name to `ContractTools.EXPECTED` and assertions on real CardDemo data to `McpContractIT` for both
   transports.
3. Change the count 20 in `ServerProfileIT`, `LauncherIT` (3 places) and `scripts/windows-smoke.py` (5 places).
4. Add the name to `RulebookLint.TOOLS`, and to the rulebook templates if agents should call it.
5. Update `README.md`, this file, and `docs/STATUS.md`. Run `mvnw.cmd -B verify -pl cli -am -Dit.test=McpContractIT,ServerProfileIT,LauncherIT -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`.

### Add a Flyway migration

1. Next free version: `V6__short_name.sql` in `core/src/main/resources/db/migration`, or `V6__short_name.java` in
   `core/src/main/java/db/migration` (package `db.migration`, extends `BaseJavaMigration`). Never edit V1 to V5.
2. No superuser statements: client installs migrate as the non-superuser `toolkit` role.
3. pgvector is optional: nothing in SQL may mention `vector` or `chunk.embedding`. Vector work goes in a Java
   migration guarded by `VectorSupport.available(con)`, and the same change into `VectorSupport.enable` (used by
   `toolkit reindex` when pgvector arrives later). V5 is the pattern.
4. Run `DoctorAndMigrationIT`, `VectorSupportIT` (core) and `NoVectorIT` (cli). Update the data model table above.

### Add a REST endpoint and regenerate the UI client

1. A method on a controller in `api/src/main/java/com/modtoolkit/api` (`@RestController`, `@RequestMapping("/api/...")`,
   `@ConditionalOnWebApplication`), guarded by `@PreAuthorize("hasAnyRole(...)")`. Errors map through `ApiErrors` to
   `{error: {code, message, hint}}`. Anything under `/api/**` needs authentication (`SecurityConfig`).
2. Test it in `ApiIT` (cli) or `McpContractIT.h_...` style, against the real jar.
3. `mvnw.cmd -B -q -DskipTests -DskipITs package`, start `java -jar cli\target\toolkit.jar serve --plain-http`
   against a migrated database, then in `ui\`: `npm run regen-api -- http://localhost:8080`. This rewrites
   `ui/openapi.json` and `ui/src/app/api-client` (ng-openapi-gen, `ui/ng-openapi-gen.json`). On Windows, if the
   script fails at the generator step, run `npx ng-openapi-gen -c ng-openapi-gen.json` (see rough edges).
4. Add the response interface and a method to `ui/src/app/core/api.ts`; use it from a page.
5. Commit `openapi.json` and the generated client; the e2e test compares the saved operations with the live server.

### Add a parser or a language

1. Member types are `parsers/.../classify/MemberType`; `parsed()` says which are parsed rather than only inventoried.
   Classification is by content only, never by file name (`Classifier`); add a signature and cases to
   `NormalizeClassifyTest`, including near misses (other languages share PL/I's comments, for example).
2. A reader in `parsers/src/main/java/com/modtoolkit/parsers/<language>/`. Hand-written for line and column structured
   languages (ADR 0005). Every fact keeps its line. One test fixture per hard case (`StructuredParsersTest` pattern).
3. Dispatch in `discovery/.../MemberProcessor` (`switch` on the type), writes through `FactWriter`/`StructureWriter`,
   links in `GraphLinker`. New tables need a migration.
4. Searchable? Chunking in `retrieval/.../ChunkIndexer`. Exposed? A tool or a field in an existing tool.
5. Counts in the inventory summary change the CardDemo snapshot: rerun `CardDemoCorpusIT` with
   `-Dsnapshot.update=true` (read as a system property by the test; that failsafe passes it through is unverified),
   review the diff of `testdata/snapshots/carddemo-summary.json`, and commit it. `windows-smoke.py` compares against it too.
6. A new dependency (a grammar runtime, a library) goes through the license gate. Run the 10 repositories
   (`scripts/fetch-corpora.sh --all`, `scripts/validate-corpora.py`) and record results in `docs/evidence`.

### A test that needs PostgreSQL

1. Name it `*IT`, in a module whose POM has the core test-jar:
   `<dependency><groupId>com.modtoolkit</groupId><artifactId>toolkit-core</artifactId><version>${project.version}</version><type>test-jar</type><scope>test</scope></dependency>`
   (discovery, mcp and cli already do).
2. `TestPostgres.Database pg = TestPostgres.newDatabase();` gives a fresh empty database on the JVM's server
   (superuser `test`); migrate it with Flyway (`Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(),
   pg.getPassword()).load().migrate()`) or let the jar do it. `TestPostgres.newDatabaseWithoutVector()` for a server
   without pgvector.
3. Set `TOOLKIT_TEST_PG_HOME`. The server is deleted at JVM exit; orphans from killed JVMs are swept on the next start.

### Record an LLM response for tests

Recordings are JSON files in `testdata/llm-recordings`, one per unit key (`rule_CBACT04C.json`, retries as
`*.retry.json`), holding `model`, `tokens_in`, `tokens_out` and `text`. The prompt is never saved.

1. A `toolkit.yaml` with an `llm` block for a model you are allowed to send this source to (a local Ollama for
   public corpora such as CardDemo). Never record client source into this repository.
2. `java -jar cli\target\toolkit.jar knowledge run -c toolkit.yaml --programs CBACT04C --record-to testdata\llm-recordings`
   (`RecordedLlm.recording` wraps the real client). `knowledge match` takes the same options.
3. Replay: `--replay-from testdata/llm-recordings` answers from the files and sends nothing; a unit with no file fails
   (exit 3). `RecordedKnowledgeIT` and `ui/e2e/stack.sh` replay this way.
4. Prompt files are versioned (`Prompts.VERSIONS`). Recordings are keyed by unit, not prompt version, so after a
   prompt change re-record the affected units, or the tests replay answers to the old prompt.

### Rebuild the Windows bundle

On Windows (cmd.exe), with JDK 21, Node 24, Python 3.9+ and internet (or a pre-filled `.cache\downloads`):

```bat
mvnw.cmd -B -q -DskipTests -DskipITs package
mvnw.cmd -B -q org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom
cd ui && npm ci && npm run build && cd ..
python scripts\build-windows-bundle.py --jar cli\target\toolkit.jar --ui ui\dist\ui\browser --pgvector pgvector-win-x64 --sbom target\bom.json --out target
```

- Output: `target\toolkit-0.1.0-SNAPSHOT-windows-x64.zip` and `.sha256` (`--pgvector none`: `...-lexical.zip`).
- Downloads, each checked against `PINS`: EDB PostgreSQL 17.12-1 zip (get.enterprisedb.com), Temurin JRE 21.0.13+9
  (github.com), Node 24.22.3 (nodejs.org), WinSW 2.12.0 and its license (github.com). Pre-fill
  `.cache\downloads` with those files under the names in `PINS` to build offline. `npm ci --omit=dev` for
  `tools/ng-extract` still needs the npm registry; the SBOM step needs Maven Central (or pass `--sbom`).
- `--pgvector`: the folder from the CI artifact or copied out of an existing bundle, as described above. No Visual
  Studio needed unless you build pgvector yourself (`windows.yml` job `pgvector` shows how).
- Prove it: `python scripts\windows-smoke.py --zip target\toolkit-0.1.0-SNAPSHOT-windows-x64.zip --artifacts smoke-logs --skip-service`
  (the service part and `--hide-vcruntime` need an administrator). Or push and let `windows.yml` do all of this.
- Builds are not byte-identical: the zip from the `main` run of 9d16228 and the released one from the
  `win/ci-windows` run of the same commit have different SHA-256 values.

### Release a new zip

No release workflow exists; preview 1 was published by hand. What it took, as far as the repository shows:

1. Decide the version. `pom.xml` (all module POMs carry the parent version), `ToolkitMain` (`version = "toolkit 0.1.0"`)
   and `application*.yml` (`spring.ai.mcp.server.version`) hold it; the zip name comes from `pom.xml`. Preview 1 kept
   `0.1.0-SNAPSHOT`.
2. Update `docs/STATUS.md`, the evidence files and `deploy/INSTALL.md`.
3. Tag the commit and push the tag. `windows.yml` and `ci.yml` run on tag pushes too; wait for both to pass.
4. `gh run download <windows run id> -n toolkit-windows-x64`, check the `.sha256`, then
   `gh release create <tag> --prerelease --title "..." --notes-file notes.md toolkit-*-windows-x64.zip toolkit-*-windows-x64.zip.sha256`.
   Preview 1: tag `v0.1.0-windows-preview1` on 9d16228, zip from run 43688242597, 213.7 MB, SHA-256 `67fe339f...ac4bf`.
5. Consider attaching the gettext 0.19.8 and libiconv 1.15 source tarballs (ADR 0008, LGPL).

The exact commands used for preview 1 are not recorded in the repository; steps 3 and 4 are reconstructed from the
release and the CI runs.

## GitHub Copilot coding agent

The coding agent works in a GitHub Actions Linux environment and reads `.github/copilot-instructions.md`,
`.github/instructions/` and `AGENTS.md`. This repository has no `.github/workflows/copilot-setup-steps.yml`, so the
agent starts without PostgreSQL, pgvector or the corpora, and integration tests will skip or fail there. To give it
the same environment as `ci.yml`, copy that job's PGDG install, `TOOLKIT_TEST_PG_HOME=/usr/lib/postgresql/17` and
`scripts/fetch-corpora.sh` into a `copilot-setup-steps` job, and allow github.com, Maven Central and the PGDG apt
repository through the agent's firewall. Not set up or tried (unverified). Its pull requests still run `ci.yml` and
`windows.yml`, which are the real check.

## Rough edges found and not yet fixed

- `ui/scripts/regen-api.mjs` runs `node_modules/.bin/ng-openapi-gen` with `execFileSync`; on Windows that file is a
  shell script beside an `.cmd` shim, so the call is expected to fail there (unverified). Workaround above.
- `scripts/license-check.py` and `scripts/it.sh` call `mvn`, not the wrapper; `license-check.py` cannot start `mvn.cmd` on Windows.
- `TestPostgres` does not put a VC++ runtime folder on the PATH of the test PostgreSQL, unlike the launcher.
- `ui.oidc_client_id_env` in `toolkit.yaml` is validated, but `AuthController` always reads `TOOLKIT_OIDC_CLIENT`; the
  server's issuer comes from `TOOLKIT_OIDC_ISSUER`, while `ui.oidc_issuer` is used only by `doctor`.
- `testdata/captures/CBACT04C/manifest.json` and `docs/evidence/M7.md` say the GnuCOBOL capture ran in Docker.
  That was true of that capture; `scripts/capture-gnucobol.sh` now needs `cobc` on the PATH instead.
- `docs/spec/build-spec.md` still describes Compose, Helm and Testcontainers; ADR 0008 lists the superseded lines.
