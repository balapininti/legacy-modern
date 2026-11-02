---
description: Write characterization tests that prove a Java implementation behaves like the mainframe
mode: agent
---

Write tests for ${r"${input:program}"}.

1. Call `migration_status` for fixture counts and `deviations` for approved differences.
2. Prefer the toolkit's generated parameterized tests: `toolkit tests generate`. Do not change expected values to make a test pass.
3. A difference the tests find that is not registered is a bug or a candidate deviation. Propose it with `record_deviation`; a lead decides.
4. Add a test per rule in `business_rules` that the fixtures do not reach.
