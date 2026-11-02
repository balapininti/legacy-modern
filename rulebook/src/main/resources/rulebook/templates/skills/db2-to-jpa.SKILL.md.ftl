---
name: db2-to-jpa
description: How DB2 SQL and VSAM files map to persistence here. Use when translating data access.
---

# DB2 to JPA

Get the statements with table_usage first.

<#list rules as r>
<#if r.skills?seq_contains("db2-to-jpa")>
## ${r.cobol}

Becomes ${r.java}. ${r.guidance}

Example: `${r.example}`

</#if>
</#list>
