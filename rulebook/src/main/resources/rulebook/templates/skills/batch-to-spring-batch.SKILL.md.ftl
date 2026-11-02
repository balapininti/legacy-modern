---
name: batch-to-spring-batch
description: How JCL jobs, steps, SORT and GDGs map to Spring Batch. Use when translating batch programs.
---

# Batch to Spring Batch

Get the step order and datasets with job_flow first.

<#list rules as r>
<#if r.skills?seq_contains("batch-to-spring-batch")>
## ${r.cobol}

Becomes ${r.java}. ${r.guidance}

Example: `${r.example}`

</#if>
</#list>
