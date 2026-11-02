# Installing the Mainframe Modernization Toolkit

One instance per client, inside the client's network. The target is your first MCP query in under a working day. This runbook is all you need.

## 1. Before you start

The full intake, once you go beyond a first query: the source export with copybooks, JCL, PROCs, BMS and DDL; an approved LLM endpoint with written data-policy sign-off, or an air-gap decision; test LPAR access or a waiver with exported fixtures; the target stack; the phase 1 programs and jobs; one SME per business domain; identity provider details.

For a first query you need only:

- A machine with Docker and Compose, 8 vCPU and 32 GB for a 1M line estate. Less works for less.
- The source export on that machine: a git checkout or a PDS dump, as files. Extensions do not matter.

**Encryption at rest is the client's responsibility, at the volume level.** Put the Compose volumes on encrypted storage.

## 2. Unpack and load

From the release tarball:

```sh
tar xzf toolkit-*-airgap.tar.gz && cd toolkit-*-airgap
./load-images.sh
cd compose
```

## 3. Configure

```sh
cp .env.example .env
openssl rand -base64 24   # paste as TOOLKIT_DB_PASSWORD in .env
openssl rand -base64 24   # paste as TOOLKIT_ADMIN_PASSWORD in .env
chmod 600 .env
```

Edit `.env` and set `TOOLKIT_SOURCE` to the absolute path of the export. On a Mac, Docker can mount only shared paths, usually your home directory, so keep the bundle and the export there. To use another HTTPS port than 8443, set `TOOLKIT_HTTPS_PORT`, and use that port in the URLs below. On an air-gapped machine also set `TOOLKIT_PULL_POLICY=never`.

```sh
cp config/toolkit.yaml.example config/toolkit.yaml
./gen-tls.sh "$(hostname)"
```

In `config/toolkit.yaml`, set `client:` to a short name for this client; it appears in generated instructions. Replace `tls/tls.crt` and `tls/tls.key` with a certificate from the client's CA when you have one, and run `chmod 600 tls/tls.key`, leaving it readable by the container's user (uid 101) if you mount it with stricter ownership.

## 4. Start and check

```sh
docker compose up -d
./toolkit doctor -c /config/toolkit.yaml
```

Doctor prints PASS, FAIL or SKIP for source, database, LLM, z/OS and identity provider. A FAIL says what to fix. SKIP is fine for what you have not configured yet: discovery and MCP need no LLM.

## 5. Discover

```sh
./toolkit discover -c /config/toolkit.yaml --report /config/discovery-report.json
./toolkit index -c /config/toolkit.yaml
```

It prints a summary. `config/discovery-report.json` lists every parse failure with its position, unresolved CALL, missing copybook, missing PROC and dead-code candidate. Discovery reruns only what changed, and a stopped run resumes where it ended.

Reading the summary:

| Field | Meaning |
|---|---|
| `parse_status` | `parsed`; `in_context`, a copybook that only makes sense inside a program that parsed; `failed`, in the report with its position; `inventoried`, a member-level language or something that is not source |
| `members_by_type` | By content. `unknown` is anything with no recognized signature: data files, documents, images. Check it for source you expected |
| `static_calls.resolved_pct` | Calls whose target is in this estate, leaving out subsystem APIs such as MQ and CEE. Null means there were no such calls. Below 95% usually means the export is missing programs: see `calls_to_missing_programs` |
| `db2_tables_with_ddl`, `db2_tables_referenced` | Tables with a CREATE TABLE in the export, and tables the programs' SQL touches. Zero of the first with some of the second means the DDL is not in the export |
| `missing_copybooks`, `missing_proc_names` | Distinct names. The report lists each place they are used |
| `symbolic_maps_generated_from_bms` | Copybooks the BMS assembler generates, so they are expected to be absent |
| `dead_code_candidates` | No caller, JCL step or transaction. Scheduler entries are invisible here, so these are candidates only |
| `dialect_features` | `FALLBACK PARSER koopa` counts members only the fallback parser could read. It passes over what it does not recognize, so facts from those members may be incomplete |

