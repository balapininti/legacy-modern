# Using the toolkit: from "the programs are loaded" to a modernization plan

`INSTALL.md` ends when the toolkit answers its first question. This guide starts there. It is written for GitHub
Copilot CLI on Windows with a Claude Sonnet model, and for a team modernizing a large system it does not fully
understand, for example a claims system with many claim types, shared rules, and parts that depend on each other.

## Why the output looked like nothing

After `toolkit.cmd discover` you get a block of counts: members, programs, jobs, edges. That is a health check of the
load, not an explanation of your system. Nothing is wrong.

The toolkit is a database of facts about your code (what calls what, which job runs which program, which program
reads which table, every field and where it moves), each tied to a file and a line. It does not explain anything by
itself. **The explaining is done by an AI agent that asks the toolkit questions.** You get meaning when you:

1. connect Copilot CLI to the toolkit (section 1),
2. install the `legacy-modernize` skill, which tells the agent which questions to ask and in what order (section 1),
3. ask in plain language, following the flow in section 2.

Two things make the answers richer, and neither is needed to start:

- **The knowledge pass** (`INSTALL.md` section 10) stores a summary and a list of rules for every program, which your
  experts then review in the web UI. Without it, `get_program` reports summaries and rules as `not_available`, and the
  agent reads the code and explains it on the spot instead. The explanation is just as cited, but it is not stored or
  reviewed.
- **The modern repository** (`INSTALL.md` section 11) lets the toolkit compare what is already modernized with the
  legacy code.

## 1. One-time setup for Copilot CLI

Do `INSTALL.md` sections 1 to 6 first (setup, start, discover, index, token). Then, in Command Prompt:

**Connect the MCP server.** Use the machine name the certificate was made for, or `localhost` on the toolkit machine.

```bat
copilot mcp add --transport http --header "Authorization: Bearer <token>" legacy https://<host>:8443/mcp
```

Or, inside Copilot CLI, type `/mcp add`, choose HTTP, give the URL `https://<host>:8443/mcp` and the headers
`{"Authorization": "Bearer <token>"}`. Either way the server is saved in `%USERPROFILE%\.copilot\mcp-config.json`.

**Trust the certificate** if you use the self-signed one (`INSTALL.md` section 7). Copilot CLI runs on Node.js. If it
reports a certificate error when it connects, set this once in a new Command Prompt and start Copilot CLI again:

```bat
setx NODE_EXTRA_CA_CERTS "C:\certs\toolkit.crt"
```

**Install the skill.** From the toolkit folder:

```bat
xcopy /E /I /Y skills\legacy-modernize "%USERPROFILE%\.copilot\skills\legacy-modernize"
```

**Check, inside Copilot CLI:**

| Type | You should see |
|---|---|
| `/mcp show legacy` | The server connected, with 20 tools, `estate_map` among them |
| `/skills list` | `legacy-modernize` (run `/skills reload` if you installed it while Copilot was open) |
| `/model` | Pick a Claude Sonnet model |

Start Copilot CLI in the folder where you want the notes to be written: your modern repository, or an empty working
folder. The skill writes everything it learns to `modernization-notes\` there.

## 2. The flow

```
 1 Map the system ──► 2 Find the slices ──► 3 Understand one slice ──► 4 Dependencies ──► 5 Modernize ──► 6 Prove it
   (what is here?)      (claim types)         (flow + rules)             and order          one slice       matches
        │                    │                      │                        │                  │               │
        ▼                    ▼                      ▼                        ▼                  ▼               ▼
 00-system-overview.md  01-slices.md   slices\<type>.md, rules\<type>-rules.md  02-dependencies.md   code + tests   gap report
