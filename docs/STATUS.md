# Status

Date: 2026-11-02. On branch `main`, local only: nothing has been pushed or published.

All milestones M0 to M9 are built. `mvn clean verify` passes 179 tests. All 12 browser tests pass. The license gate passes on 214 Java dependencies and 22 shipped UI packages.

## Acceptance

| Milestone | Result | Evidence |
|---|---|---|
| M0 Skeleton | Met | [M0](evidence/M0.md) |
| M1 Discovery | Met. 100% of CardDemo parsed. 1M lines target beaten on synthetic code | [M1](evidence/M1.md) |
| M2 MCP | **Half met.** Claude Code proved. VS Code Copilot not available here | [M2](evidence/M2.md) |
| M3 JCL, BMS, DDL | Met | [M3](evidence/M3.md) |
| M3b Existing code | Met on a sample repository | [M3b](evidence/M3b.md) |
| M4 Knowledge | Met on recordings. Real run covered all programs, not the jobs and screens | [M4](evidence/M4.md) |
| M5 Retrieval | Met with qualifications: 0.84 on the spec's 50 after tuning on them, 0.80 on first contact with a held-out set | [M5](evidence/M5.md) |
| M6 Rulebook | **Half met.** Real Claude Code translation followed the generated file. Copilot not tested | [M6](evidence/M6.md) |
| M7 Verification | **Met through the spec's no-LPAR path.** GnuCOBOL capture, not a mainframe | [M7](evidence/M7.md) |
| M8 Web UI | Met in a real browser. Real SSO not tested | [M8](evidence/M8.md) |
| M9 Packaging | Met: 87 s from the runbook to a first MCP answer. Helm install not run | [M9](evidence/M9.md) |
| Owner's 10 repositories | 2 COBOL programs of 321 fail to parse, both in BRICKS_TS | [Final validation](evidence/final-validation.md) |

## Never exercised

- GitHub Copilot in VS Code.
- A real LPAR: Zowe, FTP JES, or compiling TKDRIVER under Enterprise COBOL.
- A real identity provider. JWTs were tested against a local issuer.
- GitHub Actions: `ci.yml` has never run.
- Azure OpenAI, Bedrock, OpenAI and Anthropic as chat providers. They are constructed, not called. Only local Ollama was really used.
- Helm install on a cluster.
- A hosted embedding model.

## Known gaps

- PL/I, REXX and Assembler are inventoried only, per the spec.
- BRICKS_TS's own `.map` format and DDL inside Jinja templates are unread.
- Koopa-only members may have incomplete facts. They are flagged.
- The captures diff view in the UI does not exist.
- Nobody has reviewed real model output for accuracy at scale.
- The full real knowledge run stopped at about half of its 270 units. Rerun `knowledge run` to resume.
- Without CSD definitions, programs started only by a transaction appear as dead-code candidates.

## For the owner to decide

- ADR 0003: Jakarta annotation, under EPL, is shipped as an exception to the license rule.
- ADR 0002: LGPL cb2xml and JRecord were replaced by our own layout code.
- 81 build-only npm packages are ISC or BlueOak, which is outside the "Apache, MIT or BSD" wording, though they do not ship.
- `KnowledgeOptions` adds a token budget and per-token prices that the spec does not name.

## A real finding in CardDemo

`CBACT04C.cbl`, line 220: the `ELSE` that posts accrued interest is unreachable, because the loop at line 188 ends first. The last account's interest is written as a transaction but never posted to the account. The toolkit's GnuCOBOL capture confirmed this behavior.
