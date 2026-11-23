---
name: legacy-modernize
description: Understand and modernize a mainframe (COBOL, JCL, CICS, DB2) application using the Mainframe Modernization Toolkit's MCP tools (estate_map, get_program, get_source, job_flow, screen_flow, trace_field, business_rules, table_usage, gap_report and the rest). Use when the user asks what a legacy application, program, job, screen, transaction or field does; where to start on an unfamiliar estate; to list or explain business rules; to find what depends on what; to break the system into slices such as claim types or business functions; to plan the order of a migration; to compare legacy behavior with the modern code; or to translate a legacy program to Java and prove it behaves the same.
---

# Legacy modernization with the toolkit

The toolkit has parsed the legacy source into a database of facts, each tied to a file (a *member*) and a line. You reach it through the MCP server (usually named `legacy`). You are the one who reads, explains and plans: the toolkit gives you facts and source lines, and you turn them into explanations a person can act on.

## Rules (these override everything else)

1. **Every statement about the legacy system carries a citation**: `member:line` or `member:line_start-line_end`, taken from a tool result. No citation, no statement. If the tools don't show it, write "not found in the toolkit" and say what you looked for.
2. **Read the code before you explain it.** Names and comments mislead. To say what a program or paragraph does, call `get_source` for those lines and describe what the statements do.
3. **Facts, your reading, and guesses are three different things.** Mark them. A fact has a citation. Your reading is what you conclude from cited lines. A guess is labelled "unconfirmed" and listed under "Questions for an SME".
4. **Never invent a program, field, table, rule or link.** A name in your answer must have come from a tool result in this session or from a notes file you read in this session.
5. **Legacy behavior is the specification**, bugs included. Record a suspected bug under "Suspected defects"; don't "fix" it in the description or in translated code. An intentional difference is registered with `record_deviation` and takes effect only when a lead approves it.
6. **Write down what you learn.** Save every finished analysis to the notes folder (below) so the next session starts from it instead of re-reading the estate.
7. **Ask the user only what the code cannot answer**: business names and meaning, which slice matters first, what is in scope. Ask once, with what you found, and continue.

## Before anything else: check the connection

Call `inventory_summary`. If the tool is missing or the call fails, stop and tell the user:

- the MCP server isn't connected: `/mcp show legacy` lists its tools; `INSTALL.md` section 7 in the toolkit folder shows how to add it;
- or the toolkit isn't running: `start.cmd` in the toolkit folder, then `toolkit.cmd doctor`.

If `programs` is 0, the source hasn't been loaded: `toolkit.cmd discover` then `toolkit.cmd index`.

## The notes folder

Write notes as Markdown under `modernization-notes/` in the folder Copilot was started in (ask once if that folder is a place the user wants them; never write inside the toolkit folder or the legacy source). Layout:

```
modernization-notes/
  00-system-overview.md        what the system is, entry points, data stores, slices
  01-slices.md                 the slices (for example claim types), what each one uses, what they share
  02-dependencies.md           what must move together, and the proposed order
  programs/<PROGRAM>.md        one per program analysed
  slices/<slice>.md            one per slice: flow, rules, data, modern status, plan
  rules/<slice>-rules.md       the rule catalogue for a slice
  questions-for-sme.md         open questions, each with the citation that raised it
```

At the start of a session, read `00-system-overview.md` and `01-slices.md` if they exist and build on them. Templates for every file are in `templates.md` next to this file: read it before writing a note.

## Choose the playbook

| The user wants | Playbook |
|---|---|
| "What is this system?", "Where do I start?", "I loaded the programs, now what?" | A. Map the system |
| "What does program / job / screen / transaction X do?" | B. Explain one thing |
| "What are the claim types / products / functions, and what does each use?" | C. Find the slices |
| "What are the rules for X?", "Why does it do Y?" | D. Rule catalogue |
| "What depends on what?", "What breaks if I change X?", "In what order do we migrate?" | E. Dependencies and order |
| "How did we already modernize X?", "What is missing in the new system?" | F. Compare with the modern code |
| "Translate / migrate X", "Write tests that prove it" | G. Modernize one slice |

Finish the playbook you start. If a tool returns `next_cursor`, call it again with that `cursor` until it is null before you conclude anything from the list: a first page is not the whole answer.

## A. Map the system

Goal: a one-page overview a new team member can read in five minutes.

