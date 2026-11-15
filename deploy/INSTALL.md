# Installing the Mainframe Modernization Toolkit on Windows

One install per client, on one Windows machine inside the client's network. Nothing is downloaded during the
install and nothing needs administrator rights, except the optional firewall rule and service mode. The target is a
first cited MCP answer on the first day.

Everything is in this folder: a Java runtime, PostgreSQL 17 with pgvector, Node.js (for indexing Angular code), the
toolkit and its web UI. You run three files: `toolkit.cmd`, `start.cmd` and `stop.cmd`.

Run every command below in **Command Prompt** (`cmd.exe`), from the install folder. PowerShell works too: there, type
`.\toolkit.cmd` instead of `toolkit.cmd`. No PowerShell execution policy change is needed.

## 1. Requirements

- Windows 10 22H2 or later, Windows 11, or Windows Server 2019 or later, 64-bit (x64).
- Memory: 8 GB for a small estate (CardDemo, about 60,000 lines). For about a million lines, 16 to 32 GB and 8 CPU
  cores. To give the server more memory, put `TOOLKIT_JAVA_OPTS=-Xmx8g` in `config\toolkit.env`.
- Disk: about 600 MB for the toolkit itself, plus the database. Plan 2 GB for a small estate and 20 GB per million
  lines of source, more after the knowledge pass with embeddings.
- The source export as files on this machine: a git checkout or a PDS dump. File extensions do not matter.
- Two free ports: 8443 (web UI, REST API and MCP, HTTPS) and 55432 (the toolkit's own PostgreSQL, reachable only from
  this machine). Both can be changed at setup.
- **Microsoft Visual C++ runtime.** The bundled PostgreSQL needs `VCRUNTIME140.dll` and `MSVCP140.dll`. You do not have
  to install anything: the toolkit uses the copies that come with its Java runtime (`runtime\jre\bin`) when Windows
  has none. If you run the PostgreSQL programs in `runtime\pgsql\bin` yourself (backups, section 12), see the note
  there.

**Encryption at rest is the client's responsibility, at the volume level.** Put the install folder on an encrypted
volume (BitLocker), since `data\` holds the database and `config\toolkit.env` holds its passwords.

## 2. Unzip

1. Copy `toolkit-<version>-windows-x64.zip` and its `.sha256` file to the machine.
2. Check the download: `certutil -hashfile toolkit-<version>-windows-x64.zip SHA256` must print the value in the
   `.sha256` file.
3. Right-click the zip, **Extract All**, and choose a folder you own, for example `C:\Tool Kit` or
   `D:\toolkit`. Spaces in the path are fine. Do not use `C:\Program Files`: standard users cannot write there.
4. Open Command Prompt in the extracted `toolkit` folder:

   ```bat
   cd /d "C:\Tool Kit\toolkit"
   ```

If Windows marks the extracted files as downloaded from the internet and asks before running them, right-click the
zip, **Properties**, tick **Unblock**, and extract again.

## 3. Set up

```bat
toolkit.cmd setup --source "D:\export" --client acme
```

`--source` is the folder holding the source export. `--client` is a short name that reports show. Optional:
`--port 9443` for another HTTPS port, `--pg-port 55433` for another database port, `--target "D:\repos\modern-app"`
for a checkout of the modern (Java and Angular) repository.

Setup takes about a minute. It writes:

- `config\toolkit.yaml`: the settings, without secrets. `config\toolkit.yaml.example` shows every block.
- `config\toolkit.env`: generated passwords for the database, the web UI admin and the certificate keystore, readable
  only by you (and by Windows itself, for service mode). One `NAME=value` per line.
- `data\tls\toolkit.p12`: a self-signed certificate for this machine's name, `localhost` and `127.0.0.1`, and
  `data\tls\toolkit.crt`, the same certificate without its key, for clients to trust (section 7).
- `data\pg\`: the toolkit's PostgreSQL database.

It prints the web UI login, `admin` and a password. **It is shown once.** It is also stored in
`config\toolkit.env` as `TOOLKIT_ADMIN_PASSWORD`.

To start over, `toolkit.cmd setup --source ... --force` deletes the database and the certificate and writes new
secrets.

## 4. Start and check

```bat
start.cmd
```

It starts the database, then the server, waits until it answers, and prints the address, for example
`toolkit is running: https://myhost:8443/`. The server keeps running after you close the window. Logs go to
`data\logs\`.

Open `https://<host>:8443` in a browser. With the self-signed certificate the browser warns that the connection is
not private: that is expected until you trust the certificate (section 7) or replace it with one from the client's
certificate authority (below). Sign in as `admin` with the password from setup.

