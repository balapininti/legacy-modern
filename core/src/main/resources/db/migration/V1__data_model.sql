-- Spec "Data model". All line numbers are 1-based on normalized text, with the original beside them.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE member (
  id bigserial PRIMARY KEY, path text NOT NULL UNIQUE, name text NOT NULL, type text NOT NULL,
  dialect text, encoding text, format text, content_hash text NOT NULL, loc int NOT NULL DEFAULT 0,
  parse_status text NOT NULL DEFAULT 'pending', parse_error text, classify_reason text,
  normalized_text text, line_map int[], updated_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX member_name_idx ON member (upper(name));

CREATE TABLE program (
  id bigserial PRIMARY KEY, member_id bigint NOT NULL REFERENCES member ON DELETE CASCADE,
  program_id text NOT NULL, parent_id bigint REFERENCES program ON DELETE CASCADE,
  kind text NOT NULL, line_start int, line_end int, metrics jsonb NOT NULL DEFAULT '{}');
CREATE INDEX program_name_idx ON program (upper(program_id));

CREATE TABLE copybook (id bigserial PRIMARY KEY, member_id bigint NOT NULL REFERENCES member ON DELETE CASCADE, name text NOT NULL);
CREATE INDEX copybook_name_idx ON copybook (upper(name));

CREATE TABLE copy_use (
  id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES program ON DELETE CASCADE,
  copybook_id bigint REFERENCES copybook ON DELETE SET NULL, copybook_name text NOT NULL,
  replacing jsonb, line int, member_id bigint REFERENCES member ON DELETE CASCADE);
CREATE INDEX copy_use_program_idx ON copy_use (program_id);
CREATE INDEX copy_use_name_idx ON copy_use (upper(copybook_name));

CREATE TABLE paragraph (
  id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES program ON DELETE CASCADE,
  section text, name text NOT NULL, line_start int NOT NULL, line_end int NOT NULL,
  member_id bigint REFERENCES member ON DELETE CASCADE, complexity int NOT NULL DEFAULT 1, reachable boolean NOT NULL DEFAULT true);
CREATE INDEX paragraph_program_idx ON paragraph (program_id);

CREATE TABLE data_item (
  id bigserial PRIMARY KEY, owner_member_id bigint NOT NULL REFERENCES member ON DELETE CASCADE,
  program_id bigint REFERENCES program ON DELETE CASCADE,
  parent_id bigint REFERENCES data_item ON DELETE CASCADE, level int NOT NULL, name text, pic text, usage text,
  occurs int, occurs_max int, odo_ref text, redefines_id bigint REFERENCES data_item ON DELETE SET NULL, redefines_name text,
  "offset" int, length int, line int NOT NULL, section text);
CREATE INDEX data_item_owner_idx ON data_item (owner_member_id);
CREATE INDEX data_item_name_idx ON data_item (upper(name));
CREATE INDEX data_item_program_idx ON data_item (program_id);

CREATE TABLE condition_value (id bigserial PRIMARY KEY, data_item_id bigint NOT NULL REFERENCES data_item ON DELETE CASCADE, name text NOT NULL, "values" jsonb NOT NULL, line int);

CREATE TABLE modern_unit (
  id bigserial PRIMARY KEY, repo text NOT NULL, path text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('java_class','endpoint','dto','entity','batch_job','ng_component','ng_route','ng_service')),
  name text NOT NULL, signature text, content_hash text NOT NULL, line_start int, line_end int, attrs jsonb NOT NULL DEFAULT '{}');
CREATE INDEX modern_unit_name_idx ON modern_unit (upper(name));

CREATE TABLE graph_node (
  id bigserial PRIMARY KEY, kind text NOT NULL, name text NOT NULL,
  member_id bigint REFERENCES member ON DELETE SET NULL, modern_unit_id bigint REFERENCES modern_unit ON DELETE CASCADE,
  scope text NOT NULL DEFAULT '', UNIQUE (kind, name, scope));
CREATE INDEX graph_node_name_idx ON graph_node (upper(name));
CREATE TABLE graph_edge (
  id bigserial PRIMARY KEY, src_id bigint NOT NULL REFERENCES graph_node ON DELETE CASCADE,
  dst_id bigint NOT NULL REFERENCES graph_node ON DELETE CASCADE, type text NOT NULL,
  program_id bigint REFERENCES program ON DELETE CASCADE, member_id bigint REFERENCES member ON DELETE CASCADE,
  line int, attrs jsonb NOT NULL DEFAULT '{}');
CREATE INDEX graph_edge_src_idx ON graph_edge (src_id, type);
CREATE INDEX graph_edge_dst_idx ON graph_edge (dst_id, type);
CREATE INDEX graph_edge_member_idx ON graph_edge (member_id);

CREATE TABLE unresolved_call (
  id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES program ON DELETE CASCADE,
  member_id bigint REFERENCES member ON DELETE CASCADE, target_expr text NOT NULL, line int, candidates jsonb NOT NULL DEFAULT '[]', reason text);