1. `estate_map` (no arguments) for the overview: counts, entry points, and whether the knowledge pass has run.
2. `estate_map {"area":"jobs"}`, `{"area":"transactions"}`, `{"area":"screens"}`, `{"area":"data"}`. On a large estate use `name_prefix` to take it in parts.
3. Group what you see by naming pattern (program prefixes, job prefixes, mapsets) and propose what each group is. These are proposals: say so.
4. Pick up to five entry points that look central (most steps, most programs, most data touched) and run playbook B on each, briefly.
5. Write `00-system-overview.md` from the template: what the system appears to do, batch entry points, online entry points, data stores with who reads and writes them, the proposed groups, what was not parsed (`parse_status`, `unresolved_dynamic_calls`, missing copybooks) and what that hides.
6. Tell the user what you found in ten lines or fewer, and ask which business names match the groups you proposed.

## B. Explain one thing

**A program**
1. `get_program {"name":"X","include":["callers","callees","tables","maps","jcl","data","summary","rules"]}`.
2. If `summary` is `not_available`, that only means the knowledge pass hasn't run. Carry on: you read the code yourself.
3. `get_source` for the member. For a long program, read the PROCEDURE DIVISION's main paragraph first, then each paragraph it performs, in order. Read all of it before describing it; follow `next_cursor`.
4. For each file or table it uses: `get_copybook` for the record layout, `table_usage` for who else touches it.
5. Write `programs/X.md` from the template: purpose in one sentence; how it is started; inputs and outputs; the processing steps in order with line ranges; every decision that changes the outcome (a rule) with its condition, action and lines; error and abend handling; what it calls and what calls it; suspected defects; questions for an SME.

**A job**: `job_flow {"job":"X"}` gives steps in order with datasets in and out. Explain each step's program with the program steps above (briefly), and show how data moves from step to step.

**A screen or transaction**: `screen_flow {"transaction":"X"}` or `{"map":"X"}` gives the fields and owning programs. Then explain the program, and say which field triggers which processing.

**A field**: `trace_field {"data_item":"X","program":"P","direction":"upstream"}` for where its value comes from, `downstream` for what it affects. Report the chain in order with a citation per hop.

## C. Find the slices (for example claim types)

A slice is a part of the system that can be understood, migrated and tested by itself: a claim type, a product, a business function. In a claims system the claim type is usually a code in a record, tested by `EVALUATE`, `IF` or 88-level condition names.

1. Ask the user for the business names of the slices and any codes they know (for example claim type codes), and which ones are already modernized. If they don't know the codes, find them: `search` for the words they use ("claim type", the type names) with `kinds: ["code","dictionary"]`.
2. Find the field that carries the slice code: `get_copybook` on the record layouts the search hit; look for the field and its 88-level values.
3. Find where the code decides the processing: `trace_field` downstream from that field, and `search` for its condition names. Each hit is a place where slices diverge.
4. For each slice, list what it uses: programs and paragraphs (from step 3), jobs and screens that reach them (`who_calls`, `job_flow {"program":...}`, `screen_flow`), tables and files (`get_program include tables`), copybooks.
5. Mark each item **specific** (used by one slice) or **shared** (used by several). Shared items are where the dependencies are.
6. Write `01-slices.md`: a table of slice × (code value, entry points, specific programs, shared programs, data, already modernized yes/no), then one section per slice. Create `slices/<slice>.md` for each from the template.
7. Tell the user which slices look simplest (few shared items, few rules) and which are entangled, with the evidence.

## D. Rule catalogue

