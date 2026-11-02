# Installing the Mainframe Modernization Toolkit

One instance per client, inside the client's network. The target is your first MCP query in under a working day. This runbook is all you need.

## 1. Before you start

Complete the intake checklist in the spec's "Configuration and install-time intake". For a first query you need only:

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
```

Edit `.env` and set `TOOLKIT_SOURCE` to the absolute path of the export. On an air-gapped machine also set `TOOLKIT_PULL_POLICY=never`.

```sh
cp config/toolkit.yaml.example config/toolkit.yaml
./gen-tls.sh "$(hostname)"
```

Edit `client:` in `config/toolkit.yaml`. Replace `tls/tls.crt` and `tls/tls.key` with a certificate from the client's CA when you have one.

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

It prints a summary with counts, parse status and static CALL resolution. `config/discovery-report.json` lists every parse failure with its position, unresolved CALL, missing copybook and dead-code candidate. Discovery reruns only what changed, and a stopped run resumes where it ended.

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

Add an `llm:` block to `config/toolkit.yaml` (see `config/toolkit.yaml.example` in the spec's format), put the key in `TOOLKIT_LLM_KEY` in `.env`, run `docker compose up -d`, and run doctor again. Then:

```sh
./toolkit knowledge run -c /config/toolkit.yaml --budget 50
./toolkit index -c /config/toolkit.yaml
```

The run stops cleanly at the budget and resumes on the next run. Unchanged units cost nothing. For USD budgets set `input_usd_per_mtok` and `output_usd_per_mtok` in the `llm:` block.

**Air-gapped:** `docker compose --profile local-llm up -d`, load the bundled models into the `ollama` volume, and use `provider: openai-compatible`, `endpoint: http://ollama:11434`.

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
