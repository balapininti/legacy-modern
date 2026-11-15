---
applyTo: "mcp/**"
---

# MCP tools, prompts and resources

The MCP contract is frozen: 19 tools, 4 prompts, 3 resource templates. Agents at clients depend on the names,
argument names and result fields. Add; do not rename or remove.

## How a tool is built

- A tool is a `ToolDef(name, description, inputSchema, writes, handler)`. Groups implement `ToolSet` as a Spring
  `@Component` (`FlowTools`, `ModernTools`, `PlanningTools`); `LegacyTools` is exposed by a `ToolSet` bean in
  `ToolkitMcpConfig`. `ToolkitMcpConfig.toolkitTools` registers the union.
- Input schemas are JSON strings built with `LegacyTools.schema("\"name\":{\"type\":\"string\"}", "name")` (properties,
  then required names).
- A handler returns a `List<?>` of items. It does not page, check roles, catch errors or audit: `ToolRuntime.call` does
  all of that for every tool, so no tool can forget it.
  - Roles: `admin` or `developer`, else `FORBIDDEN`. stdio callers are `<user>@stdio` with `developer`
    (`TOOLKIT_USER` or the OS user).
  - Paging: `Paginator`, at most 20,000 characters per result (`Paginator.MAX_CHARS`). Oversized items are split and
    rejoin exactly. Cursors are bound to the tool and arguments (`BAD_CURSOR` otherwise).
  - Errors: throw `new ToolError(code, message, hint)`. Codes in use: `NOT_FOUND`, `BAD_ARGUMENT`, `OUT_OF_RANGE`,
    `BAD_CURSOR`, `FORBIDDEN`, `AMBIGUOUS`, `NOT_CONFIGURED`, `NOT_TEXT`; any other exception becomes `INTERNAL`
    with no detail (logged on the server). The hint tells the agent what to do
    next (for a misspelled program, `NOT_FOUND` names the closest real one).
  - Audit: every call writes an `audit_log` row (user, tool, SHA-256 of the arguments, latency, outcome) and a
    `toolkit.tool.latency` metric.

## Rules for results

- Every item that states a fact about the source carries its citation: `member` (a `/` path from `PathKeys`) and `line`,
  or `line_start`/`line_end`. A tool that cannot cite does not state.
- Never put SQL, a stack trace or an exception class name in a result or error.
- Knowledge (summaries, rules) carries its review status; a candidate link is marked as a candidate, not authoritative.
- Write tools (`record_decision`, `record_deviation`) set `writes=true` and create nothing that takes effect without a
  lead's approval where the spec says so (a deviation starts as `candidate`).
- Arguments are data. Prompt arguments are reduced to one identifier before they enter a prompt sentence
  (`McpExtras`: `[^A-Za-z0-9@#$_-]` is stripped).
- Searches that use embeddings go through `retrieval` and work without pgvector (lexical only).

## What the contract tests enforce

`cli/src/test/java/com/modtoolkit/cli/McpContractIT` starts the real jar and drives it with the MCP Java client over
Streamable HTTP and stdio, on real CardDemo facts:

- every name in `ContractTools.EXPECTED` is listed, each with a description and an `object` input schema;
- answers carry `member` and `line` that match the source (for example `who_calls CBSTM03B` returns 13 callers,
  the first at `app/cbl/CBSTM03A.CBL` line 351, and `get_source` at that line contains `CALL 'CBSTM03B'`);
- `get_source` pages a long member with every page at or under 20,000 characters and the pages rejoin to the file;
- errors have exactly `code`, `message`, `hint`, with no `Exception`, stack frame or `SELECT`;
- no token or an unknown token gets 401; `sme-reviewer` gets `FORBIDDEN`; calls on both transports are audited;
- the 4 prompts and 3 resource templates exist by name, and a hostile prompt argument is sanitized;
- stdout of CLI commands holds only command output.

`ServerProfileIT` and `LauncherIT` (cli) assert exactly 19 tools; `scripts/windows-smoke.py` does too.

## Adding a tool (all of these, in one change)

1. A `ToolDef` in the right `ToolSet`, with a description an agent can act on.
2. `ContractTools.EXPECTED` and a provenance assertion in `McpContractIT` on real CardDemo data.
3. The counts: `ServerProfileIT` (`hasSize(19)`), `LauncherIT` (3 places), `scripts/windows-smoke.py` (3 places).
4. `RulebookLint.TOOLS` (rulebook), so generated instructions may name it; the rulebook templates if agents should call it.
5. `README.md`, `docs/PROJECT-CONTEXT.md` (tool list) and `deploy/INSTALL.md` if it changes what operators see.

Prompts and resource templates are in `McpExtras`; resources reuse tools through `ToolRuntime`, so they get the same
rules. `KnowledgePassIT` is the module's own IT; `PaginatorTest` covers paging.
