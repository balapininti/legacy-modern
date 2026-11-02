---
name: cobol-to-java-mapping
description: How COBOL data types and statements map to Java here. Use when translating data definitions, arithmetic or control flow.
---

# COBOL to Java mapping

Get the real layout with get_copybook first; these rules say how to carry it across.

<#list rules as r>
<#if r.skills?seq_contains("cobol-to-java-mapping")>
## ${r.cobol}

Becomes ${r.java}. ${r.guidance}

Example: `${r.example}`

</#if>
</#list>
