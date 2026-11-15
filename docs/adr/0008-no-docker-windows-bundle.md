# 0008: No Docker; a Windows zip bundle instead

Status: accepted. Date: 2026-11-14.

## Context

The owner, on 2026-11-14: "for the legacy-modern, i dont want any docker, in the client machine there is no docker and its windows based machine. also give instructions on how to start the repos".

Until then the toolkit installed as Docker images through Compose, or Helm on Kubernetes, and every database test ran PostgreSQL in a Testcontainers container. None of that works on a Windows machine without Docker, and the installer may have no admin rights. Design: `docs/superpowers/specs/2026-11-14-windows-no-docker-design.md`.

## Decision

- The client install is one Windows zip: a Temurin 21 JRE, portable PostgreSQL 17 (the EDB Windows x64 binaries) with pgvector built by our own CI from source, Node 24 for `ng-extract`, the toolkit jar with the UI build inside, and optional WinSW for service mode. Downloads are pinned by URL and SHA-256 when the bundle is built; nothing is fetched at install time.
- One Spring process serves the UI, the REST API and MCP on one HTTPS port (`toolkit serve`; `serve --plain-http` for development). nginx is gone.
- `.cmd` files (`toolkit.cmd`, `start.cmd`, `stop.cmd`) call a Java launcher that runs `setup`, `start`, `stop` and every other command, with no PowerShell and no admin.
- Docker is removed from the repository: Compose, Helm, the Dockerfiles, the Jib image profile, the nginx UI image, the air-gap image tarball, Testcontainers, and the scripts that used them. Tests, the UI e2e stack and the evaluation scripts start a native PostgreSQL 17 with pgvector from `TOOLKIT_TEST_PG_HOME` (Homebrew on macOS, PGDG packages on Linux CI, the EDB zip on Windows CI). `scripts/dev-pg.sh` starts one for development.
- pgvector is optional at run time: without the extension, migrations still succeed, search is lexical only and `doctor` warns.

## Consequences

- No Kubernetes path. Helm needs images, and images need Docker. A client that wants Kubernetes is a new decision.
- No Linux or macOS client bundle. The jar still runs there against a native PostgreSQL, which is how developers run it.
- These parts of `docs/spec/build-spec.md` are superseded: line 57 (`deploy/` holds `compose/` and `helm/`), lines 100-101 and 103 (Compose, Helm, Jib images; Testcontainers), lines 430-445 (Compose and Helm services, image air-gap bundle, release artifacts), line 453 (Postgres via Testcontainers), line 483 (M9 Compose, Helm, air-gap bundle), line 496 (Testcontainers and the Compose smoke test in CI) and the "adding a service to Compose" clause of line 507. The air-gap requirement itself stands: the zip carries everything.
- Every test run now exercises the same `LocalPostgres` code the client's launcher uses.
- License exceptions, to be recorded by the bundle task with the bundle's license inventory, for the owner to accept:
  - Temurin JRE: GPLv2 with the Classpath Exception.
  - The EDB PostgreSQL zip's DLLs, including LGPL `libintl` and `libiconv`.
  - The Visual C++ runtime is not in the EDB zip's `bin`; the client needs it installed, or the bundle must carry it under Microsoft's redistribution terms.
