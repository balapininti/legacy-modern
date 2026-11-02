---
description: Translate a JCL job and its programs to a Spring Batch job with steps mapped 1:1
mode: agent
---

Translate JCL job ${r"${input:job}"} to Spring Batch under `${packageRoot}`.

1. Call `job_flow` for the order of steps, datasets, conditions and restart points.
2. Create one Step per JCL step, keeping the step names.
3. Turn COND and IF into a decider. Test both directions.
4. Translate each step's program as in `translate-program.prompt.md`.
5. Map each GDG through the versioned file naming rule.
