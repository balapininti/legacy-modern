package com.modtoolkit.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Name resolution shared by the tools: exact, case-insensitive, and an honest error with suggestions otherwise. */
@Component
public class Lookup {
    private final JdbcTemplate jdbc;

    public Lookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> member(String nameOrPath, String... types) {
        String typeFilter = types.length == 0 ? "" : " AND type = '" + types[0].replaceAll("[^a-z_]", "") + "'";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, path, name, type FROM member WHERE (path = ? OR upper(name) = upper(?))" + typeFilter + " ORDER BY (path = ?) DESC, length(path), path", nameOrPath, nameOrPath, nameOrPath);
        if (rows.isEmpty()) {
            throw notFound(types.length == 0 ? "member" : types[0], nameOrPath, "SELECT name FROM member WHERE name % ?" + typeFilter + " ORDER BY similarity(name, ?) DESC LIMIT 5");
        }
        if (rows.size() > 1 && !rows.get(0).get("path").equals(nameOrPath)) {
            throw new ToolError("AMBIGUOUS", nameOrPath + " matches " + rows.size() + " members", "pass one path exactly: " + rows.stream().map(r -> (String) r.get("path")).limit(8).toList());
        }
        return rows.get(0);
    }

    public Map<String, Object> program(String name) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.member_id, p.program_id, p.kind, p.line_start, p.line_end, p.metrics::text AS metrics, m.path
                FROM program p JOIN member m ON m.id = p.member_id WHERE upper(p.program_id) = upper(?) ORDER BY length(m.path), m.path""", name);
        if (rows.isEmpty()) {
            throw notFound("program", name, "SELECT program_id FROM program WHERE program_id % ? ORDER BY similarity(program_id, ?) DESC LIMIT 5");
        }
        return rows.get(0);
    }

    /** A CALL target with no COBOL source (say an Assembler routine) still has callers worth showing. */
    public String programNameOrKnownTarget(String name) {
        List<String> known = jdbc.queryForList("SELECT name FROM graph_node WHERE kind = 'program' AND upper(name) = upper(?)", String.class, name);
        if (!known.isEmpty()) return known.get(0);
        throw notFound("program", name, "SELECT name FROM graph_node WHERE kind = 'program' AND name % ? ORDER BY similarity(name, ?) DESC LIMIT 5");
    }

    public List<Map<String, Object>> jclCallers(String program) {
        return jdbc.queryForList("""
                SELECT j.name || '.' || coalesce(s.name, '') AS from_program, upper(s.pgm) AS to_program, 'JCL_STEP' AS edge_type,
                       NULL AS call_mode, NULL AS via, 1 AS depth, m.path AS member, s.line
                FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id JOIN member m ON m.id = coalesce(s.source_member_id, j.member_id)
                WHERE upper(s.pgm) = upper(?) ORDER BY j.name, s.seq""", program);
    }

    public ToolError notFound(String what, String name, String suggestSql) {
        List<String> near = jdbc.queryForList(suggestSql, String.class, name, name);
        String hint = near.isEmpty() ? "check the name with search or inventory_summary" : "did you mean " + near + "?";
        return new ToolError("NOT_FOUND", "no " + what + " named " + name, hint);
    }

    public Object knowledgePart(String part, long programId, String name) {
        String kind = switch (part) {
            case "summary" -> "summary";
            case "rules" -> "rule";
            case "migration_note" -> "migration_note";
            default -> null;
        };
        if (kind == null) {
            return Map.of("status", "not_available", "reason", "no " + part + " data for " + name + " yet");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS artifact_id, a.status, a.content::text AS content, a.line_start, a.line_end, m.path AS member
                FROM knowledge_artifact a LEFT JOIN member m ON m.id = a.member_id
                WHERE a.unit_kind = 'program' AND a.unit_id = ? AND a.kind = ? AND a.status <> 'rejected' ORDER BY a.status = 'reviewed' DESC, a.id""", programId, kind);
        if (rows.isEmpty()) return Map.of("status", "not_available", "reason", "the knowledge pass has not produced a " + kind + " for " + name);
        return rows.stream().map(Lookup::withContent).toList();
    }

    private static Map<String, Object> withContent(Map<String, Object> r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(r);
        try {
            m.put("content", new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(r.get("content"))));
        } catch (Exception e) {
            m.put("content", String.valueOf(r.get("content")));
        }
        return m;
    }

    public Object dictionaryEntry(String item) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS artifact_id, a.status, a.content::text AS content FROM knowledge_artifact a
                WHERE a.kind = 'dictionary' AND upper(a.content->>'data_item') = upper(?) AND a.status <> 'rejected' ORDER BY a.status = 'reviewed' DESC, a.id LIMIT 1""", item);
        return rows.isEmpty() ? null : withContent(rows.get(0));
    }
}