## 6. First MCP query

Issue a token. It is shown once.

```sh
./toolkit token --user your.name --role developer
```

Connect a client to `https://<host>:8443/mcp` with that bearer token.

Claude Code:

```sh
claude mcp add --transport http legacy https://<host>:8443/mcp --header "Authorization: Bearer <token>"
```

VS Code Copilot agent mode, in `.vscode/mcp.json`:

```json
{"servers": {"legacy": {"type": "http", "url": "https://<host>:8443/mcp", "headers": {"Authorization": "Bearer <token>"}}}}
```

Cursor uses the same shape in `.cursor/mcp.json`, under `mcpServers`.

Clients must trust the certificate, so a self-signed one has to be added to the machine's trust store (for Claude Code, `NODE_EXTRA_CA_CERTS=/path/to/tls.crt`).

Ask: *"What does inventory_summary say?"*, then *"What calls PROGRAMX, and which DB2 tables does it touch?"* with a real program name. You are done when the answer cites members and lines.

## 7. The web UI

`https://<host>:8443`. Sign in as `admin` with `TOOLKIT_ADMIN_PASSWORD`. Create users, roles and MCP tokens on the Admin page. For OIDC, set `TOOLKIT_OIDC_ISSUER` and `TOOLKIT_OIDC_CLIENT` in `.env` and run `docker compose up -d`. Tokens may carry the roles `admin`, `developer` and `sme-reviewer` in a `roles` or `groups` claim.

## 8. The knowledge pass

Needs written data-policy sign-off for the LLM endpoint, or a local model.

Add this to `config/toolkit.yaml`, with your provider's values, put the key in `TOOLKIT_LLM_KEY` in `.env`, run `docker compose up -d`, and run doctor again:

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

```sh
./toolkit knowledge run -c /config/toolkit.yaml --budget 50
./toolkit index -c /config/toolkit.yaml
```

The run stops cleanly at the budget and resumes on the next run. Unchanged units cost nothing. A USD budget needs the two `usd_per_mtok` prices, since nothing else tells the toolkit what a token costs; `--budget-tokens` works without them.

**Air-gapped:** the release bundle holds models only if it was built with their names. Start `docker compose --profile local-llm up -d`, copy the bundle's `models/` into the `ollama` volume, and use `provider: openai-compatible`, `endpoint: http://ollama:11434`, with the model names in `models/names.txt`.

## 9. Existing modern code, rulebook, verification

- Set `TOOLKIT_TARGET` in `.env`, `target.index_existing: true` in `toolkit.yaml`, and run discover again. A lead confirms links on the Link confirmation page.
- `./toolkit rulebook analyze --repo /data/target --out /config/conventions.yaml`, have a lead edit it, then `./toolkit rulebook generate -c /config/toolkit.yaml --conventions /config/conventions.yaml --out /data/target`.
- Captures: `./toolkit capture submit` with a test LPAR, or with no LPAR, export datasets into a directory with a `manifest.json` naming each file's copybook, and run `./toolkit capture import --dir DIR`. Then `./toolkit tests generate`, and after the target repo's tests have run, `./toolkit tests report`.

## Kubernetes

The chart in `helm/toolkit` runs the same images. Create the Secrets and the source PVC named in `values.yaml` first. The chart holds no secret values.

```sh
helm install toolkit helm/toolkit -f my-values.yaml
```

## Operations

- **Upgrades:** load the new images and `docker compose up -d`. Flyway migrations are forward only, so back up the `pgdata` volume first. Prompt templates are versioned, and the knowledge job re-runs only units whose prompt version or content changed.
- **Metrics:** `/actuator/prometheus` on `toolkit-api` and `toolkit-mcp`: tool latency, members parsed per minute, pending members, LLM tokens and cost. Logs are JSON on stderr. `/actuator/health` on each service.
- **No telemetry leaves the instance.** The only outbound calls go to the LLM and embedding endpoint named in `toolkit.yaml`, the identity provider, and the test LPAR.