```bat
toolkit.cmd doctor
```

Doctor prints PASS, WARN, FAIL or SKIP for config, source, database, pgvector, LLM, z/OS and identity provider. A
FAIL says what to fix. SKIP is fine for what you have not configured yet: discovery and MCP need no LLM. WARN on
`vector` means this PostgreSQL has no pgvector: everything works, search is lexical only.

Health, without signing in: `https://<host>:8443/actuator/health` answers `{"status":"UP"}`.

### A certificate from the client's certificate authority

Ask for a server certificate for the machine's name, as PEM files: the certificate (with the intermediate
certificates after it) and an unencrypted private key. Store them in `data\tls\`, then add two lines to
`config\toolkit.env` and restart (`stop.cmd`, `start.cmd`):

```
TOOLKIT_TLS_CERT=C:\Tool Kit\toolkit\data\tls\server.crt
TOOLKIT_TLS_KEY=C:\Tool Kit\toolkit\data\tls\server.key
```

Values are taken exactly as written: no quotes, even with spaces in the path. Clients that already trust the
client's certificate authority need nothing else. Keep the key file readable only by the account that runs the
toolkit.

## 5. Discover

```bat
toolkit.cmd discover --report data\discovery-report.json
toolkit.cmd index
```

Discover parses the export and prints a summary. `data\discovery-report.json` lists every parse failure with its
position, unresolved CALL, missing copybook, missing PROC and dead-code candidate. Discovery reruns only what changed,
and a stopped run resumes where it ended. `index` builds the search index MCP uses. Run both again after the export
changes.

Reading the summary:

| Field | Meaning |
|---|---|
| `parse_status` | `parsed`; `in_context`, a copybook that only makes sense inside a program that parsed; `failed`, in the report with its position; `inventoried`, a member-level language or something that is not source |
| `members_by_type` | By content. `unknown` is anything with no recognized signature: data files, documents, images. Check it for source you expected |
| `static_calls.resolved_pct` | Calls whose target is in this estate, leaving out subsystem APIs such as MQ and CEE. Null means there were no such calls. Below 95% usually means the export is missing programs: see `calls_to_missing_programs` in the report |
| `db2_tables_with_ddl`, `db2_tables_referenced` | Tables with a CREATE TABLE in the export, and tables the programs' SQL touches. Zero of the first with some of the second means the DDL is not in the export, or not in a form discovery reads, such as a Jinja template |
| `missing_copybooks`, `missing_proc_names` | Distinct names. The report lists each place they are used |
| `symbolic_maps_generated_from_bms` | Copybooks the BMS assembler generates, so they are expected to be absent |
| `dead_code_candidates` | No caller, JCL step or CSD transaction. Scheduler entries are invisible here, and without CSD definitions in the export so are programs started only by a transaction, so these are candidates only |
| `lines` | All text lines in members discovery read, including documents and other files. It is not a count of COBOL |
| `dialect_features` | `FALLBACK PARSER koopa` counts members only the fallback parser could read. It passes over what it does not recognize, so facts from those members may be incomplete |

Line ends do not matter: a checkout with Windows (CRLF) line ends gives the same results as one with LF.

## 6. A token for MCP

```bat
toolkit.cmd token --user your.name --role developer
```

The last line is the token. **It is shown once.** Roles: `developer`, `sme-reviewer`, `admin`. Tokens can also be
created and revoked on the web UI's Admin page.

## 7. Connect an MCP client

The MCP endpoint is `https://<host>:8443/mcp`, with the token as a bearer token. Use the machine name the
certificate was made for (setup printed it), or `localhost` on the toolkit machine itself.

