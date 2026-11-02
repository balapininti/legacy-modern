package com.modtoolkit.mcp;

import static com.modtoolkit.mcp.LegacyTools.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.modtoolkit.rulebook.MappingRules;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** migration_status, record_decision and explain_feature. */
@Component
public class PlanningTools implements ToolSet {
    private final JdbcTemplate jdbc;
    private final Lookup lookup;
    private final MappingRules rules;

    public PlanningTools(JdbcTemplate jdbc, Lookup lookup) throws IOException {
        this.jdbc = jdbc;
        this.lookup = lookup;
        String dir = System.getenv("TOOLKIT_RULES_DIR");
        this.rules = MappingRules.load(dir == null ? null : Path.of(dir));
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("migration_status", "Stage, owner, test counts and target code references for one program or for all.",
                        schema("\"program\":{\"type\":\"string\"}"), false, (a, w) -> status(a)),
                new ToolDef("record_decision", "Appends a decision to the migration log.",
                        schema("\"program\":{\"type\":\"string\"},\"note\":{\"type\":\"string\"},\"target_ref\":{\"type\":\"string\"}", "program", "note"), true, (a, w) -> decide(a, w.username())),
                new ToolDef("explain_feature", "Canonical guidance for mapping a COBOL feature to Java, from the rulebook. For example comp-3, redefines, cics-link, gdg.",
                        schema("\"feature\":{\"type\":\"string\"}", "feature"), false, (a, w) -> explain(a)));
    }

    private List<?> status(JsonNode a) {
        String program = a.path("program").asText(null);
        if (program != null) lookup.program(program);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.program_id AS program, m.path AS member, p.line_start, p.line_end, coalesce(s.stage, 'analyzed') AS stage, s.owner, coalesce(s.test_count, 0) AS test_count, coalesce(s.tests_passing, 0) AS tests_passing,
                       coalesce(s.target_refs, '[]'::jsonb)::text AS target_refs, s.updated_at::text AS updated_at
                FROM program p JOIN member m ON m.id = p.member_id LEFT JOIN migration_status s ON s.program_id = p.id
                WHERE p.parent_id IS NULL AND (?::text IS NULL OR upper(p.program_id) = upper(?)) ORDER BY p.program_id""", program, program);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            try {
                m.put("target_refs", new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(r.get("target_refs"))));
            } catch (Exception e) {
                m.put("target_refs", List.of());
            }
            out.add(m);
        }
        return out;
    }

    private List<?> decide(JsonNode a, String author) {
        String program = a.path("program").asText("").strip(), note = a.path("note").asText("").strip();
        if (program.isEmpty() || note.isEmpty()) throw new ToolError("BAD_ARGUMENT", "program and note are required", "see the tool's input schema");
        lookup.program(program);
        long id = jdbc.queryForObject("INSERT INTO migration_decision (program, note, target_ref, author) VALUES (?,?,?,?) RETURNING id", Long.class, program.toUpperCase(), note, a.path("target_ref").asText(null), author);
        return List.of(Map.of("id", id, "program", program.toUpperCase(), "author", author));
    }

    private List<?> explain(JsonNode a) {
        String f = a.path("feature").asText("").strip();
        if (f.isEmpty()) throw new ToolError("BAD_ARGUMENT", "feature is required", "for example comp-3");
        MappingRules.Rule r = rules.forFeature(f).orElseThrow(() -> new ToolError("NOT_FOUND", "no guidance for " + f, "known features: " + rules.knownFeatures()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule_id", r.id());
        m.put("cobol", r.cobol());
        m.put("java", r.java());
        m.put("guidance", r.guidance());
        m.put("example", r.example());
        return List.of(m);
    }
}
