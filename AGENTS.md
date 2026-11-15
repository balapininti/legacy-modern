# Agents working in this repository

Read these first, in this order:

1. [.github/copilot-instructions.md](.github/copilot-instructions.md): what the toolkit is, the module map, build and
   test commands (Windows first), the hard rules, and the checklist before you finish a change.
2. [docs/PROJECT-CONTEXT.md](docs/PROJECT-CONTEXT.md): the handbook for resuming work: history and decisions, what is
   proved and what is not, open owner decisions, configuration, data model, MCP contract and step-by-step recipes.
3. Path-specific rules: [.github/instructions/](.github/instructions/) (Java, Flyway migrations, MCP tools, Angular UI,
   scripts and the Windows bundle).

The rules that matter most: the toolkit does not translate code; client source leaves the network only for the
configured LLM endpoint; no test calls a live LLM; secrets only from environment variables; permissive licenses only;
no Docker; paths through `PathKeys`; every answer cited by member and line; pgvector optional; never edit an applied
Flyway migration.

Installing and operating the toolkit is [deploy/INSTALL.md](deploy/INSTALL.md), not these files.