```

Steps 1, 2 and 4 are done once for the system. Steps 3, 5 and 6 are repeated for each slice (each claim type). Every
step writes a note, so the next session, or the next person, starts from what is already known.

Each step below gives the prompt to type, what the agent does, and what you get. Use your own names where the
examples say `<...>`.

### Step 1: Map the system (about an hour)

> Use the legacy-modernize skill. Map this system: what is here and where should I start?

The agent lists the jobs, transactions, screens, programs and data stores (`estate_map`), groups them by naming
pattern, reads a few central entry points, and writes `modernization-notes\00-system-overview.md`: what the system
appears to do, the batch and online entry points, the data stores with who reads and writes them, and what was not
parsed.

It will ask you which business names match the groups it found. Answer what you know. "I don't know" is a useful
answer: it goes into `questions-for-sme.md`.

**You are done when** you can read the overview and point to where a claim enters the system and where it ends up.

### Step 2: Find the slices (half a day)

> Use the legacy-modernize skill. This is a claims processing system. Find the claim types: which field decides the
> claim type, what values it has, and which programs, jobs, screens and tables each claim type uses. We have already
> modernized <type A> and <type B>.

The agent finds the field that carries the claim type, its values, and every place the code branches on it. It then
lists what each claim type uses and marks each item as **specific** to one claim type or **shared** by several. It
writes `01-slices.md` and one `slices\<type>.md` per claim type.

**You are done when** you have a table of claim types against programs and data, and you can see which claim types are
nearly independent and which are tangled together through shared programs, copybooks and tables.

### Step 3: Understand one slice (a day or two per claim type)

Start with a claim type you have already modernized. You know the right answer for it, so you can judge the agent's
output, and it becomes the pattern for the rest.

> Use the legacy-modernize skill. Explain how claim type <X> is processed end to end, from the entry point to the
> last write, and save it to its slice note.

> Use the legacy-modernize skill. Build the rule catalogue for claim type <X>: every condition that changes an
> outcome, an amount or a status, with its citation.

> What does program <PROGRAM> do? Read the code, don't rely on names.

> Where does the field <FIELD> in <PROGRAM> get its value, and what does it affect?

You get the slice's flow, its programs and data, and `rules\<type>-rules.md`: one row per rule with the condition,
the action, the fields, the file and lines, and how sure the agent is. Anything only a business person can settle is
added to `questions-for-sme.md`.

**Check a sample.** Open the cited file and lines for five rules. If they are right, trust the rest more; if one is
wrong, tell the agent which and why, and have it re-read that program.

**You are done when** an SME has read the slice note and rule catalogue and answered the questions.

### Step 4: Dependencies and order (a day)

> Use the legacy-modernize skill. For the claim types in 01-slices.md, work out what depends on what (calls, shared
> data, shared copybooks and subroutines) and propose the order to migrate them. Say what has to move together.

> What breaks if I change <PROGRAM or TABLE>?

The agent traces callers and callees, every reader and writer of each shared table and file, and shared fields that
cross programs. It writes `02-dependencies.md`: each dependency with its reason and citation, what must move together,
and a proposed order with the risk of each step.

**You are done when** the team agrees the order, or changes it knowing what each change costs.

### Step 5: Modernize one slice

This needs the modern repository connected (`INSTALL.md` section 11), so the toolkit can see what already exists.

> Use the legacy-modernize skill. Compare claim type <A> (already modernized) with the legacy code: what is
> implemented, what is missing, what was changed on purpose. Write down how it was modernized so the next claim type
> follows the same pattern.

> Use the legacy-modernize skill. Modernize claim type <C> following the pattern of <A>. Write the tests first, one
> per rule in its rule catalogue.

The agent reads the slice note, the rules and the reference pattern, checks approved deviations, writes tests named
after the rule IDs, and then translates one program at a time. Decisions go into the toolkit's migration log
(`record_decision`), and intended differences are proposed for a lead to approve (`record_deviation`).

For the modern code itself, also use the instruction files the rulebook generates for your repository
(`INSTALL.md` section 11), and your own coding skills (for example `springboot-feature` and `angular-feature`).

### Step 6: Prove it matches

> Use the legacy-modernize skill. Run the gap report for claim type <C> and tell me what is covered, what is not, and
> what is waiting for an SME.

If you can export input and output datasets from a real run of the legacy program, the toolkit turns them into tests
that the new code must pass (`toolkit.cmd capture import`, `toolkit.cmd tests generate`, `INSTALL.md` section 11).
That is the strongest proof available: the same inputs give the same outputs.

**You are done when** every rule in the catalogue is covered, changed on purpose with an approved deviation, or listed
as an open question with an owner.

### What a good answer looks like

This is from a trial on the public CardDemo sample (a credit-card system, not claims), with a Claude Sonnet model
that had never seen it, the skill, and no knowledge pass. The prompt was "What does the job that calculates interest
do, end to end? Explain its program and build its rule catalogue." Part of the rule catalogue it wrote:

| ID | When | Then | Citation |
|---|---|---|---|
| CBACT04C-R03 | The rate record is not found for the account's group (`DISCGRP-STATUS = '23'`) | Retry with group `DEFAULT` | `app/cbl/CBACT04C.cbl:436-446` |
| CBACT04C-R05 | For each category balance with a non-zero rate | Monthly interest = balance × rate / 1200, added to the account's total | `app/cbl/CBACT04C.cbl:464-467` |
| CBACT04C-R07 | The account number changes | Add total interest to the balance, zero the cycle credit and debit, rewrite the account | `app/cbl/CBACT04C.cbl:350-356` |
| CBACT04C-I02 | (implied) | Interest is truncated to 2 decimals, not rounded | `app/cbl/CBACT04C.cbl:464-465` |
| CBACT04C-I03 | (implied) | The last account in the file is never updated: the end-of-file branch cannot be reached | `app/cbl/CBACT04C.cbl:188-222` |

The last row is a real defect in that program. That is the kind of output to expect: rules in business words, the
coded condition, the lines to check, and behavior nobody wrote down.

## 3. Prompts for everyday questions

| You want to know | Ask |
|---|---|
| What a program does | "What does `<PROGRAM>` do? Read the code and cite the lines." |
| What a job does | "Walk me through job `<JOB>` step by step: programs, inputs, outputs." |
| What a screen does | "What happens when a user submits screen `<MAP>` (transaction `<TRAN>`)?" |
| Why a value is what it is | "Trace `<FIELD>` in `<PROGRAM>` upstream. Where does it come from?" |
| Who uses a table | "Which programs read and write table `<TABLE>`, and in which claim types?" |
| Impact of a change | "What depends on `<PROGRAM>`? What breaks if its output changes?" |
| Where a rule lives | "Where is the rule that `<business description>`? Show the code." |
| Whether something is already done | "Is `<PROGRAM>` implemented in the modern code? What is missing?" |
| How to carry over a COBOL feature | "How should `COMP-3` / `REDEFINES` / `OCCURS DEPENDING ON` be mapped in Java here?" |
| What is still open | "List the open questions for an SME for claim type `<X>`." |

Say "use the legacy-modernize skill" in the first prompt of a session. After that the agent keeps using it.

## 4. Judging the answers

- **Every fact has a file and line.** Open a few. An answer without citations is not from the toolkit; ask again with
  "cite the lines".
- **"Not found" is an answer.** It usually means part of the source is missing from the export (a called program, a
  copybook) or a name is decided at run time. The overview lists what is missing.
- **Draft and candidate mean unreviewed.** Rules marked `draft` and links marked `candidate` are leads to check, not
  facts.
- **Legacy behavior is the specification, bugs included.** The agent records suspected defects; it does not correct
  them in the description or the translation. A difference you want is registered as a deviation and approved by a
  lead.

## 5. Making the output richer

| Do | You get | How |
|---|---|---|
| Run the knowledge pass | A stored summary, rule list and migration note per program, searchable, instead of the agent re-reading code each time | `INSTALL.md` section 10 |
| Review in the web UI | Rules your experts have confirmed rank first and are marked `reviewed`; rejected ones disappear | `https://<host>:8443`, Review page |
| Connect the modern repository | Legacy-to-modern links, the gap report, and the pattern of what you already modernized | `INSTALL.md` section 11, then the Link confirmation page |
| Include CSD and scheduler definitions in the export | Transactions and job schedules, so online programs are not reported as having no caller | Re-export, then `discover` and `index` again |
| Import captures of real runs | Tests that prove the new code gives the same outputs | `INSTALL.md` section 11 |

