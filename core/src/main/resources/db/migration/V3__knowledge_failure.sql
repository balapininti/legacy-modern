-- Units the knowledge pass could not complete, so a re-run and the UI can show why. New table only.
CREATE TABLE knowledge_failure (
  id bigserial PRIMARY KEY, llm_run_id bigint REFERENCES llm_run ON DELETE SET NULL,
  unit_kind text NOT NULL, unit_name text NOT NULL, kind text NOT NULL, error text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE UNIQUE INDEX knowledge_artifact_identity ON knowledge_artifact (unit_kind, unit_id, kind, content_hash, prompt_version, model, coalesce(content->>'data_item',''), coalesce(content->>'map',''), coalesce(content->>'job',''));

-- Provenance says where a fact was written; the owner is the member whose parse produced it. They differ for a step
-- written in a PROC and expanded by a job, and only the owner's re-parse may delete the edge.
ALTER TABLE graph_edge ADD COLUMN owner_member_id bigint REFERENCES member ON DELETE CASCADE;
CREATE INDEX graph_edge_owner_idx ON graph_edge (owner_member_id);
