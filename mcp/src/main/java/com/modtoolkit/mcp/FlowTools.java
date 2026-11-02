package com.modtoolkit.mcp;

import static com.modtoolkit.mcp.LegacyTools.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** job_flow, screen_flow and trace_field. */
@Component
public class FlowTools implements ToolSet {
    private final JdbcTemplate jdbc;
    private final Lookup lookup;

    public FlowTools(JdbcTemplate jdbc, Lookup lookup) {
        this.jdbc = jdbc;
        this.lookup = lookup;
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("job_flow", "Steps in order for a job, or the jobs that run a program: PGM, datasets in and out, conditions, restart points, scheduler hints.",
                        schema("\"job\":{\"type\":\"string\"},\"program\":{\"type\":\"string\"}"), false, (a, w) -> jobFlow(a)),
                new ToolDef("screen_flow", "A BMS map or a transaction: fields, owning programs, and where known validations and next-map transitions.",
                        schema("\"map\":{\"type\":\"string\"},\"transaction\":{\"type\":\"string\"}"), false, (a, w) -> screenFlow(a)),
                new ToolDef("trace_field", "Field lineage: MOVE and COMPUTE chain, CALL USING and COMMAREA crossings, file records and table columns read or written.",
                        schema("\"data_item\":{\"type\":\"string\"},\"program\":{\"type\":\"string\"},\"direction\":{\"type\":\"string\",\"enum\":[\"upstream\",\"downstream\"]},\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":25}", "data_item"), false, (a, w) -> traceField(a)));
    }

    private List<?> jobFlow(JsonNode a) {
        String job = a.path("job").asText(null), program = a.path("program").asText(null);
        if ((job == null) == (program == null)) throw new ToolError("BAD_ARGUMENT", "pass exactly one of job or program", "for example {\"job\":\"POSTTRAN\"}");
        List<String> jobs;
        if (job != null) {
            jobs = jdbc.queryForList("SELECT name FROM jcl_job WHERE upper(name) = upper(?)", String.class, job);
            if (jobs.isEmpty()) throw lookup.notFound("job", job, "SELECT name FROM jcl_job WHERE name % ? ORDER BY similarity(name, ?) DESC LIMIT 5");
        } else {
            jobs = jdbc.queryForList("SELECT DISTINCT j.name FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id WHERE upper(s.pgm) = upper(?) ORDER BY 1", String.class, program);
            if (jobs.isEmpty()) lookup.programNameOrKnownTarget(program);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : jobs) {
            for (Map<String, Object> s : jdbc.queryForList("""
                    SELECT j.name AS job, s.id AS step_id, s.seq, s.name AS step, s.pgm, s.proc, s.cond, s.params::text AS params, jm.path AS job_member, coalesce(sm.path, jm.path) AS member, s.line, j.line AS job_line
                    FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id JOIN member jm ON jm.id = j.member_id LEFT JOIN member sm ON sm.id = s.source_member_id
                    WHERE j.name = ? ORDER BY j.id, s.seq""", name)) {
                Map<String, Object> row = new LinkedHashMap<>(s);
                long stepId = ((Number) row.remove("step_id")).longValue();
                List<Map<String, Object>> dd = jdbc.queryForList("SELECT dd_name, dsn, disp, gdg_rel, line FROM jcl_dd WHERE step_id = ? ORDER BY id", stepId);
                List<Map<String, Object>> in = new ArrayList<>(), outs = new ArrayList<>(), other = new ArrayList<>();
                for (Map<String, Object> d : dd) {
                    String disp = String.valueOf(d.get("disp")).toUpperCase();
                    if (d.get("dsn") == null) other.add(d);
                    else if (disp.startsWith("(NEW") || disp.equals("NEW") || disp.startsWith("(MOD") || disp.equals("MOD") || disp.startsWith("(OLD") || disp.equals("OLD")) outs.add(d);
                    else in.add(d);
                }
                row.put("datasets_in", in);
                row.put("datasets_out", outs);
                row.put("other_dd", other);
                String jsonParams = String.valueOf(row.remove("params"));
                row.put("parm", jsonField(jsonParams, "PARM"));
                row.put("restart_point", row.get("step") != null);
                out.add(row);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("job", name);
            summary.put("scheduler_hints", schedulerHints(name));
            out.add(summary);
        }
        return out;
    }

    private static Object jsonField(String json, String field) {
        try {
            JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return n.hasNonNull(field) ? n.get(field).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Scheduler exports are not parsed in v1; a line naming the job is still a useful pointer. */
    private List<Map<String, Object>> schedulerHints(String job) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.path AS member, x.n AS line, trim(x.l) AS text
                FROM member m CROSS JOIN LATERAL regexp_split_to_table(m.normalized_text, E'\\n') WITH ORDINALITY AS x(l, n)
                WHERE m.type = 'unknown' AND m.normalized_text IS NOT NULL AND lower(m.path) ~ '(sched|ca7|controlm|control-m|tws|opc)' AND x.l ~* ('\\m' || ? || '\\M')
                ORDER BY 1, 2 LIMIT 10""", job);
        return rows;
    }

    private List<?> screenFlow(JsonNode a) {
        String map = a.path("map").asText(null), tx = a.path("transaction").asText(null);
        if ((map == null) == (tx == null)) throw new ToolError("BAD_ARGUMENT", "pass exactly one of map or transaction", "for example {\"map\":\"COSGN0A\"}");
        List<String> maps = new ArrayList<>();
        Map<String, Object> head = new LinkedHashMap<>();
        if (tx != null) {
            List<String> programs = jdbc.queryForList("""
                    SELECT p.name FROM graph_edge e JOIN graph_node t ON t.id = e.dst_id JOIN graph_node p ON p.id = e.src_id
                    WHERE e.type = 'RUNS_IN' AND t.kind = 'transaction' AND upper(t.name) = upper(?)""", String.class, tx);
            if (programs.isEmpty()) throw lookup.notFound("transaction", tx, "SELECT name FROM graph_node WHERE kind = 'transaction' AND name % ? ORDER BY similarity(name, ?) DESC LIMIT 5");
            head.put("transaction", tx.toUpperCase());
            head.put("programs", programs);
            maps.addAll(jdbc.queryForList("""
                    SELECT DISTINCT d.name FROM graph_edge e JOIN graph_node s ON s.id = e.src_id JOIN graph_node d ON d.id = e.dst_id
                    WHERE e.type IN ('SENDS_MAP','RECEIVES_MAP') AND s.name = ANY(?::text[]) ORDER BY 1""", String.class, (Object) ("{" + String.join(",", programs) + "}")));
        } else {
            maps.add(map);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (!head.isEmpty()) out.add(head);
        for (String m : maps) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT b.id, b.mapset, b.name, x.path AS member, b.line FROM bms_map b JOIN member x ON x.id = b.member_id WHERE upper(b.name) = upper(?) ORDER BY x.path", m);
            if (rows.isEmpty()) {
                if (tx == null) throw lookup.notFound("map", m, "SELECT name FROM bms_map WHERE name % ? ORDER BY similarity(name, ?) DESC LIMIT 5");
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("map", m);
                r.put("status", "no BMS source for this map in the export");
                out.add(r);
                continue;
            }
            for (Map<String, Object> row : rows) {
                Map<String, Object> r = new LinkedHashMap<>(row);
                long id = ((Number) r.remove("id")).longValue();
                r.put("fields", jdbc.queryForList("SELECT name, \"row\", col, length, attrs::text AS attrs, line FROM bms_field WHERE map_id = ? ORDER BY id", id));
                r.put("owning_programs", jdbc.queryForList("""
                        SELECT DISTINCT p.name AS program, e.type AS edge_type, x.path AS member, e.line
                        FROM graph_edge e JOIN graph_node n ON n.id = e.dst_id JOIN graph_node p ON p.id = e.src_id JOIN member x ON x.id = e.member_id
                        WHERE n.kind = 'map' AND upper(n.name) = upper(?) AND e.type IN ('SENDS_MAP','RECEIVES_MAP') ORDER BY 1, 2, 4""", m));
                r.put("screen_flow", knowledge(m));
                out.add(r);
            }
        }
        return out;
    }

    private Object knowledge(String map) {
        List<Map<String, Object>> a = jdbc.queryForList("""
                SELECT k.id AS artifact_id, k.status, k.content::text AS content FROM knowledge_artifact k
                WHERE k.kind = 'screen_flow' AND upper(k.content->>'map') = upper(?) AND k.status <> 'rejected' ORDER BY k.status = 'reviewed' DESC, k.id LIMIT 1""", map);
        return a.isEmpty() ? Map.of("status", "not_available", "reason", "the knowledge pass has not described validations and transitions for this map") : a.get(0);
    }

    private List<?> traceField(JsonNode a) {
        String item = a.path("data_item").asText("").strip();
        if (item.isEmpty()) throw new ToolError("BAD_ARGUMENT", "data_item is required", "for example {\"data_item\":\"ACCT-CURR-BAL\"}");
        String program = a.path("program").asText(null);
        String direction = a.path("direction").asText("downstream");
        if (!direction.equals("upstream") && !direction.equals("downstream")) throw new ToolError("BAD_ARGUMENT", "direction must be upstream or downstream", "the default is downstream");
        int depth = a.path("depth").asInt(6);
        if (depth < 1 || depth > 25) throw new ToolError("BAD_ARGUMENT", "depth must be between 1 and 25", "start with 6");
        String from = direction.equals("downstream") ? "src_id" : "dst_id";
        String to = direction.equals("downstream") ? "dst_id" : "src_id";
        String start = "n.kind = 'data_item' AND upper(n.name) = upper(?)" + (program != null ? " AND upper(n.scope) = upper(?)" : "");
        Object[] args = program != null ? new Object[] {item, program, depth} : new Object[] {item, depth};
        long found = program != null
                ? jdbc.queryForObject("SELECT count(*) FROM graph_node n WHERE " + start, Long.class, item, program)
                : jdbc.queryForObject("SELECT count(*) FROM graph_node n WHERE " + start, Long.class, item);
        if (found == 0) throw lookup.notFound("data item", item, "SELECT name FROM graph_node WHERE kind = 'data_item' AND name % ? ORDER BY similarity(name, ?) DESC LIMIT 5");
        List<Map<String, Object>> edges = jdbc.queryForList("""
                WITH RECURSIVE w(edge_id, node_id, depth, path) AS (
                  SELECT e.id, e.%2$s, 1, ARRAY[n.id, e.%2$s] FROM graph_node n JOIN graph_edge e ON e.%1$s = n.id
                  WHERE %3$s AND e.type = 'MOVES_TO'
                  UNION ALL
                  SELECT e.id, e.%2$s, w.depth + 1, w.path || e.%2$s FROM w JOIN graph_edge e ON e.%1$s = w.node_id
                  WHERE w.depth < ? AND e.type = 'MOVES_TO' AND NOT e.%2$s = ANY(w.path)
                )
                SELECT DISTINCT min(w.depth) OVER (PARTITION BY e.id) AS depth, s.scope AS from_program, s.name AS from_item, d.scope AS to_program, d.name AS to_item,
                       e.attrs->>'op' AS op, m.path AS member, e.line
                FROM w JOIN graph_edge e ON e.id = w.edge_id JOIN graph_node s ON s.id = e.src_id JOIN graph_node d ON d.id = e.dst_id LEFT JOIN member m ON m.id = e.member_id
                ORDER BY 1, 2, 3, 5, 8""".formatted(from, to, start), args);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : edges) {
            Map<String, Object> r = new LinkedHashMap<>(e);
            r.put("kind", "flow");
            out.add(r);
        }
        out.addAll(touches(item, program, edges, direction));
        return out;
    }

    /** Where the trace meets the outside world: host variables against table columns, and record I/O. */
    private List<Map<String, Object>> touches(String item, String program, List<Map<String, Object>> edges, String direction) {
        Map<String, String> reached = new LinkedHashMap<>();
        String sideItem = direction.equals("downstream") ? "to_item" : "from_item";
        String sideProg = direction.equals("downstream") ? "to_program" : "from_program";
        for (Map<String, Object> e : edges) reached.put(e.get(sideProg) + "\u0000" + e.get(sideItem), "");
        if (program != null) {
            reached.put(program.toUpperCase() + "\u0000" + item.toUpperCase(), "");
        } else {
            for (String p : jdbc.queryForList("SELECT scope FROM graph_node WHERE kind = 'data_item' AND upper(name) = upper(?)", String.class, item)) reached.put(p + "\u0000" + item.toUpperCase(), "");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : reached.keySet()) {
            String[] k = key.split("\u0000");
            for (Map<String, Object> s : jdbc.queryForList("""
                    SELECT 'table' AS kind, p.program_id AS program, ? AS item, s.stmt_type, s.tables::text AS tables, m.path AS member, s.line AS line
                    FROM sql_stmt s JOIN program p ON p.id = s.program_id JOIN member m ON m.id = s.member_id
                    WHERE p.program_id = ? AND s.host_vars ?? ? ORDER BY s.line""", k[1], k[0], k[1])) {
                out.add(s);
            }
            for (Map<String, Object> s : jdbc.queryForList("""
                    SELECT 'file' AS kind, ? AS program, d.name AS record, d.section, d.line, m.path AS member
                    FROM data_item d JOIN program p ON p.id = d.program_id JOIN member m ON m.id = d.owner_member_id
                    WHERE p.program_id = ? AND upper(d.name) = upper(?) AND d.section = 'FILE'""", k[0], k[0], k[1])) {
                out.add(s);
            }
        }
        return out;
    }
}
