package com.modtoolkit.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Knowledge artifacts as separate search documents, each with the provenance of what it describes. */
@Service
public class ArtifactChunks {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ArtifactChunks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int index() throws Exception {
        int n = 0;
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT a.id, a.kind, a.member_id, a.line_start, a.line_end, a.content::text AS content, p.program_id
                FROM knowledge_artifact a LEFT JOIN program p ON a.unit_kind = 'program' AND p.id = a.unit_id
                WHERE NOT EXISTS (SELECT 1 FROM chunk c WHERE c.artifact_id = a.id) ORDER BY a.id""")) {
            long id = ((Number) a.get("id")).longValue();
            String kind = (String) a.get("kind");
            JsonNode c = JSON.readTree((String) a.get("content"));
            String program = (String) a.get("program_id");
            Object member = a.get("member_id");
            switch (kind) {
                case "summary" -> {
                    add(id, member, "summary", a.get("line_start"), a.get("line_end"), program + ": " + c.path("purpose").asText() + " Inputs: " + c.path("inputs") + " Outputs: " + c.path("outputs") + " Role: " + c.path("role").asText() + " Errors: " + c.path("error_handling").asText(), program);
                    n++;
                }
                case "rule" -> {
                    for (JsonNode r : c.path("rules")) {
                        add(id, member, "rule", r.path("line_start").asInt(), r.path("line_end").asInt(), program + " " + r.path("rule_id").asText() + ": " + r.path("statement").asText() + " When " + r.path("condition").asText() + " then " + r.path("action").asText() + " " + r.path("data_items"), r.path("rule_id").asText());
                        n++;
                    }
                }
                case "dictionary" -> {
                    add(id, member, "dictionary", c.path("line").asInt(), c.path("line").asInt(), c.path("data_item").asText() + ": " + c.path("meaning").asText() + " Values: " + c.path("domain_values"), c.path("data_item").asText());
                    n++;
                }
                case "job_narrative" -> {
                    add(id, member, "job", a.get("line_start"), a.get("line_end"), c.path("job").asText() + ": " + c.path("purpose").asText() + " Steps: " + c.path("steps"), c.path("job").asText());
                    n++;
                }
                case "screen_flow" -> {
                    add(id, member, "map", a.get("line_start"), a.get("line_end"), c.path("map").asText() + " screen. Fields: " + c.path("fields") + " Validations: " + c.path("validations") + " Next: " + c.path("next_maps"), c.path("map").asText());
                    n++;
                }
                default -> { }
            }
        }
        return n;
    }

    private void add(long artifact, Object member, String kind, Object start, Object end, String text, String unit) {
        jdbc.update("INSERT INTO chunk (member_id, artifact_id, kind, line_start, line_end, text, tsv, metadata) VALUES (?,?,?,?,?,?,cobol_tsvector(?),jsonb_build_object('unit',?::text))",
                member, artifact, kind, start, end, text, text, String.valueOf(unit));
    }

    /** Content an SME edited must be found by its new words. */
    public void reindexArtifact(long id) {
        jdbc.update("DELETE FROM chunk WHERE artifact_id = ?", id);
    }

    static List<String> kinds() {
        return List.of("summary", "rule", "dictionary", "job", "map");
    }
}
