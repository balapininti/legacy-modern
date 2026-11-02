---
description: Translate one COBOL program to Java, using the toolkit's facts and this repository's conventions
mode: agent
---

Translate COBOL program ${r"${input:program}"} to Java under `${packageRoot}`.

1. Call `get_program` with include `source`, `summary`, `rules`, `data`, `callers`, `callees`, `tables`, `migration_note`, and read `docs/migration-briefs/${r"${input:program}"}.md`.
2. Call `deviations` and `get_modern_impl`. Extend what exists; never revert a deviation.
3. Call `get_copybook` for each record the program uses, with `expanded_for` set to the program.
4. Implement every rule from `business_rules`. Comment each with its rule id.
5. Add tests from captured fixtures if `migration_status` shows any.
6. `record_decision` for anything you had to choose.
