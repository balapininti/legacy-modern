# Final validation on the owner's 10 repositories

Commit `4e9b241` (later commits change documents only). Repositories are pinned in `scripts/fetch-corpora.sh`, fetched with `--all`. Commands: `scripts/validate-corpora.py`, `scripts/crosscheck-corpora.py`, `scripts/mcp-probe.py`. Raw reports go to `.cache/validation`.

## Discovery

| Repository | Lines read | COBOL | Copybook | JCL + PROC | BMS | PL/I | REXX | COBOL failed | Fallback parser | Static CALLs resolved | Missing copybooks |
|---|---|---|---|---|---|---|---|---|---|---|---|
| CardDemo | 59,241 | 44 | 65 | 62 | 21 | 0 | 1 | 0 | 1 | 100% | 0 |
| BankDemo | 77,454 | 82 | 83 | 41 | 14 | 29 | 0 | 0 | 18 | 98.81% | 0 |
| Bank-of-Z | 105,638 | 44 | 94 | 41 | 10 | 2 | 0 | 0 | 0 | no static calls | 0 |
| CBSA (`July2024Refresh`) | 112,631 | 31 | 35 | 109 | 10 | 0 | 2 | 0 | 0 | no static calls | 0 |
| GenApp | 43,108 | 31 | 13 | 29 | 1 | 0 | 22 | 0 | 0 | no static calls | 0 |
| DSF | 582,495 | 7 | 0 | 1 | 0 | 1,473 | 0 | 0 | 6 | 0%: targets are PL/I, not parsed | 0 |
| taxe-fonciere | 5,041 | 6 | 17 | 0 | 0 | 0 | 0 | 0 | 17 copybooks | 29.63%: 8 of 27 in the estate | 13 |
| BRICKS_TS | 42,442 | 26 | 14 | 0 | 0 | 7 | 33 | **2** | 3 | no static calls | 0 |
| cobol-unit-test | 13,387 | 25 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 57.14%: 4 of 7 in the estate | 1 |
| cobol-programming-course | 13,698 | 32 | 0 | 46 | 0 | 0 | 0 | 0 | 1 | 100% | 0 |

"Lines read" counts every text file discovery read, not just COBOL. PL/I, REXX and Assembler are inventoried only, as the spec asks: **DSF's 1,473 PL/I programs, about 99% of that system, get no facts or call graph.**

## Independent cross-check

A script counts statements in the raw source with regular expressions, using no toolkit parser, and compares line by line with what was stored.

| Repository | EXEC CICS raw / stored | EXEC SQL | CALL |
|---|---|---|---|
| CardDemo | 240 / 240 | 21 / 21 | 62 / 62 |
| BankDemo | 202 / 202 | 66 / 66 | 66 / 66 |
| Bank-of-Z | 749 / 749 | 91 / 91 | 44 / 44 |
| CBSA | 750 / 750 | 40 / 40 | 4 / 4 |
| GenApp | 500 / 500 | 37 / 37 | 0 / 0 |
| DSF | 0 / 0 | 0 / 0 | 3 / 3 |
| taxe-fonciere | 0 / 0 | 0 / 0 | 40 / 40 |
| BRICKS_TS (24 parsed) | 188 / 188 | 40 / 40 | 0 / 0 |
| cobol-unit-test | 2 / 2 | 0 / 0 | 21 / 21 |
| cobol-programming-course | 0 / 0 | 25 / 25 | 4 / 4 |

Only members that parsed are compared: BRICKS_TS's 2 failed programs are outside this table. PERFORM, data items and copybook layouts were not cross-checked this way on these repositories.

## MCP

A real stdio session against each repository's database: all 19 tools listed; `inventory_summary`, `who_calls`, `get_program`, `search`, and `table_usage`, `get_copybook`, `job_flow`, `screen_flow` wherever such data exists. No call raised an exception. A cited line was read back through `get_source` and checked against the source for each repository with a resolvable caller: 9 of 9 held. DSF had no caller to check.

## What does not work

| Repository | Item | Why |
|---|---|---|
| BRICKS_TS | `todo.cob`, `topx.cob` fail both parsers | `topx.cob` uses the reserved word `SEARCH` as a name, and `todo.cob` has a construct ProLeap rejects at line 545. Koopa does not rescue either. Both are reported with position and the job continues. No facts for these 2 |
| BRICKS_TS | 55 `.map` files are `unknown`; no screen flows | Its own map language, not BMS. No parser exists for it |
| taxe-fonciere | 13 copybooks missing; only 8 of 27 static CALLs resolved | Absent from the repository, and each is reported |
| taxe-fonciere | `search` for English words returns nothing | Its source is French. Semantic search needs embeddings, which were not run on this repository |
| Bank-of-Z | The 17 `.asm` files are `control`, not Assembler | They are IMS DBD macros, so this is a fair reading, but it differs from the owner's count |
| cobol-unit-test | 25 programs against the owner's 24 | Counted as found |
| DSF, BankDemo, BRICKS_TS, others | Members read only by Koopa | Koopa passes over what it does not recognize, so these facts may be incomplete. Each member is flagged |

Counts of Koopa-only members: 46 across all 10 repositories, mostly in BankDemo and taxe-fonciere. `ParserDifferentialIT` shows the two parsers agree on CardDemo's base programs, and no such comparison was run elsewhere.

## Bugs these repositories found

Each is fixed, with a test taken from the real case, except where noted.

- A PL/I include sharing a COBOL copybook's name replaced it in 5 BankDemo programs.
- Java, JavaScript, HTML, REXX and WSIM scripts were counted as PL/I: 78 in CBSA alone.
- READMEs quoting CICS definitions supplied false transactions: 6 false edges in CardDemo.
- `END-EXEC` crossing column 72 swallowed the statements after it, in BRICKS_TS.
- The fallback parser gave different results run to run on the same input. It now runs one member at a time; repeated 4 times each on DSF and BRICKS_TS with identical results. It costs speed on estates that need it heavily.
- Knowledge reruns missed the cache about half the time.
- A Jinja placeholder was reported as a missing PROC in Bank-of-Z.
- The first login after startup could fail, before the admin existed.
- **Not fixed:** DDL inside Jinja templates (`.j2`) is unread, so Bank-of-Z reports `db2_tables_with_ddl: 0`.

## Scale

1,681,618 synthetic lines, 1,892 members, 0 failed, 80 s on 8 threads with other work running, 1.04 GB peak process memory. The target is 1M lines in 2 hours. Synthetic code is regular and simple, so real code will be slower: DSF's real 582,495 lines took 20 s, but nearly all of those are inventoried only.
