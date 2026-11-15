# Windows bundle: evidence

Date: 2026-11-15. Runner: GitHub Actions `windows-latest`, public repository `balapininti/legacy-modern`, branch
`win/ci-windows`, workflow `.github/workflows/windows.yml` (first all-green run: 43686970609, commit 8a160c5).

## Jobs

| Job | Proves |
|---|---|
| `pgvector` | pgvector 0.8.7 builds with `nmake /F Makefile.win` against the EDB PostgreSQL 17.12-1 zip and loads in a fresh cluster |
| `bundle` | `scripts/build-windows-bundle.py` on Windows: `toolkit-0.1.0-SNAPSHOT-windows-x64.zip`, 213.7 MB (454.6 MB unpacked), every download checked against its pinned SHA-256; `dumpbin /dependents` of what the PostgreSQL programs and `vector.dll` load |
| `smoke` | `scripts/windows-smoke.py` from that zip: 20 checks, below |
| `test` | `mvnw verify` on Windows against the EDB zip plus our pgvector: 248 tests, 0 failed, 1 skipped (a POSIX shell stand-in for the Zowe CLI) |

## Smoke checks (all PASS)

Unzip into `C:\Users\runneradmin\Tool Kit`; CardDemo at the pinned commit checked out with `core.autocrlf=true`
(CRLF in every `.cbl`) under `C:\Users\runneradmin\main frame src`; `toolkit.cmd setup`; `start.cmd`; health over
HTTPS verified against `data\tls\toolkit.crt`; `doctor` (database, source, vector PASS); `discover` (all 19 summary
fields equal `testdata/snapshots/carddemo-summary.json`, made from the LF checkout); `index`; no backslash in any stored
path (329 values in 3 path columns); `vector` 0.8.7 with `chunk.embedding`; token; MCP over HTTPS (19 tools,
`who_calls COSGN00C` 47 callers each with member and line, checked against the source; 401 without a token); UI `/`,
`/review` and a deep link serve `index.html`; certificate trust (below); `stop.cmd` leaves no java or postgres from
the install and both ports free; restart keeps data and the token; the VC++ runtime check (below); WinSW service
install, start, health, 19 MCP tools as LocalSystem, stop, uninstall; the folder deletes with no locked file.

## Findings

- **Visual C++ runtime.** The runner has it in System32, so the smoke test hides `vcruntime140.dll`,
  `vcruntime140_1.dll` and `msvcp140.dll` there. Control: the bundled `initdb.exe` alone then fails with 0xC0000135.
  With `runtime\jre\bin` on PATH (what the launcher adds for PostgreSQL), initdb, `start.cmd`, a vector query and
  `stop.cmd` work, and the running postgres processes have the three DLLs loaded from `runtime\jre\bin`.
- **Certificate trust.** `certutil -user -addstore Root` waits for the user's confirmation dialog (timed out
  unattended); `certutil -addstore Root` from an administrator prompt adds it silently, after which Schannel
  (PowerShell `Invoke-WebRequest`) trusts the server. Node rejects the certificate without `NODE_EXTRA_CA_CERTS` and
  accepts it with.
- **PostgreSQL drops administrator rights.** Run by an administrator, initdb re-runs itself with a restricted token.
  In a folder only Administrators can read (the first smoke runs unpacked into a Python `mkdtemp` folder, whose ACL
  is owner-only with Administrators as owner), it failed with 0xC0000135 although every DLL was there. `setup` now
  grants the current user's SID modify rights on the install folder.

## Not proved here

- A standard (non-administrator) user: the runner is an administrator.
- A machine that truly never had the VC++ Redistributable (simulated by hiding the System32 copies).
- `certutil -user` with a person clicking Yes, and VS Code or Cursor themselves connecting.
- Windows 10 and Server 2019 (the runner is a current Windows Server image).