CREATE TABLE missing_copybook (id bigserial PRIMARY KEY, member_id bigint NOT NULL REFERENCES member ON DELETE CASCADE, name text NOT NULL, line int);
CREATE TABLE dialect_feature (id bigserial PRIMARY KEY, member_id bigint NOT NULL REFERENCES member ON DELETE CASCADE, feature text NOT NULL, line int);

CREATE TABLE sql_stmt (
  id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES program ON DELETE CASCADE, paragraph_id bigint REFERENCES paragraph ON DELETE SET NULL,
  member_id bigint REFERENCES member ON DELETE CASCADE, line int NOT NULL, line_end int, stmt_type text NOT NULL,
  tables jsonb NOT NULL DEFAULT '[]', columns jsonb NOT NULL DEFAULT '[]', host_vars jsonb NOT NULL DEFAULT '[]', text text);
CREATE INDEX sql_stmt_program_idx ON sql_stmt (program_id);
CREATE TABLE cics_cmd (
  id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES program ON DELETE CASCADE, paragraph_id bigint REFERENCES paragraph ON DELETE SET NULL,
  member_id bigint REFERENCES member ON DELETE CASCADE, line int NOT NULL, command text NOT NULL, resource_kind text, resource_name text, attrs jsonb NOT NULL DEFAULT '{}');
CREATE INDEX cics_cmd_program_idx ON cics_cmd (program_id);

CREATE TABLE jcl_job (id bigserial PRIMARY KEY, member_id bigint NOT NULL REFERENCES member ON DELETE CASCADE, name text NOT NULL, line int);
CREATE TABLE jcl_step (
  id bigserial PRIMARY KEY, job_id bigint NOT NULL REFERENCES jcl_job ON DELETE CASCADE, seq int NOT NULL, name text, pgm text, proc text, cond text, params jsonb NOT NULL DEFAULT '{}', line int, source_member_id bigint REFERENCES member ON DELETE SET NULL);
CREATE TABLE jcl_dd (
  id bigserial PRIMARY KEY, step_id bigint NOT NULL REFERENCES jcl_step ON DELETE CASCADE, dd_name text, dsn text, disp text, gdg_rel int, attrs jsonb NOT NULL DEFAULT '{}', line int);
CREATE INDEX jcl_step_pgm_idx ON jcl_step (upper(pgm));

CREATE TABLE bms_map (id bigserial PRIMARY KEY, member_id bigint NOT NULL REFERENCES member ON DELETE CASCADE, mapset text NOT NULL, name text NOT NULL, line int);
CREATE TABLE bms_field (id bigserial PRIMARY KEY, map_id bigint NOT NULL REFERENCES bms_map ON DELETE CASCADE, name text, "row" int, col int, length int, attrs jsonb NOT NULL DEFAULT '{}', line int);

CREATE TABLE db2_table (id bigserial PRIMARY KEY, name text NOT NULL, schema text, member_id bigint REFERENCES member ON DELETE CASCADE, line int, dclgen_copybook_id bigint REFERENCES copybook ON DELETE SET NULL);
CREATE TABLE db2_column (id bigserial PRIMARY KEY, table_id bigint NOT NULL REFERENCES db2_table ON DELETE CASCADE, name text NOT NULL, type text, nullable boolean, key_pos int, line int);

