Describe BMS map ${unit} and how its programs use it.

Return JSON matching this schema:
${schema}

Rules: map is the map name. fields gives each named field with a human label, taken from the nearby initial text where there is one. validations come only from the program source in the context, citing that program and line. next_maps are maps or transactions reached from this screen, again only where the context shows it.

<context>
${context}
</context>

<source>
${source}
</source>
