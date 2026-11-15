---
applyTo: "core/src/main/resources/db/migration/**,core/src/main/java/db/migration/**"
---

# Flyway migrations

Locations: SQL in `core/src/main/resources/db/migration/`, Java in `core/src/main/java/db/migration/` (package
`db.migration`). Flyway's default location `classpath:db/migration` finds both, also inside the boot jar.

Current: `V1__data_model.sql`, `V2__spring_batch_schema.sql`, `V3__knowledge_failure.sql`,
`V4__tokenizer_parts.sql`, `V5__optional_vector.java`. The next one is `V6__<what>.sql` (or `.java`).

## Rules

- **Never edit, rename or delete an applied migration.** Flyway checks checksums, and client databases exist now.
  V1 was edited once (commit 90b1f32, moving pgvector out of it) only because no client database existed yet. That
  is no longer allowed. Fix forward with a new version.
- Migrations are forward only. `deploy/INSTALL.md` section 13 tells operators to back up before an upgrade because of this.
- Migrations run on `toolkit setup` (`SetupCommand`, Flyway API) and on every server start (`spring.flyway.enabled`),
  as the app role `toolkit`, which owns the database but is **not a superuser**. No `CREATE EXTENSION` of an untrusted
  extension, no `ALTER SYSTEM`, no role changes. Setup creates `vector` as the superuser before migrating.
  Tests migrate as superuser `test`, so they will not catch this: reason about it.
- **pgvector is optional.** A SQL migration must not mention the `vector` type, `halfvec`, or `chunk.embedding`.
  Vector work goes in a Java migration that calls into `com.modtoolkit.core.VectorSupport` and does nothing when
  `VectorSupport.available(con)` is false. Anything you add for vectors must also be added to `VectorSupport.enable`,
  because `toolkit reindex` calls it to enable vectors on a database that gains pgvector after its migrations ran.
- A Java migration extends `org.flywaydb.core.api.migration.BaseJavaMigration` and uses `context.getConnection()`.
  It runs inside Flyway's transaction; use a savepoint for a statement that may fail (see `VectorSupport.enable`).
- Large data rewrites run on client estates of a million lines; prefer set-based SQL, and say in a comment what the
  statement costs.
- Lexical search depends on `cobol_tokens`, `cobol_tsvector` and `cobol_tsquery` (V1, V4; ADR 0004 and 0007).
  Changing them changes ranking: re-run `RetrievalEvalIT` and `toolkit eval` and record the numbers in `docs/evidence/M5.md`.
- `V2` is Spring Batch's schema; `spring.batch.jdbc.initialize-schema` is `never`, so Flyway owns it.

## Test a migration

- `DoctorAndMigrationIT` and `VectorSupportIT` (core) migrate fresh databases with and without pgvector.
  `NoVectorIT` (cli) runs the real jar against a database without pgvector.
- Run them: `mvnw.cmd -B verify -pl core -Dit.test=DoctorAndMigrationIT,VectorSupportIT -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
- The tests without pgvector need `bin/pg_config` in `TOOLKIT_TEST_PG_HOME` (the Windows bundle's trimmed
  `runtime\pgsql` has none; use the full EDB zip).
- Add the new tables to the data model table in `docs/PROJECT-CONTEXT.md`.
