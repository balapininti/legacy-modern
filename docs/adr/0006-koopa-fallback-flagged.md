# 0006: Koopa as a flagged fallback, ProLeap's grammar patched

Status: accepted. Date: 2026-11-02.

Running the owner's 10 repositories found valid COBOL that stock ProLeap rejects. Where the gap is small the vendored grammar is patched (`EXIT SECTION/PARAGRAPH/PERFORM`, `FUNCTION TRIM(x, LEADING)`, `ALTERNATE KEY` without RECORD, `TYPE` and `STATUS` as data names), and REPOSITORY is blanked before parsing.

For the rest, the spec's Koopa fallback runs. Koopa is an island parser: it passes over what it does not recognize, so facts in a skipped statement are absent, and a deliberately broken lab program still "parses". A member parsed this way therefore carries the dialect feature `FALLBACK PARSER koopa`, which the reports count. Koopa gets a quarter of the member timeout, since its backtracking can run away on a large member, and such a member goes to the report.

Verified by `ParserDifferentialIT`: on every CardDemo base program, both parsers yield the same programs, calls, paragraphs, performs, GO TOs, SQL, CICS and data items, with the same lines, offsets and lengths.

Koopa's Swing front end is left out of the vendored jar because it needs swingx, which is LGPL.
