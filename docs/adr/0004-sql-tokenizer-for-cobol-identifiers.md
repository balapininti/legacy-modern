# 0004: SQL tokenizer instead of a text search configuration

Status: accepted. Date: 2026-11-01.

The spec asks for "a parser configuration that keeps hyphenated COBOL identifiers whole". A configuration can only remap the tokens of Postgres' default parser, and that parser splits `1000-MAIN-PARA`, the common form of a paragraph name, into a number and a hyphenated word. A failing test showed this. A custom parser means a C extension, which would break "same images, boring libraries".

Decision: `cobol_tsvector(text)` and `cobol_tsquery(text)` in SQL tokenize on `[a-z0-9]+([-_][a-z0-9]+)*`, keeping tsvector, GIN and ranking. No stemming: semantic search covers natural-language variants and pg_trgm covers partial identifiers.
