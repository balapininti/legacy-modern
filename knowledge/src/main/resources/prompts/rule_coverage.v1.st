Compare the business rules of a legacy program against its modern code.

Return JSON matching this schema:
${schema}

Rules: one result per rule_id in the context. covered means the modern code does the same thing. changed means it does something related but different. uncovered means you cannot find it. modern_unit_id is the listed unit holding it, or null when uncovered. reason is one short sentence.

<context>
${context}
</context>

<source>
${source}
</source>