CREATE TABLE knowledge_artifact (
  id bigserial PRIMARY KEY, unit_kind text NOT NULL, unit_id bigint NOT NULL, kind text NOT NULL
    CHECK (kind IN ('summary','rule','dictionary','screen_flow','job_narrative','migration_note')),
  content jsonb NOT NULL, model text NOT NULL, prompt_version text NOT NULL, content_hash text NOT NULL,
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','reviewed','rejected')), reviewer text,
  member_id bigint REFERENCES member ON DELETE CASCADE, line_start int, line_end int, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX knowledge_artifact_unit_idx ON knowledge_artifact (unit_kind, unit_id, kind);
CREATE TABLE llm_cache (cache_key text PRIMARY KEY, response jsonb NOT NULL, tokens_in int, tokens_out int, created_at timestamptz NOT NULL DEFAULT now());

-- Keeps hyphenated COBOL identifiers whole, including numeric-leading paragraph names (1000-MAIN-PARA), which
-- Postgres' default parser splits whatever the configuration. pg_trgm covers partial identifiers. See ADR 0004.
CREATE FUNCTION cobol_tokens(t text) RETURNS TABLE (tok text, pos bigint) LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT m[1], o FROM regexp_matches(lower(coalesce(t,'')), '([a-z0-9]+(?:[-_][a-z0-9]+)*)', 'g') WITH ORDINALITY AS x(m, o)
$$;
CREATE FUNCTION cobol_tsvector(t text) RETURNS tsvector LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT coalesce(string_agg(quote_literal(tok) || ':' || least(pos, 16383), ' '), '')::tsvector FROM cobol_tokens(t)
$$;
CREATE FUNCTION cobol_tsquery(t text) RETURNS tsquery LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT coalesce(string_agg(quote_literal(tok), ' | '), '')::tsquery FROM cobol_tokens(t)
$$;

CREATE TABLE chunk (
  id bigserial PRIMARY KEY, member_id bigint REFERENCES member ON DELETE CASCADE, artifact_id bigint REFERENCES knowledge_artifact ON DELETE CASCADE,
  modern_unit_id bigint REFERENCES modern_unit ON DELETE CASCADE,
  kind text NOT NULL, line_start int, line_end int, text text NOT NULL,
  tsv tsvector, embedding vector, embedding_model text, metadata jsonb NOT NULL DEFAULT '{}');
CREATE INDEX chunk_tsv_idx ON chunk USING gin (tsv);
CREATE INDEX chunk_text_trgm_idx ON chunk USING gin (text gin_trgm_ops);
CREATE INDEX chunk_member_idx ON chunk (member_id);

CREATE TABLE migration_status (
  program_id bigint PRIMARY KEY REFERENCES program ON DELETE CASCADE,
  stage text NOT NULL DEFAULT 'analyzed' CHECK (stage IN ('analyzed','brief_generated','translated','tests_generated','tests_passing','sme_signed_off')),
  owner text, target_refs jsonb NOT NULL DEFAULT '[]', test_count int NOT NULL DEFAULT 0, tests_passing int NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE migration_decision (id bigserial PRIMARY KEY, program text NOT NULL, note text NOT NULL, target_ref text, author text, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE migration_link (
  id bigserial PRIMARY KEY, legacy_node_id bigint NOT NULL REFERENCES graph_node ON DELETE CASCADE, modern_unit_id bigint NOT NULL REFERENCES modern_unit ON DELETE CASCADE,
  granularity text NOT NULL CHECK (granularity IN ('program','rule')), confidence real NOT NULL,
  source text NOT NULL CHECK (source IN ('name_match','llm_match','commit_ref','manual')),
  status text NOT NULL DEFAULT 'candidate' CHECK (status IN ('candidate','confirmed','rejected')), confirmed_by text, evidence jsonb NOT NULL DEFAULT '{}',
  UNIQUE (legacy_node_id, modern_unit_id, source, granularity));
CREATE TABLE rule_coverage (
  rule_artifact_id bigint NOT NULL REFERENCES knowledge_artifact ON DELETE CASCADE, rule_id text NOT NULL DEFAULT '',
  status text NOT NULL CHECK (status IN ('covered','uncovered','changed')), modern_unit_id bigint REFERENCES modern_unit ON DELETE SET NULL, checked_by text, checked_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (rule_artifact_id, rule_id));
CREATE TABLE program_implementation (program_id bigint PRIMARY KEY REFERENCES program ON DELETE CASCADE,
  status text NOT NULL CHECK (status IN ('implemented','partial','missing','replaced_by_design')), note text, confirmed_by text, checked_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE deviation (
  id bigserial PRIMARY KEY, legacy_ref jsonb NOT NULL, modern_ref jsonb NOT NULL, change text NOT NULL, reason text NOT NULL,
  status text NOT NULL DEFAULT 'candidate' CHECK (status IN ('candidate','approved','rejected')), approved_by text, comparison_rule jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE capture (id bigserial PRIMARY KEY, subject_kind text NOT NULL, subject_id text NOT NULL, jcl text, inputs jsonb NOT NULL DEFAULT '[]', outputs jsonb NOT NULL DEFAULT '[]', raw_path text, status text NOT NULL, source text NOT NULL DEFAULT 'zowe', created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fixture (id bigserial PRIMARY KEY, capture_id bigint NOT NULL REFERENCES capture ON DELETE CASCADE, name text NOT NULL, input_json jsonb NOT NULL, expected_json jsonb NOT NULL);

CREATE TABLE llm_run (id bigserial PRIMARY KEY, job_execution_id bigint, model text, units int NOT NULL DEFAULT 0, tokens_in bigint NOT NULL DEFAULT 0, tokens_out bigint NOT NULL DEFAULT 0, cost_usd numeric(12,4) NOT NULL DEFAULT 0, status text NOT NULL, started_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz);

CREATE TABLE audit_log (id bigserial PRIMARY KEY, ts timestamptz NOT NULL DEFAULT now(), "user" text, action text NOT NULL, tool text, params_hash text, latency_ms bigint, tokens bigint, outcome text);
CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'audit_log is append-only'; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER audit_log_no_change BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

CREATE TABLE app_user (id bigserial PRIMARY KEY, username text NOT NULL UNIQUE, password_hash text, display_name text, enabled boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE role (id bigserial PRIMARY KEY, name text NOT NULL UNIQUE);
INSERT INTO role (name) VALUES ('admin'),('developer'),('sme-reviewer');
CREATE TABLE user_role (user_id bigint NOT NULL REFERENCES app_user ON DELETE CASCADE, role_id bigint NOT NULL REFERENCES role ON DELETE CASCADE, PRIMARY KEY (user_id, role_id));
CREATE TABLE api_token (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES app_user ON DELETE CASCADE, name text NOT NULL, token_hash text NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz, last_used_at timestamptz);
