package com.modtoolkit.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The Module 1 reports, computed from the database so the CLI, MCP tools and UI all agree. */
@Component
public class DiscoveryReports {
    /** A target exists if a COBOL program defines it or a member-level language (say Assembler) carries its name. */
    private static final String IN_ESTATE = "EXISTS (SELECT 1 FROM program p WHERE upper(p.program_id) = d.name) "
            + "OR EXISTS (SELECT 1 FROM member m WHERE upper(m.name) = d.name AND m.type IN ('assembler','pli','easytrieve','rexx','clist'))";

    /** The BMS assembler generates the copybook for each mapset, so its absence from the export is expected. */
    private static final String NOT_SYMBOLIC_MAP = "NOT EXISTS (SELECT 1 FROM bms_map b JOIN member bm ON bm.id = b.member_id WHERE upper(b.mapset) = upper(c.name) OR upper(bm.name) = upper(c.name))";

    private final JdbcTemplate jdbc;

    public DiscoveryReports(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> summary() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("members_by_type", counts("SELECT type, count(*) FROM member GROUP BY type ORDER BY type"));
        r.put("members_by_dialect", counts("SELECT coalesce(dialect,'n/a'), count(*) FROM member GROUP BY 1 ORDER BY 1"));
        r.put("parse_status", counts("SELECT parse_status, count(*) FROM member GROUP BY parse_status ORDER BY parse_status"));
        r.put("programs", one("SELECT count(*) FROM program"));
        r.put("lines", one("SELECT coalesce(sum(loc),0) FROM member"));
        r.put("edges_by_type", counts("SELECT type, count(*) FROM graph_edge GROUP BY type ORDER BY type"));
        r.put("static_calls", staticCallResolution());
        r.put("unresolved_dynamic_calls", one("SELECT count(*) FROM unresolved_call"));
        r.put("missing_copybooks", one("SELECT count(DISTINCT c.name) FROM missing_copybook c WHERE " + NOT_SYMBOLIC_MAP));
        r.put("symbolic_maps_generated_from_bms", one("SELECT count(DISTINCT c.name) FROM missing_copybook c WHERE NOT (" + NOT_SYMBOLIC_MAP + ")"));
        r.put("missing_procs", one("SELECT count(DISTINCT feature) FROM dialect_feature WHERE feature LIKE 'MISSING PROC %'"));
        r.put("jobs", one("SELECT count(*) FROM jcl_job"));
        r.put("job_steps", one("SELECT count(*) FROM jcl_step"));
        r.put("maps", one("SELECT count(*) FROM bms_map"));
        r.put("map_fields", one("SELECT count(*) FROM bms_field"));
        r.put("db2_tables", one("SELECT count(*) FROM db2_table"));
        r.put("dead_code_candidates", deadCodeCandidates().size());
        r.put("dialect_features", counts("SELECT feature, count(DISTINCT member_id) FROM dialect_feature WHERE feature NOT LIKE 'MISSING PROC %' GROUP BY feature ORDER BY feature"));
        return r;
    }

    /** Share of static CALL edges whose target is a program in this estate; subsystem APIs are counted separately. */
    public Map<String, Object> staticCallResolution() {
        String base = "FROM graph_edge e JOIN graph_node d ON d.id = e.dst_id WHERE e.type = 'CALLS' AND e.attrs->>'mode' = 'static'";
        String system = " AND d.name ~ '^(CBLTDLI|AIBTDLI|AERTDLI|MQ[A-Z0-9]+|CEE[A-Z0-9]+|DSN[A-Z0-9]+|IGZ[A-Z0-9]+|ILBO[A-Z0-9]+|DFH[A-Z0-9]+|EZA[A-Z0-9]+|C\\$[A-Z0-9]+|CBL_[A-Z0-9_]+|__[A-Z0-9_]+)$'";
        long total = one("SELECT count(*) " + base);
        long sys = one("SELECT count(*) " + base + system);
        long resolved = one("SELECT count(*) " + base + " AND (" + IN_ESTATE + ")");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("system_api", sys);
        m.put("resolved_in_estate", resolved);
        long denominator = total - sys;
        m.put("resolved_pct", denominator == 0 ? 100.0 : Math.round(resolved * 10000.0 / denominator) / 100.0);
        return m;
    }

    public List<Map<String, Object>> parseFailures() {
        return jdbc.queryForList("SELECT path, type, parse_error FROM member WHERE parse_status = 'failed' ORDER BY path");
    }

    public List<Map<String, Object>> unknownMembers() {
        return jdbc.queryForList("SELECT path, classify_reason FROM member WHERE type = 'unknown' ORDER BY path");
    }

    public List<Map<String, Object>> unresolvedCalls() {
        return jdbc.queryForList("""
                SELECT p.program_id AS program, m.path AS member, u.line, u.target_expr, u.reason
                FROM unresolved_call u JOIN program p ON p.id = u.program_id LEFT JOIN member m ON m.id = u.member_id ORDER BY 1, 3""");
    }

    public List<Map<String, Object>> callsToMissingPrograms() {
        return jdbc.queryForList("""
                SELECT d.name AS target, count(*) AS calls FROM graph_edge e JOIN graph_node d ON d.id = e.dst_id
                WHERE e.type IN ('CALLS','LINKS','XCTLS') AND NOT (""" + IN_ESTATE + """
                ) GROUP BY d.name ORDER BY d.name""");
    }

    public List<Map<String, Object>> missingProcs() {
        return jdbc.queryForList("SELECT DISTINCT replace(d.feature, 'MISSING PROC ', '') AS proc, m.path FROM dialect_feature d JOIN member m ON m.id = d.member_id WHERE d.feature LIKE 'MISSING PROC %' ORDER BY 1, 2");
    }

    public List<Map<String, Object>> missingCopybooks() {
        return jdbc.queryForList("SELECT DISTINCT c.name, m.path FROM missing_copybook c JOIN member m ON m.id = c.member_id WHERE " + NOT_SYMBOLIC_MAP + " ORDER BY 1, 2");
    }

    /** Programs with no caller, no JCL step and no transaction. A candidate only: scheduler entries are invisible here. */
    public List<Map<String, Object>> deadCodeCandidates() {
        return jdbc.queryForList("""
                SELECT p.program_id, m.path FROM program p JOIN member m ON m.id = p.member_id
                WHERE p.parent_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM graph_edge e JOIN graph_node d ON d.id = e.dst_id
                                  WHERE d.kind = 'program' AND d.name = upper(p.program_id) AND e.type IN ('CALLS','LINKS','XCTLS'))
                  AND NOT EXISTS (SELECT 1 FROM jcl_step s WHERE upper(s.pgm) = upper(p.program_id))
                  AND NOT EXISTS (SELECT 1 FROM graph_edge e JOIN graph_node n ON n.id = e.src_id WHERE n.kind = 'program' AND n.name = upper(p.program_id) AND e.type = 'RUNS_IN')
                ORDER BY 1""");
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query(sql, rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    private long one(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }
}
