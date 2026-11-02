Extract the business rules from COBOL program ${unit}. A business rule is a decision or calculation a business person would recognize: eligibility, validation, limits, interest, status transitions. Skip file handling, abend plumbing, and screen attribute housekeeping.

Return JSON matching this schema:
${schema}

Rules: rule_id is ${unit}-R1, ${unit}-R2 and so on. statement is one plain English sentence. condition and action are short. paragraph must be a paragraph name from the source. line_start and line_end cover the code implementing the rule. data_items lists the COBOL names involved. confidence is 0 to 1. If there are no such rules, return an empty rules array.

<context>
${context}
</context>

<source>
${source}
</source>
