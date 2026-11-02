Explain the data items of copybook ${unit} for someone who has never seen this system.

Return JSON matching this schema:
${schema}

Rules: one entry per elementary item or meaningful group. data_item is the exact COBOL name. meaning is one plain sentence. domain_values come from 88-levels and from the context; never invent them. units such as USD or days only when evident. pii is true for names, addresses, national ids, card numbers, account numbers, dates of birth. line is the line the item is defined on.

<context>
${context}
</context>

<source>
${source}
</source>
