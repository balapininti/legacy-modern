-- Whole identifiers stay findable, and now so do their parts: "balance" has to be able to reach ACCT-CURR-BAL through
-- "bal", with the question words expanded by the search code. Measured by the CardDemo evaluation set (ADR 0007).
CREATE OR REPLACE FUNCTION cobol_tsvector(t text) RETURNS tsvector LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT coalesce(string_agg(quote_literal(x.tok) || ':' || least(x.pos, 16383), ' '), '')::tsvector FROM (
    SELECT tok, pos FROM cobol_tokens(t)
    UNION ALL
    SELECT part, pos FROM cobol_tokens(t) CROSS JOIN LATERAL regexp_split_to_table(tok, '[-_]') AS part WHERE tok ~ '[-_]' AND part <> ''
  ) x
$$;
UPDATE chunk SET tsv = cobol_tsvector(text);
