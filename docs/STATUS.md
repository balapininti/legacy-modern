# Status

Date: 2026-11-15. Branch `main` at commit 9d16228, pushed to the public repository `github.com/balapininti/legacy-modern`.

2026-11-14: container tooling was removed from the repository: Compose, Helm, Jib images and Testcontainers. The client install is a Windows zip with a portable PostgreSQL 17, and tests run on a native PostgreSQL. See [ADR 0008](adr/0008-no-docker-windows-bundle.md). The M9 result below was measured on the Compose install that no longer exists; the Windows bundle row replaces it.

2026-11-15: both workflows pass on `main` at 9d16228. `ci.yml` (Linux): `mvn verify` passes 248 tests with none skipped, all 12 browser tests pass, and the license gate passes on 216 Java dependencies and 22 shipped UI packages. `windows.yml`: pgvector builds, the bundle builds, its smoke test passes 20 of 20 checks, and `mvnw verify` on Windows passes 248 tests, 0 failed, 1 skipped. The zip is published as the pre-release [`v0.1.0-windows-preview1`](https://github.com/balapininti/legacy-modern/releases/tag/v0.1.0-windows-preview1) (213.7 MB; the version is still `0.1.0-SNAPSHOT`).

For resuming work, read [PROJECT-CONTEXT.md](PROJECT-CONTEXT.md).

## Acceptance

| Milestone | Result | Evidence |
|---|---|---|
| M0 Skeleton | Met | [M0](evidence/M0.md) |
| M1 Discovery | Met. 100% of CardDemo parsed. 1M lines target beaten on synthetic code | [M1](evidence/M1.md) |
| M2 MCP | **Half met.** Claude Code proved. VS Code Copilot not tried | [M2](evidence/M2.md) |
| M3 JCL, BMS, DDL | Met | [M3](evidence/M3.md) |
| M3b Existing code | Met on a sample repository | [M3b](evidence/M3b.md) |
| M4 Knowledge | Met on recordings. Real run covered all programs, not the jobs and screens | [M4](evidence/M4.md) |
| M5 Retrieval | Met with qualifications: 0.84 on the spec's 50 after tuning on them, 0.80 on first contact with a held-out set | [M5](evidence/M5.md) |
| M6 Rulebook | **Half met.** Real Claude Code translation followed the generated file. Copilot not tested | [M6](evidence/M6.md) |
| M7 Verification | **Met through the spec's no-LPAR path.** GnuCOBOL capture, not a mainframe | [M7](evidence/M7.md) |
| M8 Web UI | Met in a real browser. Real SSO not tested | [M8](evidence/M8.md) |
| M9 Packaging | Met on the removed Compose install: 87 s from the runbook to a first MCP answer. Not re-measured on the Windows bundle | [M9](evidence/M9.md) |
| Windows bundle | Met on GitHub's `windows-latest`: builds, sets up, serves cited MCP answers over HTTPS, runs as a service, deletes cleanly | [Windows](evidence/windows.md) |
| Owner's 10 repositories | 2 COBOL programs of 328 fail to parse, both in BRICKS_TS | [Final validation](evidence/final-validation.md) |

## Never exercised

- GitHub Copilot in VS Code, and the Copilot coding agent.
- A real LPAR: Zowe, FTP JES, or compiling TKDRIVER under Enterprise COBOL.
- A real identity provider. JWTs were tested against a local issuer.
- Azure OpenAI, Bedrock, OpenAI and Anthropic as chat providers. They are constructed, not called. Only local Ollama was really used.
- A hosted embedding model.
- The Windows bundle as a standard (non-administrator) user, on a machine that never had the Visual C++ runtime (simulated by hiding the System32 copies), on Windows 10 or Server 2019, and with a person trusting the certificate through `certutil -user`. The CI runner is a current Windows Server image and an administrator.
- The INSTALL.md first-answer timing on the Windows bundle by someone who reads nothing else.
- The UI e2e and the bash development scripts (`dev-pg.sh`, `ui/e2e/*.sh`) on Windows. They run on Linux CI.

## Known gaps

- PL/I, REXX and Assembler are inventoried only, per the spec.
- BRICKS_TS's own `.map` format and DDL inside Jinja templates are unread.
- Koopa-only members may have incomplete facts. They are flagged.
- The captures diff view in the UI does not exist.
- Nobody has reviewed real model output for accuracy at scale.
- The full real knowledge run stopped at about half of its 270 units. Rerun `knowledge run` to resume.
- Without CSD definitions, programs started only by a transaction appear as dead-code candidates.
- Developing on Windows: `scripts/license-check.py` cannot start `mvn.cmd`, `ui` `npm run regen-api` is expected to fail at its generator step, and the test PostgreSQL needs the Visual C++ runtime on the machine. Workarounds are in [PROJECT-CONTEXT.md](PROJECT-CONTEXT.md#developing-on-windows).

## For the owner to decide

- ADR 0008: the Temurin JRE (GPL-2.0 with the Classpath Exception) and EDB's `libintl-9.dll` and `libiconv-2.dll` (LGPL) ship in the Windows bundle as exceptions to the license rule.
- ADR 0003: Jakarta annotation, under EPL, is shipped as an exception to the license rule.
- ADR 0002: LGPL cb2xml and JRecord were replaced by our own layout code.
- 81 build-only npm packages are outside the "Apache, MIT or BSD" wording (61 ISC, 16 BlueOak, and one each of Python-2.0, CC-BY-4.0, CC-BY-3.0 and CC0-1.0), though they do not ship.
- `KnowledgeOptions` adds a token budget and per-token prices that the spec does not name.

## A real finding in CardDemo

`CBACT04C.cbl`, lines 219-220: the `ELSE` that posts accrued interest is unreachable at end of file, because the loop at line 188 ends first. The last account's interest is written as a transaction but never posted to the account. The toolkit's GnuCOBOL capture confirmed this behavior.
