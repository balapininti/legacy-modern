# ${program}

Member: `${member}` lines ${lineStart}-${lineEnd}. Kind: ${kind}. Stage: ${stage}.

## Summary
<#if summary?has_content>
${summary.purpose}

- Inputs: ${summary.inputs?join(", ")}
- Outputs: ${summary.outputs?join(", ")}
- Side effects: ${summary.sideEffects?join(", ")}
- Error handling: ${summary.errorHandling}

Status: ${summary.status}.
<#else>
The knowledge pass has not summarized this program yet. Use the graph tools.
</#if>

## Connections
- Called by: ${callers?join(", ")}
- Calls: ${callees?join(", ")}
- Tables: ${tables?join(", ")}
- Jobs: ${jobs?join(", ")}

## Business rules
<#if rules?has_content>
<#list rules as r>
- **${r.id}** (lines ${r.lineStart}-${r.lineEnd}, ${r.status}): ${r.statement}
</#list>
<#else>
None recorded.
</#if>

## Migration notes
<#if notes?has_content>
<#list notes as n>
- **${n.feature}** (lines ${n.lineStart}-${n.lineEnd}): ${n.guidance}
</#list>
<#else>
None recorded.
</#if>

## Registered deviations: never revert these
<#if deviations?has_content>
<#list deviations as d>
- ${d}
</#list>
<#else>
None registered.
</#if>
