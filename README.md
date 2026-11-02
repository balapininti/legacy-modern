# Mainframe Modernization Toolkit

Reads a mainframe source export and gives AI coding agents grounded answers about it over MCP, with member and line citations. Also generates agent instructions, keeps a human-reviewed knowledge base, and verifies translated code against captures of the original.

The toolkit does not translate code. Agents such as Claude Code or GitHub Copilot do, using these tools. Client source leaves the machine only for the LLM endpoint you configure.

## Where to start

- **Install:** [deploy/INSTALL.md](deploy/INSTALL.md), the only document an installer needs
- **What is proved and what is not:** [docs/STATUS.md](docs/STATUS.md)
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
| `deploy` | Compose, Helm, air-gap bundle |

## Develop

```bash
source scripts/env.sh          # JDK 21 and Maven from .tools, Docker host
scripts/fetch-corpora.sh       # pinned CardDemo; --all for the other 9 validation repositories
./mvnw verify                  # needs Docker
cd ui && npm ci && TOOLKIT_CHECKOUT=.. npm run e2e
scripts/compose-smoke.sh       # after scripts/build-images.sh
```

No test calls a live LLM. Real model answers are recorded in `testdata/llm-recordings` and replayed.
