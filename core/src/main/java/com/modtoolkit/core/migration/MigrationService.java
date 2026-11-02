package com.modtoolkit.core.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Link confirmation, the deviation register and the gap report. REST, MCP and UI all go through here. */
@Service
public class MigrationService {
    public static final class NotFound extends RuntimeException {
        public NotFound(String m) {
            super(m);
        }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String m) {
            super(m);
        }
    }

    private static final List<String> STAGES = List.of("analyzed", "brief_generated", "translated", "tests_generated", "tests_passing", "sme_signed_off");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public MigrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> links(String status) {
        return jdbc.queryForList("""
                SELECT l.id, n.name AS legacy, l.granularity, l.confidence, l.source, l.status, l.confirmed_by, l.evidence::text AS evidence,
                       u.kind AS modern_kind, u.name AS modern_name, u.repo, u.path, u.line_start, u.line_end
                FROM migration_link l JOIN graph_node n ON n.id = l.legacy_node_id JOIN modern_unit u ON u.id = l.modern_unit_id
                WHERE (?::text IS NULL OR l.status = ?) ORDER BY l.status, l.confidence DESC, n.name, u.name""", status, status);
    }

    /** Confirming a program link makes it authoritative and moves the program to at least translated. */
    @Transactional
    public void confirmLink(long id, String by) {
        Map<String, Object> l = link(id);
        jdbc.update("UPDATE migration_link SET status = 'confirmed', confirmed_by = ? WHERE id = ?", by, id);
        // The same pair from another signal is the same decision.
        jdbc.update("UPDATE migration_link SET status = 'confirmed', confirmed_by = ? WHERE legacy_node_id = ? AND modern_unit_id = ? AND status = 'candidate'", by, l.get("legacy_node_id"), l.get("modern_unit_id"));
        if ("program".equals(l.get("granularity"))) {
            String modern = jdbc.queryForObject("SELECT path FROM modern_unit WHERE id = ?", String.class, l.get("modern_unit_id"));
            for (Long p : jdbc.queryForList("SELECT p.id FROM program p JOIN graph_node n ON upper(n.name) = upper(p.program_id) WHERE n.id = ?", Long.class, l.get("legacy_node_id"))) {
                advance(p, "translated", modern);
            }
            jdbc.update("""
                    INSERT INTO graph_edge (src_id, dst_id, type, attrs)
                    SELECT n.id, ?, 'IMPLEMENTS', jsonb_build_object('link', ?::bigint) FROM graph_node n WHERE n.modern_unit_id = ?""",
                    l.get("legacy_node_id"), id, l.get("modern_unit_id"));
        }
    }

    @Transactional
    public void rejectLink(long id, String by) {
        Map<String, Object> l = link(id);
        jdbc.update("UPDATE migration_link SET status = 'rejected', confirmed_by = ? WHERE legacy_node_id = ? AND modern_unit_id = ?", by, l.get("legacy_node_id"), l.get("modern_unit_id"));
        jdbc.update("DELETE FROM graph_edge e WHERE e.type = 'IMPLEMENTS' AND e.attrs->>'link' = ?", String.valueOf(id));
    }

    private Map<String, Object> link(long id) {
        try {
            return jdbc.queryForMap("SELECT legacy_node_id, modern_unit_id, granularity FROM migration_link WHERE id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFound("no link " + id);
        }
    }

    /** Stages only move forward through this call; a later stage already reached is kept. */
    public void advance(long programId, String stage, String targetRef) {
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("unknown stage " + stage);
        jdbc.update("INSERT INTO migration_status (program_id, stage) VALUES (?, 'analyzed') ON CONFLICT DO NOTHING", programId);
        String cur = jdbc.queryForObject("SELECT stage FROM migration_status WHERE program_id = ?", String.class, programId);
        if (STAGES.indexOf(stage) > STAGES.indexOf(cur)) {
            jdbc.update("UPDATE migration_status SET stage = ?, updated_at = now() WHERE program_id = ?", stage, programId);
        }
        if (targetRef != null) {
            jdbc.update("UPDATE migration_status SET target_refs = (SELECT to_jsonb(array_agg(DISTINCT x)) FROM (SELECT jsonb_array_elements_text(target_refs) AS x UNION SELECT ?) y), updated_at = now() WHERE program_id = ?", targetRef, programId);
        }
    }

    public long createDeviation(JsonNode legacyRef, JsonNode modernRef, String change, String reason, JsonNode comparisonRule, String status) throws Exception {
        return jdbc.queryForObject("INSERT INTO deviation (legacy_ref, modern_ref, change, reason, comparison_rule, status) VALUES (?::jsonb,?::jsonb,?,?,?::jsonb,?) RETURNING id", Long.class,
                JSON.writeValueAsString(legacyRef), JSON.writeValueAsString(modernRef), change, reason, JSON.writeValueAsString(comparisonRule), status);
    }

    public void decideDeviation(long id, boolean approve, String by) {
        int n = jdbc.update("UPDATE deviation SET status = ?, approved_by = ? WHERE id = ?", approve ? "approved" : "rejected", by, id);
        if (n == 0) throw new NotFound("no deviation " + id);
    }

    public List<Map<String, Object>> deviations(String status, String program) {
        return jdbc.queryForList("""
                SELECT id, legacy_ref::text AS legacy_ref, modern_ref::text AS modern_ref, change, reason, status, approved_by, comparison_rule::text AS comparison_rule, created_at::text AS created_at
                FROM deviation WHERE (?::text IS NULL OR status = ?)
                  AND (?::text IS NULL OR upper(legacy_ref->>'program') = upper(?) OR upper(legacy_ref->>'rule_id') = upper(?))
                ORDER BY id""", status, status, program, program, program);
    }

    /** One row per program in scope: rule coverage decides partial or implemented; no confirmed link means missing. */
    public List<Map<String, Object>> gapReport(List<Long> programIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : programIds) {
            Map<String, Object> p = jdbc.queryForMap("SELECT p.id, p.program_id, m.path FROM program p JOIN member m ON m.id = p.member_id WHERE p.id = ?", id);
            Map<String, Object> row = new LinkedHashMap<>();
            String name = (String) p.get("program_id");
            row.put("program", name);
            row.put("member", p.get("path"));
            List<Map<String, Object>> links = jdbc.queryForList("""
                    SELECT DISTINCT u.kind, u.name, u.path, u.line_start, u.line_end FROM migration_link l JOIN graph_node n ON n.id = l.legacy_node_id JOIN modern_unit u ON u.id = l.modern_unit_id
                    WHERE upper(n.name) = upper(?) AND l.status = 'confirmed' AND l.granularity = 'program' ORDER BY u.path, u.line_start""", name);
            row.put("modern_locations", links);
            List<Map<String, Object>> rules = jdbc.queryForList("""
                    SELECT a.id AS artifact_id, r.rule->>'rule_id' AS rule_id, r.rule->>'statement' AS statement, a.status AS artifact_status,
                           (r.rule->>'line_start')::int AS line_start, (r.rule->>'line_end')::int AS line_end,
                           coalesce(c.status, 'uncovered') AS coverage, u.path AS modern_path, u.name AS modern_name
                    FROM knowledge_artifact a CROSS JOIN LATERAL jsonb_array_elements(a.content->'rules') r(rule)
                    LEFT JOIN rule_coverage c ON c.rule_artifact_id = a.id AND c.rule_id = r.rule->>'rule_id'
                    LEFT JOIN modern_unit u ON u.id = c.modern_unit_id
                    WHERE a.unit_kind = 'program' AND a.unit_id = ? AND a.kind = 'rule' AND a.status <> 'rejected'
                    ORDER BY line_start""", id);
            row.put("rules", rules);
            long uncovered = rules.stream().filter(r -> !"covered".equals(r.get("coverage"))).count();
            List<String> explicit = jdbc.queryForList("SELECT status FROM program_implementation WHERE program_id = ?", String.class, id);
            String status;
            if (!explicit.isEmpty()) status = explicit.get(0);
            else if (links.isEmpty()) status = "missing";
            else status = uncovered > 0 ? "partial" : "implemented";
            row.put("status", status);
            row.put("rules_total", rules.size());
            row.put("rules_uncovered", uncovered);
            out.add(row);
        }
        return out;
    }

    public void setImplementation(long programId, String status, String note, String by) {
        jdbc.update("""
                INSERT INTO program_implementation (program_id, status, note, confirmed_by) VALUES (?,?,?,?)
                ON CONFLICT (program_id) DO UPDATE SET status = EXCLUDED.status, note = EXCLUDED.note, confirmed_by = EXCLUDED.confirmed_by, checked_at = now()""", programId, status, note, by);
    }
}
