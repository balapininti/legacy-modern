# Windows install without Docker: design

Date: 2026-11-14. Status: decided under the owner's standing rule (no questions; an expert agent's recommendation is used). The owner's words: "for the legacy-modern, i dont want any docker, in the client machine there is no docker and its windows based machine. also give instructions on how to start the repos".

## Intent

The client machine is Windows, has no Docker, and may give the installer no admin rights. The toolkit must install from one zip, start with one command, and reach a first cited MCP answer the same day, with nothing fetched at install time.

## Goals

- One self-contained `toolkit-<version>-windows-x64.zip`: Java runtime, PostgreSQL 17 with pgvector, the toolkit jar with the UI inside, Node for the Angular helper, and a service wrapper.
- No admin rights for install, start and stop. Service mode is optional and needs admin.
- One Java process serves the UI, REST API and MCP over HTTPS on one port (default 8443).
- `.cmd` entry points: `toolkit.cmd` (any CLI command), `start.cmd`, `stop.cmd`. Logic lives in Java, not in scripts.
- Paths stored and cited with `/` on every OS.
- Docker removed from the repository: Compose, Helm, Dockerfiles, Jib, nginx image, Testcontainers, and the scripts that use them.
- Proved on a real Windows runner (`windows-latest` on GitHub Actions, public repo `balapininti/legacy-modern`, authorized by the owner on 2026-11-14).

## Non-goals

- Linux or macOS client installs. The jar still runs there with a native Postgres, and developers use that, but no bundle is shipped for them.
- Kubernetes. Helm needs images, and images need Docker. This supersedes spec lines 430-445 and 496; a new ADR records it.
- Bundling Ollama, git or the Zowe CLI. Clients install those if they use them.

## Anti-goals

- Anything that needs PowerShell execution-policy changes or admin rights for a first query.
- Shipping third-party binaries we cannot trace to an official source and a checksum.
- Two install paths to maintain.

## Decisions

| Area | Decision | Why |
|---|---|---|
| Database | Portable PostgreSQL 17 (EDB Windows x64 zip binaries) inside the bundle. The launcher runs `initdb` into `data\pg`, starts it with `pg_ctl` on `127.0.0.1:55432`, scram auth, generated password | No admin, no install, supported to 2029 |
| pgvector | Built by us from the pgvector 0.8.x source with `nmake /F Makefile.win` against that EDB zip on `windows-latest`, published as a CI artifact the bundle script consumes | No official Windows binary exists; third-party ones are untraceable |
| pgvector optional | V1 no longer creates `vector` or `chunk.embedding`. A Java migration creates them when the extension is available. Without it, search is lexical-only and doctor warns | A first query never needs vectors; also covers clients with their own Postgres. No client database exists yet, so changing V1 is safe |
| Web server | A `server` profile: REST API, MCP and the Angular build in one Spring context on one HTTPS port. Spring serves static files with an `index.html` fallback, sets CSP, HSTS and nosniff headers, limits bodies to 2 MB, keeps MCP streams open for an hour | No controller is profile-bound and security is already one policy; removes nginx and the double admin-bootstrap race |
| TLS | `toolkit setup` makes a self-signed PKCS12 keystore with the JRE's `keytool`. A client CA certificate is configured as a PEM bundle | Built into Spring Boot and the JRE |
| Secrets | `config\toolkit.env`, written by `setup`, readable only by the installing user (ACL). The launcher reads it and passes values to child processes as environment variables; real environment variables win | Application code still reads secrets from the environment only |
| Start and stop | `.cmd` one-liners calling a Java `Launcher`: `setup`, `start` (Postgres, then the server JVM), `stop`, and any CLI command (starts Postgres if needed) | `.cmd` files are not subject to PowerShell execution policy |
| Service | WinSW 2.12.0 (MIT), optional, wrapping `start --foreground` | Simple and permissively licensed; procrun fits poorly around a child Postgres |
| Java | Temurin 21 JRE zip shipped in the bundle | The client may have no Java and no admin. GPLv2 with Classpath Exception: recorded as an ADR exception for the owner |
| git | JGit replaces the `git log` call | git is GPL and not on the client; JGit is EDL (BSD-3) |
| Node | Node 24 Windows zip shipped for `ng-extract`; path configurable | Only for indexing Angular target code |
| Zowe | Command configurable, defaulting to `zowe.cmd` on Windows | `ProcessBuilder("zowe")` fails on Windows |
| Tests | Testcontainers replaced by a JUnit extension that starts a throwaway native Postgres through the production `LocalPostgres` class, from `TOOLKIT_TEST_PG_HOME` (Homebrew 17 on the Mac, the bundled Postgres on Windows CI) | Every test run exercises the client's database path; no Docker anywhere |

## Bundle layout

```
toolkit\
  toolkit.cmd  start.cmd  stop.cmd  INSTALL.md  sbom.json  LICENSES\
  lib\toolkit.jar                  (includes the UI build)
  config\toolkit.yaml.example
  runtime\jre\  runtime\pgsql\ (with vector.dll)  runtime\node\
  tools\ng-extract\
  service\WinSW-x64.exe  service\toolkit.xml
  data\                            (created by setup: pg\, tls\, logs\)
```

Downloads at bundle-build time are pinned by URL and SHA-256.

## Proof

On the Mac: path-key tests on a Windows-configured Jimfs; the whole launcher flow against Homebrew Postgres 17 and pgvector; the merged-server tests (UI without token, `/api` 401, MCP 19 tools and 401, health, headers, stream past 60 s, admin created once); the full existing test suite on native Postgres.

On `windows-latest`, from the built zip, in a folder whose path has a space: setup, start, doctor, discover and index of CardDemo checked out with CRLF line endings, no backslash in any stored path, pgvector present, token, MCP probe with cited answers and a 401 without a token, UI deep link, stop leaves nothing running, restart keeps data, WinSW install and uninstall.

## Risks

- The EDB zip may need the Visual C++ runtime, which the CI runner already has. The launcher checks for `vcruntime140.dll` and says how to fix it.
- The EDB zip may carry LGPL DLLs. The bundle's license inventory will show them; any that ship are recorded for the owner.
- Remote MCP clients need a Windows Firewall rule, which needs admin once.
- The CI runner is an administrator, so a true standard-user run is not proved there.

## Left to the builder

Class and package names, test names, the exact launcher command syntax beyond the entry points above, and how the bundle script is structured.

## Calls made without the owner (for review)

- Docker removed entirely, Helm included.
- PostgreSQL 17 rather than 16.
- Temurin JRE shipped despite its GPL-with-Classpath-Exception license.
- V1 migration edited rather than a new migration added.