**Trust the certificate first** (not needed with a certificate from a certificate authority the client machine
already trusts). Copy `data\tls\toolkit.crt` to the client machine, then:

- Claude Code (Node.js; it does not read the Windows certificate store): set the variable once, in a new Command
  Prompt, and restart Claude Code:

  ```bat
  setx NODE_EXTRA_CA_CERTS "C:\certs\toolkit.crt"
  ```

- VS Code, Cursor and browsers use the Windows certificate store. For the current user, no administrator needed:

  ```bat
  certutil -user -addstore Root "C:\certs\toolkit.crt"
  ```

  Windows shows a security warning naming the certificate; choose **Yes**. To trust it for every user of the machine
  instead, run `certutil -addstore Root "C:\certs\toolkit.crt"` from an administrator Command Prompt (no dialog).

Then add the server.

Claude Code:

```bat
claude mcp add --transport http legacy https://<host>:8443/mcp --header "Authorization: Bearer <token>"
```

VS Code Copilot agent mode, in `.vscode\mcp.json`:

```json
{"servers": {"legacy": {"type": "http", "url": "https://<host>:8443/mcp", "headers": {"Authorization": "Bearer <token>"}}}}
```

Cursor uses the same shape in `.cursor\mcp.json`, under `mcpServers`.

Ask: *"What does inventory_summary say?"*, then *"What calls PROGRAMX, and which DB2 tables does it touch?"* with a
real program name. You are done when the answer cites members and lines.

**Clients on other machines** need an inbound firewall rule for the port, once, from an administrator Command Prompt:

```bat
netsh advfirewall firewall add rule name="Mainframe Modernization Toolkit" dir=in action=allow protocol=TCP localport=8443
```

The database port (55432) listens on `127.0.0.1` only and needs no rule.

## 8. The web UI

`https://<host>:8443`. Sign in as `admin` with `TOOLKIT_ADMIN_PASSWORD`. Create users, roles and MCP tokens on the
Admin page. For the client's identity provider (OIDC), add `TOOLKIT_OIDC_ISSUER=https://login.example.internal` and
`TOOLKIT_OIDC_CLIENT=<client id>` to `config\toolkit.env` and restart. Tokens may carry the roles `admin`, `developer`
and `sme-reviewer` in a `roles` or `groups` claim.

## 9. Stop

```bat
stop.cmd
```

It stops the server, then the database. Nothing of the toolkit keeps running. `start.cmd` starts it again with all
data, users and tokens kept. Shutting Windows down without `stop.cmd` is safe: the next `start.cmd` recovers.

## 10. The knowledge pass

Needs written data-policy sign-off for the LLM endpoint, or a local model.

Add an `llm:` block to `config\toolkit.yaml` (copy it from `config\toolkit.yaml.example`), put the key in
`config\toolkit.env` as `TOOLKIT_LLM_KEY=...`, restart (`stop.cmd`, `start.cmd`) and run `toolkit.cmd doctor` again:

```yaml
llm:
  provider: azure-openai      # azure-openai | bedrock | openai | anthropic | openai-compatible
  endpoint: https://your-endpoint.example.internal
  api_key_env: TOOLKIT_LLM_KEY
  chat_model: gpt-4.1
  embedding_model: text-embedding-3-large
  embedding_dim: 3072
  budget_usd_per_run: 500
  input_usd_per_mtok: 2.50
  output_usd_per_mtok: 10.00
  concurrency: 4
  redact_pii: true
  pii_patterns: ["\\b\\d{3}-\\d{2}-\\d{4}\\b"]
```

Then:

