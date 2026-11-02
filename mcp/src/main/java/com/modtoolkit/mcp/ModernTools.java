package com.modtoolkit.mcp;

import static com.modtoolkit.mcp.LegacyTools.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.core.migration.MigrationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** get_modern_impl, gap_report, deviations and record_deviation. */
@Component
public class ModernTools implements ToolSet {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MigrationService migration;
    private final Lookup lookup;

    public ModernTools(JdbcTemplate jdbc, MigrationService migration, Lookup lookup) {
        this.jdbc = jdbc;
        this.migration = migration;
        this.lookup = lookup;
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("get_modern_impl", "Where a program or rule lives in the modern code: confirmed links are authoritative; candidates come with their confidence and are marked as candidates.",
                        schema("\"program\":{\"type\":\"string\"},\"rule_id\":{\"type\":\"string\"}"), false, (a, w) -> modernImpl(a)),
                new ToolDef("gap_report", "Per program: implemented, partial, missing or replaced_by_design. Per rule: covered, uncovered or changed, with modern locations.",
                        schema("\"scope\":{\"type\":\"string\",\"description\":\"a program name, a job name, or phase1\"}", "scope"), false, (a, w) -> gap(a)),
                new ToolDef("deviations", "Approved intentional differences. Read these before translating or fixing anything: a registered deviation is never reverted to legacy behavior.",
                        schema("\"program\":{\"type\":\"string\"},\"rule_id\":{\"type\":\"string\"}"), false, (a, w) -> deviations(a)),
                new ToolDef("record_deviation", "Proposes an intentional difference for lead approval. It has no effect until approved.",
                        schema("\"legacy_ref\":{\"type\":\"object\"},\"modern_ref\":{\"type\":\"object\"},\"change\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"},\"comparison_rule\":{\"type\":\"object\"}", "legacy_ref", "modern_ref", "change", "reason", "comparison_rule"), true, (a, w) -> record(a)));
    }

    private List<?> modernImpl(JsonNode a) {
        String program = a.path("program").asText(null), rule = a.path("rule_id").asText(null);
        if ((program == null) == (rule == null)) throw new ToolError("BAD_ARGUMENT", "pass exactly one of program or rule_id", "for example {\"program\":\"CBACT04C\"}");
        if (rule != null) return ruleImpl(rule);
        String name = lookup.programNameOrKnownTarget(program);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.kind, u.name, u.repo, u.path, u.line_start, u.line_end, max(l.confidence) AS confidence,
                       min(l.status) AS status, array_to_string(array_agg(DISTINCT l.source), ',') AS sources
                FROM migration_link l JOIN graph_node n ON n.id = l.legacy_node_id JOIN modern_unit u ON u.id = l.modern_unit_id
                WHERE upper(n.name) = upper(?) AND l.status <> 'rejected' GROUP BY u.id, u.kind, u.name, u.repo, u.path, u.line_start, u.line_end
                ORDER BY min(l.status) = 'confirmed' DESC, max(l.confidence) DESC, u.path, u.line_start""", name);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("authoritative", "confirmed".equals(r.get("status")));
            m.put("confidence", Math.round(((Number) r.get("confidence")).doubleValue() * 100) / 100.0);
            out.add(m);
        }
        return out;
    }

    private List<?> ruleImpl(String rule) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.rule_id, c.status, u.kind, u.name, u.path, u.line_start, u.line_end, c.checked_by, a.id AS artifact_id
                FROM rule_coverage c JOIN knowledge_artifact a ON a.id = c.rule_artifact_id LEFT JOIN modern_unit u ON u.id = c.modern_unit_id
                WHERE upper(c.rule_id) = upper(?) ORDER BY u.path""", rule);
        if (rows.isEmpty()) throw new ToolError("NOT_FOUND", "no coverage recorded for rule " + rule, "check the rule id with business_rules, or run the coverage check");
        return rows;
    }

    private List<?> gap(JsonNode a) throws Exception {
        String scope = a.path("scope").asText("").strip();
        if (scope.isEmpty()) throw new ToolError("BAD_ARGUMENT", "scope is required", "a program name, a job name, or phase1");
        List<Long> ids = new ArrayList<>();
        if (scope.equalsIgnoreCase("phase1")) {
            List<String> programs = phase1Programs();
            if (programs.isEmpty()) {
                throw new ToolError("NOT_CONFIGURED", "scope.phase1_programs is not set", "set it in toolkit.yaml and point TOOLKIT_CONFIG at that file");
            }
            for (String p : programs) ids.addAll(jdbc.queryForList("SELECT id FROM program WHERE upper(program_id) = upper(?)", Long.class, p));
        } else {
            ids.addAll(jdbc.queryForList("SELECT id FROM program WHERE upper(program_id) = upper(?)", Long.class, scope));
            if (ids.isEmpty()) {
                ids.addAll(jdbc.queryForList("""
                        SELECT DISTINCT p.id FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id JOIN program p ON upper(p.program_id) = upper(s.pgm) WHERE upper(j.name) = upper(?)""", Long.class, scope));
            }
            if (ids.isEmpty()) throw new ToolError("NOT_FOUND", "no program or job named " + scope, "use a program name, a job name, or phase1");
        }
        return migration.gapReport(ids);
    }

    private static List<String> phase1Programs() {
        Path p = Path.of(System.getenv().getOrDefault("TOOLKIT_CONFIG", "toolkit.yaml"));
        if (!Files.exists(p)) return List.of();
        ToolkitConfig c = ToolkitConfig.load(p);
        return c.scope() == null || c.scope().phase1Programs() == null ? List.of() : c.scope().phase1Programs();
    }

    private List<?> deviations(JsonNode a) {
        String program = a.path("program").asText(null), rule = a.path("rule_id").asText(null);
        String key = program != null ? program : rule;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : migration.deviations("approved", key)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            for (String k : List.of("legacy_ref", "modern_ref", "comparison_rule")) {
                try {
                    m.put(k, JSON.readTree(String.valueOf(r.get(k))));
                } catch (Exception e) {
                    m.put(k, r.get(k));
                }
            }
            out.add(m);
        }
        return out;
    }

    private List<?> record(JsonNode a) throws Exception {
        for (String f : List.of("legacy_ref", "modern_ref", "comparison_rule")) {
            if (!a.path(f).isObject()) throw new ToolError("BAD_ARGUMENT", f + " must be an object", "see the tool's input schema");
        }
        String kind = a.path("comparison_rule").path("kind").asText("");
        if (!List.of("ignore_field", "numeric_tolerance", "expected_transformation", "expected_new_behavior").contains(kind)) {
            throw new ToolError("BAD_ARGUMENT", "comparison_rule.kind must be ignore_field, numeric_tolerance, expected_transformation or expected_new_behavior", "for example {\"kind\":\"ignore_field\",\"field\":\"ACCT-ADDR-ZIP\"}");
        }
        long id = migration.createDeviation(a.get("legacy_ref"), a.get("modern_ref"), need(a, "change"), need(a, "reason"), a.get("comparison_rule"), "candidate");
        return List.of(Map.of("id", id, "status", "candidate", "note", "a lead must approve this before it has any effect"));
    }

    private static String need(JsonNode a, String f) {
        String v = a.path(f).asText("").strip();
        if (v.isEmpty()) throw new ToolError("BAD_ARGUMENT", f + " is required", "see the tool's input schema");
        return v;
    }
}
