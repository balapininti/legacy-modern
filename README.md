# Mainframe Modernization Toolkit

Reads a mainframe source export and gives AI coding agents grounded answers about it over MCP, with member and line citations. Also generates agent instructions, keeps a human-reviewed knowledge base, and verifies translated code against captures of the original.

The toolkit does not translate code. Agents such as Claude Code or GitHub Copilot do, using these tools. Client source leaves the machine only for the LLM endpoint you configure.

## Where to start

- **Install:** [deploy/INSTALL.md](deploy/INSTALL.md), the only document an installer needs
- **What is proved and what is not:** [docs/STATUS.md](docs/STATUS.md)
- **Changing the code (people and agents):** [docs/PROJECT-CONTEXT.md](docs/PROJECT-CONTEXT.md), [.github/copilot-instructions.md](.github/copilot-instructions.md), [AGENTS.md](AGENTS.md)
- **Spec:** [docs/spec/build-spec.md](docs/spec/build-spec.md)
- **Decisions:** [docs/adr](docs/adr)
- **Evidence per milestone:** [docs/evidence](docs/evidence)

## What is in it

| Part | Does |
|---|---|
| `parsers`, `discovery` | COBOL, copybooks, JCL, BMS, DDL and CSD into a graph, with every fact traced to a line. PL/I, REXX and Assembler are inventoried only |
| `retrieval` | Lexical and vector search fused, with reviewed knowledge ranked higher |
| `knowledge` | LLM summaries, rules and migration notes, each validated against the source, then reviewed by people |
| `mcp` | 19 tools, 4 prompts and 3 resource templates over Streamable HTTP and stdio |
| `rulebook` | Generates `CLAUDE.md` and Copilot instructions from your repository's conventions |
| `verify` | Decodes captured datasets and generates JUnit tests against them |
| `api`, `ui` | Web UI for review, target-code links, deviations, gaps and tracking |
| `deploy` | Install runbook and the Windows bundle's launcher files. No container images ([ADR 0008](docs/adr/0008-no-docker-windows-bundle.md)) |

## Develop

Needs JDK 21, Maven (or `./mvnw`), PostgreSQL 17 with pgvector, and Node 24. No containers. PostgreSQL is not run as a service: tests and scripts start their own throwaway servers from its binaries, found through `TOOLKIT_TEST_PG_HOME` (the folder holding `bin/pg_ctl`), else `pg_config` on the PATH, else Homebrew's `postgresql@17`.

- macOS: `brew install postgresql@17 pgvector`
- Debian or Ubuntu (PGDG repository): `apt install postgresql-17 postgresql-17-pgvector`, then `export TOOLKIT_TEST_PG_HOME=/usr/lib/postgresql/17`

```bash
source scripts/env.sh          # JDK 21 and Maven from .tools when present
scripts/fetch-corpora.sh       # pinned CardDemo; --all for the other 9 validation repositories
./mvnw verify                  # each test JVM starts its own PostgreSQL on a free port
cd ui && npm ci && TOOLKIT_CHECKOUT=.. npm run e2e
scripts/dev-pg.sh start        # a persistent dev PostgreSQL on 127.0.0.1:55433 for validate-corpora.py and mcp-probe.py
```

The Windows client bundle (`target/toolkit-<version>-windows-x64.zip`; CI builds it on Windows in `.github/workflows/windows.yml` and proves it with `scripts/windows-smoke.py`):

```bash
./mvnw -B -q -DskipTests -DskipITs package && (cd ui && npm ci && npm run build)
python3 scripts/build-windows-bundle.py --jar cli/target/toolkit.jar --ui ui/dist/ui/browser --pgvector <pgvector-win-x64 artifact, or none> --out target
```

No test calls a live LLM. Real model answers are recorded in `testdata/llm-recordings` and replayed.
