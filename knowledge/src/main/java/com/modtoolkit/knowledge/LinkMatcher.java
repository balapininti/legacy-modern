package com.modtoolkit.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.Hashes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The LLM signal for legacy-to-modern links, and the rule coverage check. Each stays a proposal: links are candidates
 * until a lead confirms them, coverage is checked_by llm until a developer confirms it. Both only ever cite units
 * listed in the prompt, so a made-up id is rejected, not stored.
 */
@Service
public class LinkMatcher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_UNITS = 60;
    private static final int MAX_MODERN_CHARS = 60_000;

    public record Stats(int programs, int links, int rulesChecked, long tokensIn, long tokensOut) {}

    private final JdbcTemplate jdbc;

    public LinkMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Stats matchLinks(LlmClient llm, List<String> onlyPrograms) throws Exception {
        List<Map<String, Object>> programs = jdbc.queryForList("""
                SELECT p.id, p.program_id, a.content::text AS summary FROM program p JOIN knowledge_artifact a ON a.unit_id = p.id AND a.unit_kind = 'program' AND a.kind = 'summary' AND a.status <> 'rejected'
                ORDER BY p.program_id""");
        List<Map<String, Object>> units = jdbc.queryForList("""
                SELECT u.id, u.kind, u.name, u.signature, left(c.text, 400) AS excerpt FROM modern_unit u LEFT JOIN chunk c ON c.modern_unit_id = u.id
                WHERE u.kind IN ('java_class','endpoint','batch_job','entity','ng_component') ORDER BY u.path, u.line_start LIMIT ?""", MAX_UNITS * 10);
        Set<Long> known = new TreeSet<>();
        units.forEach(u -> known.add(((Number) u.get("id")).longValue()));
        int linked = 0, seen = 0;
        long in = 0, out = 0;
        for (Map<String, Object> p : programs) {
            String name = (String) p.get("program_id");
            if (!onlyPrograms.isEmpty() && !onlyPrograms.contains(name)) continue;
            seen++;
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("modern_units", units);
            String prompt = Prompts.render("link_match", name, JSON.writeValueAsString(ctx), (String) p.get("summary"));
            LlmClient.Response r = llm.complete("link_match/" + name, Prompts.system(), prompt);
            in += r.tokensIn();
            out += r.tokensOut();
            JsonNode a = ArtifactValidator.validate("link_match", r.text(), new ArtifactValidator.Facts(0, Integer.MAX_VALUE, Set.of(), Set.of()));
            for (JsonNode m : a.get("matches")) {
                long unit = m.get("unit_id").asLong();
                if (!known.contains(unit) || m.get("confidence").asDouble() < 0.5) continue;
                linked += jdbc.update("""
                        INSERT INTO migration_link (legacy_node_id, modern_unit_id, granularity, confidence, source, evidence)
                        SELECT n.id, ?, 'program', ?, 'llm_match', ?::jsonb FROM graph_node n WHERE n.kind = 'program' AND n.name = ?
                        ON CONFLICT (legacy_node_id, modern_unit_id, source, granularity) DO UPDATE SET confidence = EXCLUDED.confidence, evidence = EXCLUDED.evidence""",
                        unit, (float) m.get("confidence").asDouble(), JSON.writeValueAsString(Map.of("reason", m.get("reason").asText(), "model", llm.model())), name);
            }
        }
        return new Stats(seen, linked, 0, in, out);
    }

    /** Only for programs with a confirmed link: there is nothing to compare otherwise. */
    public Stats checkCoverage(LlmClient llm, List<String> onlyPrograms) throws Exception {
        int rules = 0, seen = 0;
        long in = 0, out = 0;
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT a.id, a.content::text AS rules, p.program_id FROM knowledge_artifact a JOIN program p ON p.id = a.unit_id
                WHERE a.unit_kind = 'program' AND a.kind = 'rule' AND a.status <> 'rejected' ORDER BY p.program_id""")) {
            String name = (String) a.get("program_id");
            if (!onlyPrograms.isEmpty() && !onlyPrograms.contains(name)) continue;
            List<Map<String, Object>> linked = jdbc.queryForList("""
                    SELECT DISTINCT u.id, u.name, c.text FROM migration_link l JOIN graph_node n ON n.id = l.legacy_node_id JOIN modern_unit u ON u.id = l.modern_unit_id JOIN chunk c ON c.modern_unit_id = u.id
                    WHERE upper(n.name) = upper(?) AND l.status = 'confirmed'""", name);
            if (linked.isEmpty()) continue;
            seen++;
            StringBuilder code = new StringBuilder();
            List<Map<String, Object>> listed = new ArrayList<>();
            Set<Long> known = new TreeSet<>();
            for (Map<String, Object> u : linked) {
                if (code.length() > MAX_MODERN_CHARS) break;
                long id = ((Number) u.get("id")).longValue();
                known.add(id);
                listed.add(Map.of("id", id, "name", u.get("name")));
                code.append("// unit ").append(id).append(": ").append(u.get("name")).append('\n').append(u.get("text")).append('\n');
            }
            String ctx = JSON.writeValueAsString(Map.of("program", name, "rules", JSON.readTree((String) a.get("rules")).get("rules"), "units", listed));
            LlmClient.Response r = llm.complete("rule_coverage/" + name, Prompts.system(), Prompts.render("rule_coverage", name, ctx, code.toString()));
            in += r.tokensIn();
            out += r.tokensOut();
            JsonNode res = ArtifactValidator.validate("rule_coverage", r.text(), new ArtifactValidator.Facts(0, Integer.MAX_VALUE, Set.of(), Set.of()));
            for (JsonNode x : res.get("results")) {
                Long unit = x.path("modern_unit_id").isNumber() && known.contains(x.get("modern_unit_id").asLong()) ? x.get("modern_unit_id").asLong() : null;
                // A person's confirmation outranks a later model opinion.
                rules += jdbc.update("""
                        INSERT INTO rule_coverage (rule_artifact_id, rule_id, status, modern_unit_id, checked_by) VALUES (?,?,?,?,'llm')
                        ON CONFLICT (rule_artifact_id, rule_id) DO UPDATE SET status = EXCLUDED.status, modern_unit_id = EXCLUDED.modern_unit_id, checked_by = 'llm', checked_at = now()
                        WHERE rule_coverage.checked_by = 'llm'""", a.get("id"), x.get("rule_id").asText(), x.get("status").asText(), unit);
            }
        }
        return new Stats(seen, 0, rules, in, out);
    }

    /** Confirmation by a developer, or their own correction. */
    public void confirmCoverage(long artifact, String ruleId, String status, Long modernUnit, String by) {
        jdbc.update("""
                INSERT INTO rule_coverage (rule_artifact_id, rule_id, status, modern_unit_id, checked_by) VALUES (?,?,?,?,?)
                ON CONFLICT (rule_artifact_id, rule_id) DO UPDATE SET status = EXCLUDED.status, modern_unit_id = coalesce(EXCLUDED.modern_unit_id, rule_coverage.modern_unit_id), checked_by = EXCLUDED.checked_by, checked_at = now()""",
                artifact, ruleId, status, modernUnit, by);
    }

    static String cacheKey(String s) {
        return Hashes.sha256(s);
    }
}
