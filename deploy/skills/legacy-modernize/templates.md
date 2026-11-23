# Note templates

Copy the template, fill every section, and keep the headings. Write "none found" where a section is empty, and say what you looked for. Every row that states a fact has a citation (`member:line` or `member:start-end`).

## 00-system-overview.md

```markdown
# System overview

Last updated: <date> · Source: <client name from the toolkit> · Based on: estate_map, <n> programs read

## What the system does
<Three to six sentences in business language. Mark anything not confirmed by an SME as "unconfirmed".>

## Size and state
| Programs | Batch | Online | Jobs | Transactions | Screens | DB2 tables | Files |
|---|---|---|---|---|---|---|---|

Not parsed or incomplete: <members that failed, missing copybooks, calls to programs outside the export, dynamic calls>, and what that hides.
Knowledge pass: <not run / n of m programs summarized, k rules reviewed>.

## Batch entry points
| Job | What it does | Steps / programs | Reads | Writes | Citation |
|---|---|---|---|---|---|

## Online entry points
| Transaction | Program | Screen | What the user does there | Citation |
|---|---|---|---|---|

## Data stores
| Name | Kind | What it holds | Written by | Read by | Citation |
|---|---|---|---|---|---|

## Groups proposed
| Group (naming pattern) | Members | What it appears to be | Confirmed by |
|---|---|---|---|

## Questions for an SME
- <question> (raised by <citation>)
```

## 01-slices.md

```markdown
# Slices

A slice is a part that can be understood, migrated and tested by itself (for example a claim type).
The slice is decided by: <field name> in <copybook> (<citation>), values below.

| Slice | Code value | Entry points | Specific programs | Shared programs | Data | Modernized? |
|---|---|---|---|---|---|---|

## Shared by several slices
| Item | Kind (program / copybook / table / file) | Used by slices | Why it matters | Citation |
|---|---|---|---|---|

## Simplest first
<Ranking of slices by how entangled they are, with the reason for each.>
```

## slices/<slice>.md

```markdown
# Slice: <name>

Code value: <value> (<citation>) · Status: <not started / analysed / in progress / modernized>

## What it does
<Business description, three to six sentences.>

## Flow
1. <Entry point: job step or transaction> (<citation>)
2. <Program / paragraph and what happens> (<citation>)
3. ...

## Programs and paragraphs
| Program | Paragraphs for this slice | Shared with | Citation |
|---|---|---|---|

## Data
| Table / file | Read / written | Fields that matter | Citation |
|---|---|---|---|

## Rules
See `rules/<slice>-rules.md`. Count: <n> (<reviewed> reviewed, <verified> verified against source, <unconfirmed> unconfirmed).

## Depends on
| Needs | Kind (call / data / shared code) | Why | Citation |
|---|---|---|---|

## Modern code
| Legacy | Modern | Link status (confirmed / candidate) | Gaps |
|---|---|---|---|

## How it was modernized (fill in for a finished slice; the next slice follows it)
- Structure and naming:
- How each kind of rule was carried over:
- How shared dependencies were handled:
- How it was tested:

## Plan
| Step | What | Risk | How a mistake would show |
|---|---|---|---|

## Suspected defects (not fixed)
- <behavior> (<citation>)

## Questions for an SME
- <question> (raised by <citation>)
```

## rules/<slice>-rules.md

```markdown
# Rules: <slice>

| ID | When | Then | Else | Data read / written | Citation | Applies to | Confidence |
|---|---|---|---|---|---|---|---|
| <PROGRAM>-R01 | <condition in business words> (`<as coded>`) | <action> | <action or "nothing"> | <fields> | <member:start-end> | <slices> | reviewed / verified against source / unconfirmed |

## Implied rules (not stated as a condition)
| ID | Behavior | Citation |
|---|---|---|
| <PROGRAM>-I01 | <order of checks, default when a lookup fails, rounding, truncation, end-of-file handling> | <member:line> |
```

## programs/<PROGRAM>.md

```markdown
# <PROGRAM>

Member: <path> (<line range>) · Kind: <batch / online / subroutine> · Size: <loc> lines, complexity <n>

## Purpose
<One sentence.>

## How it is started
<Job and step, transaction, or callers> (<citation>)

## Inputs and outputs
| Name | Kind | Direction | Layout (copybook) | Citation |
|---|---|---|---|---|

## Processing, in order
1. <Step> (<member:start-end>)
2. ...

## Rules
<Table as in the rule catalogue, or a link to it.>

## Errors and abends
| Condition | What happens | Citation |
|---|---|---|

## Calls and is called by
| Direction | Program | How (CALL / LINK / XCTL / job step) | Citation |
|---|---|---|---|

## Suspected defects (not fixed)
## Questions for an SME
```

## 02-dependencies.md

```markdown
# Dependencies and order

## Dependencies
| A | needs B | Kind (call / data / shared code) | Why | Citation |
|---|---|---|---|---|

## Must move together
| Items | Why |
|---|---|

## Proposed order
| # | Slice or shared item | Why now | Risk | How a mistake would show |
|---|---|---|---|---|
```

## questions-for-sme.md

```markdown
# Questions for an SME

| # | Question | Why it matters | Raised by (citation) | Slice | Answer | Answered by / date |
|---|---|---|---|---|---|---|
```