The web UI is for people; Copilot CLI is for asking. The pages you will use most: **Inventory** and **Programs** (what
was loaded), **Graph** (who calls whom), **Search**, **Review** (confirm or reject summaries and rules), **Link
confirmation**, **Gaps**, **Deviations** and **Tracker** (the status of each program).

## 6. If it does not work

| You see | Do |
|---|---|
| Copilot says it has no tool for that, or `/mcp show legacy` fails | The server is not connected or not running: `start.cmd`, `toolkit.cmd doctor`, then section 1 |
| A certificate error | Trust `data\tls\toolkit.crt` (`INSTALL.md` section 7) and set `NODE_EXTRA_CA_CERTS` (section 1) |
| 401 or `FORBIDDEN` | The token is wrong, revoked, or has the `sme-reviewer` role: `toolkit.cmd token --user <you> --role developer` |
| 19 tools and no `estate_map` | The toolkit is an older version: upgrade (`INSTALL.md` section 13) |
| The agent answers from general knowledge, without citations | Start the prompt with "Use the legacy-modernize skill" and check `/skills list` |
| "summary not_available" | Expected before the knowledge pass. The agent reads the code instead |
| Answers stop part way through a long list | Ask "continue: follow the cursor until the list is complete" |
| A program you know exists is "not found" | Check `data\discovery-report.json` for parse failures, and the Inventory page for members of type `unknown` |