1. `business_rules {"program":"X"}`. Rules with `status: reviewed` have been checked by a person: use them as written. `draft` rules are a model's reading: verify each against `get_source` before relying on it.
2. If there are no rules (the knowledge pass hasn't run for this program), extract them yourself: read the code (playbook B) and list every decision that changes an outcome, an amount, a status, or what is written.
3. Write each rule as: **ID** (`<PROGRAM>-R<nn>`), **When** (the condition, in business words and as coded), **Then** (the action), **Else**, **Data** (fields read and written), **Citation**, **Applies to** (which slices), **Confidence** (reviewed / verified against source / unconfirmed).
4. Rules the code implies but doesn't state (order of checks, defaults when a lookup fails, rounding, truncation, what happens at end of file) are rules too. List them.
5. Save to `rules/<slice>-rules.md`. Put anything only a business person can confirm in `questions-for-sme.md`.

## E. Dependencies and order

1. For each program in scope: `who_calls` and `calls_from` (depth 3), `get_program include ["tables","jcl","maps"]`.
2. For each shared table or file: `table_usage`, or the `data` area of `estate_map`, for every reader and writer.
3. For each shared field that crosses programs (COMMAREA, linkage, shared copybook): `trace_field`.
4. Build the dependency list: *A needs B because ...* with the citation. Three kinds: **calls** (program to program), **data** (one writes what another reads, including job step to job step), **shared code** (copybooks, common subroutines).
5. Propose the order: shared items that everything needs first, or kept in place behind an interface; then slices with the fewest inbound dependencies; entangled slices last, or together. Say what must move together and why.
6. For each step in the order, name the risk and how to detect a mistake (which outputs to compare).
7. Write `02-dependencies.md`.

## F. Compare with the modern code

Needs the modern repository indexed (`toolkit.cmd setup --target ...` or the `target:` block, then `discover`). If `get_modern_impl` returns `NOT_CONFIGURED`, tell the user that step is missing and stop this playbook.

1. `get_modern_impl {"program":"X"}`: confirmed links are authoritative; candidates are guesses, say so.
2. `gap_report {"scope":"X"}`: per program implemented / partial / missing / replaced_by_design; per rule covered / uncovered / changed.
3. `deviations {"program":"X"}`: approved intentional differences. Never report an approved deviation as a defect.
4. For a slice that is already modernized, read how it was done (the linked modern classes) and write down the pattern in `slices/<slice>.md` under "How it was modernized": structure, naming, how each kind of rule was carried over, how it was tested. The next slice follows this pattern.
5. Report uncovered and changed rules first: they are the work, or the bugs.

## G. Modernize one slice

Do this only after the slice has a note (C), a rule catalogue (D) and its dependencies (E).

1. Read `slices/<slice>.md`, its rules file, and the modernized reference slice's "How it was modernized" section if there is one.
2. `deviations` for every program in the slice, and `migration_status`.
3. For each COBOL construct you are unsure how to carry over (COMP-3, REDEFINES, OCCURS DEPENDING ON, CICS LINK, GDG): `explain_feature {"feature":"..."}`. Follow the repository's own instructions file (`CLAUDE.md` or `.github/copilot-instructions.md`) if the rulebook generated one.
4. Write the tests first, one per rule in the catalogue, named after the rule ID. If captures of the legacy program exist (`toolkit.cmd tests generate` produced fixtures), use them as the expected results.
5. Translate one program, or one paragraph group, at a time. Keep the rule IDs in comments or test names so each rule can be traced from legacy line to modern code.
6. When a decision is made (a mapping choice, an interface for a shared dependency), `record_decision {"program":"X","note":"..."}`.
7. Where the modern code must differ on purpose, `record_deviation` with the reason. It starts as a candidate for a lead to approve.
8. Finish with `gap_report` for the slice and report what is covered, what is not, and what is waiting for an SME.

## How to answer

- Lead with the answer in plain business language, then the evidence.
- Use tables for lists of programs, rules and data. Put `member:line` in its own column.
- Keep program and field names exactly as in the source.
- End with: what you saved and where, what is unconfirmed, and the one next step you recommend.

## When something is missing

| You see | It means | Do |
|---|---|---|
| `summary` / `rules` are `not_available` | The knowledge pass hasn't run | Read the code with `get_source` and explain it yourself. Mention once that `toolkit.cmd knowledge run` adds stored, reviewable summaries and rules. |
| `NOT_FOUND` with a hint | Wrong name | Use the name in the hint, or look it up with `estate_map` / `search`. |
| `AMBIGUOUS` | Several members share the name | Pass the member path from the hint. |
| A call target that isn't in the estate, a missing copybook | The export is incomplete | Say what is missing and what it hides. Don't guess its behavior. |
| `unresolved_dynamic_calls` > 0, a CALL through a variable | The target is decided at run time | `trace_field` upstream on the variable to find the values it can take; list them as candidates. |
| `search` returns nothing useful | Wrong words | Search for field and paragraph names you saw in the code, not business terms. |
| A result marked `candidate` or `draft` | Not reviewed | Use it as a lead, verify against source, and label it. |
