# M3b evidence

`McpContractIT.h_...` runs the real jar, MCP and REST as separate processes, on CardDemo plus `testdata/target-sample` copied with a real git history:

- Java (JavaParser, under its Apache option) and Angular (TypeScript compiler API) extract classes, entities and their tables, DTOs, endpoints, batch jobs, components, services, routes and component-to-endpoint calls. Searchable with `kinds:["modern"]`.
- Candidates from names (COACTVWC cited in a Javadoc, 0.7) and commit messages (CBACT04C in a commit subject, 0.8, with the commit in its evidence). All start as candidates and `get_modern_impl` marks them not authoritative.
- A lead confirms through REST: `get_modern_impl` turns authoritative, `migration_status` moves to translated with the target ref, `gap_report` moves from missing to implemented, and to partial when an uncovered rule exists.
- Developers get 403 on confirm and approve, anonymous gets 401, unknown ids 404.
- Re-running discovery keeps a rejected link rejected and a confirmed one confirmed.
- `record_deviation` creates a candidate with no effect until a lead approves; `deviations` then returns it with the approver and comparison rule.

Still open: "an approved deviation suppresses a known difference in a generated test" needs the generator, so it is proved in M7. LLM link matching arrives in M4. The CBSA repository from the owner's list exercises real-world Java in the final validation.

Defect found: every process took a 40-connection pool, so three exhausted Postgres. Pools are now sized per process.