```bat
toolkit.cmd knowledge run --budget 50
toolkit.cmd index
```

The run stops cleanly at the budget and resumes on the next run. Unchanged units cost nothing. A USD budget needs the
two `usd_per_mtok` prices, since nothing else tells the toolkit what a token costs; `--budget-tokens` works without
them.

**A local model with Ollama** (no data leaves the machine; no administrator needed):

1. Install Ollama for Windows: `OllamaSetup.exe` from https://ollama.com/download/windows installs for the current user.
   Without internet on this machine, copy the installer, or the standalone `ollama-windows-amd64.zip` (unzip it and
   run `ollama serve`), from a machine that has it.
2. Get the models: `ollama pull qwen2.5-coder:14b` and `ollama pull nomic-embed-text`. On an air-gapped machine, copy
   the `%USERPROFILE%\.ollama\models` folder from a machine that pulled them.
3. In `config\toolkit.yaml`:

   ```yaml
   llm:
     provider: openai-compatible
     endpoint: http://localhost:11434
     chat_model: qwen2.5-coder:14b
     embedding_model: nomic-embed-text
     embedding_dim: 768
     budget_tokens_per_run: 20000000
   ```

## 11. Existing modern code, rulebook, verification

- Run setup with `--target "D:\repos\modern-app"`, or add `target:` with `local_path` and `index_existing: true` to
  `config\toolkit.yaml`, and run discover again. A lead confirms links on the Link confirmation page. Angular code is
  indexed with the bundled Node.js; git is not needed.
- `toolkit.cmd rulebook analyze --repo "D:\repos\modern-app" --out config\conventions.yaml`, have a lead edit it, then
  `toolkit.cmd rulebook generate --conventions config\conventions.yaml --out "D:\repos\modern-app"`.
- Captures: `toolkit.cmd capture submit` with a test LPAR (the Zowe CLI, `zowe.cmd`, installed separately), or with no
  LPAR, export datasets into a folder with a `manifest.json` naming each file's copybook, and run
  `toolkit.cmd capture import --dir D:\captures\CBACT04C`. Then `toolkit.cmd tests generate`, and after the target
  repository's tests have run, `toolkit.cmd tests report`.

## 12. Back up and restore

