---
applyTo: "scripts/**,deploy/**,.github/workflows/**"
---

# Scripts, the Windows bundle and CI

## The Windows bundle (`deploy/windows/`, `scripts/build-windows-bundle.py`)

- `toolkit.cmd`, `start.cmd`, `stop.cmd` are three lines each: run `runtime\jre\bin\java.exe -DTOOLKIT_HOME="%~dp0."
  -jar lib\toolkit.jar ...`. Keep it that way: setup, start, stop, environment loading, port checks and PostgreSQL
  control are Java (`cli/src/main/java/com/modtoolkit/cli/launch`). No PowerShell, no admin rights.
- `%~dp0.` (with the dot) is deliberate: a trailing backslash before a closing quote would escape the quote.
- `.cmd` files must have CRLF line ends. `.gitattributes` has `*.cmd text eol=crlf`, and the bundle builder dies on
  an LF `.cmd`. Do not let an editor convert them.
- `service/service.cmd` and `service/toolkit.xml` drive WinSW 2.12.0 for the optional service; only that needs an administrator.
- `config/toolkit.yaml.example` documents every YAML block. Secrets never go in it; it names variables that live in
  `config\toolkit.env`.
- `deploy/INSTALL.md` is the operator runbook and ships inside the zip (converted to CRLF). Change it whenever install
  behavior changes, and keep it the only document an installer needs.

### `build-windows-bundle.py`

- Python 3.9+, standard library only; runs on Windows, macOS and Linux.
- Inputs: `--jar cli/target/toolkit.jar`, `--ui ui/dist/ui/browser`, `--pgvector <dir>|none`, optional `--sbom
  target/bom.json` (else it runs `mvnw cyclonedx:makeAggregateBom`), `--out target`, `--cache .cache/downloads`.
- The `--pgvector` folder layout: `lib/vector.dll`, `share/extension/vector.control` and the other `vector*` files,
  and `LICENSE.pgvector`. `none` builds `toolkit-<version>-windows-x64-lexical.zip`.
- Every download is pinned by URL and SHA-256 in `PINS` (EDB PostgreSQL 17.12-1, Temurin JRE 21.0.13+9, Node 24.22.3,
  WinSW 2.12.0 and its license). A pre-filled cache with the same file names needs no internet for those; `npm ci` for
  `tools/ng-extract` still needs the npm registry. Changing a pin changes the CI cache key (it hashes this script).
- It keeps only the PostgreSQL programs in `PG_PROGRAMS` and the DLLs their PE import tables need, and fails on an
  imported DLL it cannot place or a DLL without a recorded license (`PG_DLL_LICENSES`). Adding a program to the
  bundle means adding it there. `pg_config.exe` is not shipped.
- The VC++ runtime is not copied in. The launcher puts `runtime\jre\bin` (where Temurin ships `vcruntime140.dll`,
  `vcruntime140_1.dll`, `msvcp140.dll`) on the PostgreSQL processes' PATH; the builder fails if Temurin stops shipping them.
- It writes `LICENSES/INVENTORY.md` (every exe, dll and jar with license and source) and `sbom.json`. An unknown
  license fails the build.

## Other scripts

| Script | Shell | Notes |
|---|---|---|
| `fetch-corpora.sh` | bash | Pinned commits; snapshots depend on them, so change a pin only on purpose. Git Bash on Windows (CI does this) |
| `env.sh` | bash, sourced | Puts `.tools/jdk-21*` and `.tools/apache-maven-*` on PATH when present; always returns 0 |
| `dev-pg.sh` | bash | Persistent dev PostgreSQL on 127.0.0.1:55433 (`TOOLKIT_EVAL_PG_*`, `TOOLKIT_DEV_PG_DIR`) |
| `it.sh` | bash | Runs named ITs with `mvn` (not the wrapper) |
| `license-check.py` | python | Java license gate. It starts `mvn` by bare name, which Windows Python cannot run (`mvn.cmd`); on Windows rely on `ci.yml` or see the handbook |
| `windows-smoke.py` | python, Windows only | The client's day one from a zip; `--hide-vcruntime` and the service part need an administrator |
| `validate-corpora.py`, `crosscheck-corpora.py`, `mcp-probe.py`, `evalpg.py` | python | Final validation on the 10 repositories, against `dev-pg.sh` |
| `capture-gnucobol.sh` | bash | Needs `cobc` (GnuCOBOL 3.x) on PATH |
| `build-third-party.sh` | bash | Rebuilds the vendored ProLeap and Koopa jars into `third_party/repo` |

- New scripts: Python standard library, or bash. If a developer on Windows must run it, prefer Python and call
  programs with an argument list. On Windows, `subprocess` finds `.exe` files only: `npm`, `mvn` and `npx` are
  `.cmd` shims (see `npm()` in the bundle builder).
- No Docker, ever (ADR 0008).

## CI (`.github/workflows/`)

- `ci.yml` (ubuntu-latest, every push and PR): `verify` installs PostgreSQL 17 and pgvector from PGDG, sets
  `TOOLKIT_TEST_PG_HOME=/usr/lib/postgresql/17`, fetches corpora, runs `./mvnw -B verify` and the license gate. `ui`
  builds the jar, runs `npm run licenses` and the Playwright e2e.
- `windows.yml` (windows-latest, every push, PR and manual run): `pgvector` builds v0.8.7 with `nmake` against the EDB
  zip and uploads artifact `pgvector-win-x64`; `bundle` builds the zip (artifact `toolkit-windows-x64`); `smoke` runs
  `windows-smoke.py --hide-vcruntime` from that zip; `test` runs `mvnw.cmd -B verify` against the EDB zip plus
  pgvector, checked out with `core.autocrlf=false`, and fails on any failed test.
- `PG_VERSION`/`PG_SHA256` in `windows.yml` must match `PINS["postgresql"]` in the bundle builder.
- Upload logs on failure, never `config\toolkit.env`. Keep jobs runnable by a fork with no secrets configured.
