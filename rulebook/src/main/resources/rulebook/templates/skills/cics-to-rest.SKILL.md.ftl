---
name: cics-to-rest
description: How CICS transactions and BMS screens map to REST and Angular. Use when translating online programs.
---

# CICS to REST

Find the screen with screen_flow and its callers with who_calls first.

<#list rules as r>
<#if r.skills?seq_contains("cics-to-rest")>
## ${r.cobol}

Becomes ${r.java}. ${r.guidance}

Example: `${r.example}`

</#if>
</#list>