Everything the toolkit knows is in `data\` (the database is `data\pg\`); the settings and secrets are in `config\`.

**Offline (simplest):** `stop.cmd`, copy or zip the `data` and `config` folders, `start.cmd`. To restore,
`stop.cmd`, replace both folders with the copies, `start.cmd`. Never copy `data\pg` while the toolkit runs.

**Online, with the bundled `pg_dump`**, while the toolkit runs (`TOOLKIT_DB_PASSWORD` and the port are in
`config\toolkit.env`):

```bat
set PATH=%PATH%;%CD%\runtime\jre\bin
set PGPASSWORD=<TOOLKIT_DB_PASSWORD from config\toolkit.env>
runtime\pgsql\bin\pg_dump.exe -h 127.0.0.1 -p 55432 -U toolkit -Fc -f D:\backup\toolkit.dump toolkit
```

The first line lets the PostgreSQL programs find the Visual C++ runtime in the Java runtime when Windows has none
installed (the toolkit does this itself for its own database). To restore into a running toolkit:

```bat
runtime\pgsql\bin\pg_restore.exe -h 127.0.0.1 -p 55432 -U toolkit -d toolkit --clean --if-exists D:\backup\toolkit.dump
```

## 13. Upgrade

1. `stop.cmd` (or `service\service.cmd stop` in service mode).
2. Back up `data\` and `config\` (section 12).
3. Extract the new zip to a new folder, then copy the old `config\` and `data\` folders into it, replacing nothing
   else. Or, in place: delete everything in the old folder except `config\` and `data\`, and extract the new version
   over it.
4. `start.cmd`. The database schema is migrated forward on start; migrations are forward only, which is why step 2
   matters.
5. Compare `config\toolkit.yaml` with the new `config\toolkit.yaml.example` for new settings.

In service mode, uninstall the service before replacing the files and install it again after (section 14).

## 14. Service mode (optional, needs an administrator)

To have the toolkit start with Windows and run without anyone signed in, register it as a Windows service. It runs
the same thing as `start.cmd`, under the Windows service manager, through WinSW (`service\toolkit.exe`, configured by
`service\toolkit.xml`).

From an **administrator** Command Prompt (right-click Command Prompt, **Run as administrator**), in the install folder:

```bat
stop.cmd
service\service.cmd install
service\service.cmd start
```

- `service\service.cmd stop` stops the server and the database; `service\service.cmd status` says `Started`,
  `Stopped` or `NonExistent`; `service\service.cmd uninstall` stops and removes the service and keeps all data.
- The service is named `toolkit` ("Mainframe Modernization Toolkit" in `services.msc`), starts automatically (delayed)
  with Windows, and restarts after a crash.
- While the service runs, do not use `start.cmd` or `stop.cmd`; `toolkit.cmd` commands (discover, index, token,
  doctor) work as usual.
- It runs as LocalSystem. To run it as another account, add a `<serviceaccount>` block to `service\toolkit.xml`
  before installing (see the comment in that file); that account needs full control of the install folder and read
  access to the source folder.
- Its output goes to `data\logs\toolkit.out.log`, `toolkit.err.log` and `toolkit.wrapper.log`, 10 MB each, 8 kept.

## 15. Uninstall

1. In service mode: `service\service.cmd uninstall` (administrator). Otherwise `stop.cmd`.
2. Delete the install folder. Nothing was written anywhere else, apart from what you added yourself: the firewall rule
   (`netsh advfirewall firewall delete rule name="Mainframe Modernization Toolkit"`) and trusted certificates on
   client machines (`certmgr.msc`, Trusted Root Certification Authorities).

## 16. Troubleshooting

- **"port 8443 is already in use"** (or 55432): another program has the port. Find it with
  `netstat -ano | findstr :8443` and the PID in Task Manager, or move the toolkit: change `TOOLKIT_HTTPS_PORT` (or
  `TOOLKIT_PG_PORT`) in `config\toolkit.env` and start again. For the database port also change the port in
  `TOOLKIT_DB_URL` in the same file.
- **Logs:** `data\logs\toolkit.log` (the server; the previous one is kept as `toolkit.log.1`),
  `data\logs\postgres.log` (the database), and in service mode `data\logs\toolkit.*.log`.
- **Is it up?** `https://<host>:8443/actuator/health` on the toolkit machine. `start.cmd` again says
  `already running` when it is.
- **doctor WARN `vector`:** this database has no pgvector, so search is lexical only. The full bundle includes
  pgvector; a `-lexical` bundle does not. Everything else works.
- **"Windows could not find a DLL ... (0xC0000135)":** the PostgreSQL programs did not find the Visual C++ runtime.
  Keep `runtime\jre` complete, or install the Microsoft Visual C++ 2015-2022 Redistributable (x64) from
  https://aka.ms/vs/17/release/vc_redist.x64.exe (needs an administrator).
- **The browser or an MCP client rejects the certificate:** trust `data\tls\toolkit.crt` on that machine
  (section 7), and connect with the name the certificate was made for, not an IP address other than `127.0.0.1`.
- **After a crash or power loss:** `start.cmd` removes stale lock files itself. If the database still does not start,
  the end of `data\logs\postgres.log` says why.

## Operations

- **Metrics:** `/actuator/prometheus`: tool latency, members parsed per minute, pending members, LLM tokens and cost.
- **No telemetry leaves the instance.** The only outbound calls go to the LLM and embedding endpoint named in
  `toolkit.yaml`, the identity provider, and the test LPAR.
- **Third-party software:** `LICENSES\INVENTORY.md` lists every program and library in this folder with its license;
  `sbom.json` is the software bill of materials (CycloneDX).
