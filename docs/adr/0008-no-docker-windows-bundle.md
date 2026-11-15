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
- License exceptions, found by the bundle task (`scripts/build-windows-bundle.py`, which writes `LICENSES/INVENTORY.md`
  listing every exe, dll and jar in the zip with its license and source), for the owner to accept:
  - Temurin 21 JRE (`runtime/jre`): GPL-2.0 with the Classpath Exception, shipped unmodified. Its `legal/` folder
    stays with it.
  - `libintl-9.dll` (GNU gettext 0.19.8) and `libiconv-2.dll` (GNU libiconv 1.15) from the EDB PostgreSQL zip: LGPL
    (EDB's notice carries the LGPL-3.0 text; upstream they are LGPL-2.1-or-later). They cannot be left out:
    `postgres.exe`, `psql.exe`, `pg_ctl.exe`, `initdb.exe` and `libpq.dll` import `libintl-9.dll`, which imports
    `libiconv-2.dll`. They ship unmodified as separate DLLs that a user can replace, with the license text, and the
    inventory names the upstream source and offers the exact source on request (LGPL-2.1 section 6 / LGPL-3.0
    section 4). Publishing those two source tarballs next to each release would remove the need for the offer.
  - Everything else kept from the EDB zip is permissive: PostgreSQL License (the server, libpq), Apache-2.0
    (OpenSSL 3.5), Unicode/ICU (ICU 67), MIT (libxml2), BSD-2-Clause (LZ4), BSD-3-Clause (zstd), Zlib. The zip's
    wxWidgets, libcurl and libxslt DLLs, pgAdmin and StackBuilder are not shipped.
  - WinSW 2.12.0 `WinSW-x64.exe`: MIT; it is a self-contained .NET Core 3.1 build, whose runtime is MIT too. Node.js
    24: MIT with the third-party notices in its LICENSE. ng-extract's only dependency, TypeScript: Apache-2.0.
  - The Visual C++ runtime. Every EDB program and DLL imports `VCRUNTIME140.dll` (ICU also `MSVCP140.dll`); the zip does
    not carry them, and Windows has them only when the Visual C++ Redistributable is installed. We do not copy them
    into the bundle ourselves. Temurin ships `vcruntime140.dll`, `vcruntime140_1.dll` and `msvcp140.dll` (14.40) in
    `runtime/jre/bin` under Microsoft's distributable-code terms, and the launcher appends that folder to the PATH of
    the PostgreSQL processes it starts, so a machine without the Redistributable works and one with it uses its own
    (System32 comes first in the DLL search order). The CI runner has the Redistributable, so the smoke test hides the
    System32 copies, shows `initdb.exe` then fails with 0xC0000135, and shows the bundle then still sets up, starts
    and loads `vector.dll` with the runtime from `runtime/jre/bin`. The Universal C runtime (`api-ms-win-crt-*`) is
    part of Windows 10 and later.
