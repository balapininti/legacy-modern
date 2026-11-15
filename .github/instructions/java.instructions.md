---
applyTo: "**/*.java"
---

# Java in this repository

Java 21 (the enforcer requires `[21,22)`), Spring Boot 3.5, Maven modules under `com.modtoolkit.<module>`. Flyway Java
migrations are the one exception to the package rule: they live in package `db.migration`
(`core/src/main/java/db/migration`).

## Style

- 4-space indent. Long lines are normal here; no formatter plugin is configured, so match the surrounding file.
- Records for data, `switch` expressions, text blocks. Constructor injection; no field `@Autowired`, no Lombok.
- Database access is `JdbcTemplate` with SQL and bind parameters. There is no JPA. JSON columns are `jsonb`.
- Javadoc and comments are plain sentences that say why, not what.
- Anything hashed into a cache key or compared across runs must be deterministic. `Map.of` and `Set.of` iterate in a
  different order in each JVM; this once made the knowledge cache miss about half the time (`KnowledgeRunner`).

## Paths

- Stored, cited and compared paths use `/` on every OS. Use `com.modtoolkit.core.PathKeys.key(root, file)` and
  `PathKeys.under(root, file, dirNames)`.
- Do not write `relativize(` in any `src/main/java` file other than `PathKeys.java`: `NoRawRelativizeTest` (cli) scans
  every module and fails the build. Test code may use it.
- Never build a path by string concatenation with `\` or `/`; use `Path.resolve`.

## Processes and Windows

- Start processes with `new ProcessBuilder(List.of(...))`, never a shell string. Close stdin when unused and drain
  output on another thread (see `LocalPostgres.run`).
- A bare name resolves to an `.exe` only. Batch shims need the extension: the Zowe CLI is `zowe.cmd` on Windows
  (`ToolkitConfig.Zos.zoweCommand`), and arguments to a `.cmd` are checked because cmd.exe interprets metacharacters
  (`ZoweCapture`).
- Executables in a PostgreSQL home: `LocalPostgres.binary(name)` adds `.exe` on Windows. The bundled JRE is found by
  `Home.java()`.
- On Windows the environment key is `Path`, and `ProcessBuilder.environment()` is case-sensitive: look the key up
  case-insensitively (see `LocalPostgres.run`).
- `Home.WINDOWS` / `LocalPostgres.WINDOWS` test `os.name`. Keep Windows-only behavior behind them.

## Secrets and output

- Secrets only from environment variables. Pass them to children in the environment (`-storepass:env`,
  `ZOWE_OPT_PASSWORD`), never on a command line. Never log them, never echo a config value that may be a pasted
  secret (`ConfigValidator`).
- stdout is command output and the MCP stdio transport. Use SLF4J; Log4j2 writes to stderr (`cli/src/main/resources/log4j2.xml`).
  `-Dtoolkit.log=err` switches to JSON logs.
- The enforcer bans `ch.qos.logback:*` and `commons-logging:commons-logging`. A new Spring or library dependency that
  pulls commons-logging needs an `<exclusion>`, as several module POMs already have.

## Database

- pgvector is optional. Before any use of `chunk.embedding` or the `vector` type, check
  `VectorSupport.available(jdbc)` and fall back to the lexical path. `VectorSupport.HOW_TO_ENABLE` is the user-facing hint.
- Schema changes are new Flyway migrations only (see `migrations.instructions.md`).
- The install's app role `toolkit` is not a superuser. Tests run as superuser `test`, so a statement needing superuser
  passes in tests and fails on a client install.

## Tests

- `*Test`: unit tests (surefire). No database, no network, no corpus.
- `*IT`: integration tests (failsafe, `mvn verify`). For a database call `TestPostgres.newDatabase()` from the core
  test-jar (`<type>test-jar</type><scope>test</scope>` on `toolkit-core`); for one without pgvector,
  `TestPostgres.newDatabaseWithoutVector()`. Each test JVM starts one throwaway PostgreSQL from
  `TOOLKIT_TEST_PG_HOME`.
- ITs that need the corpus or the jar use `assumeTrue(...)` and skip when it is missing; keep that pattern and fetch
  corpora before trusting a green run.
- CLI and MCP ITs run the real `cli/target/toolkit.jar` as a child process with `TOOLKIT_DB_*` in its environment.
- No live LLM in any test: use `RecordedLlm` and `testdata/llm-recordings`.
- Line endings: compare text with `\r\n` normalized (see `CardDemoCorpusIT.snapshotOfTheGraphIsUnchanged`); Windows
  CI checks out with `core.autocrlf=false`.
