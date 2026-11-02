package com.modtoolkit.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.discovery.DiscoveryReports;
import com.modtoolkit.retrieval.GraphQueries;
import com.modtoolkit.retrieval.HybridSearch;
import com.modtoolkit.retrieval.SearchHit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The read tools over discovery facts. Names and signatures follow the contract in Module 4. */
@Component
public class LegacyTools {
    private static final Set<String> PROGRAM_PARTS = Set.of("source", "summary", "rules", "data", "callers", "callees", "tables", "maps", "jcl", "migration_note", "modern");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final DiscoveryReports reports;
    private final GraphQueries graph;
    private final HybridSearch hybrid;
    private final org.springframework.beans.factory.ObjectProvider<com.modtoolkit.retrieval.Embedder> embedder;
    private final Lookup lookup;

    public LegacyTools(JdbcTemplate jdbc, DiscoveryReports reports, GraphQueries graph, HybridSearch hybrid, org.springframework.beans.factory.ObjectProvider<com.modtoolkit.retrieval.Embedder> embedder, Lookup lookup) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.graph = graph;
        this.hybrid = hybrid;
        this.embedder = embedder;
        this.lookup = lookup;
    }

    static String schema(String props, String... required) {
        StringBuilder r = new StringBuilder();
        for (String q : required) r.append(r.isEmpty() ? "" : ",").append('"').append(q).append('"');
        return "{\"type\":\"object\",\"properties\":{" + props + (props.isEmpty() ? "" : ",") + "\"cursor\":{\"type\":\"string\",\"description\":\"next_cursor from the previous page\"}},\"required\":[" + r + "],\"additionalProperties\":false}";
    }

    public List<ToolDef> tools() {
        return List.of(
                new ToolDef("inventory_summary", "Counts by member type and dialect, parse status, unresolved CALLs and dead-code candidates for the whole estate.", schema(""), false, (a, w) -> inventory()),
                new ToolDef("search", "Find code and knowledge. Ranked hits with member, line range, snippet, score and artifact status.",
                        schema("\"query\":{\"type\":\"string\"},\"kinds\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"code\",\"summary\",\"rule\",\"dictionary\",\"job\",\"map\",\"modern\"]}},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}", "query"), false, (a, w) -> search(a)),
                new ToolDef("get_program", "A program bundle. Ask only for the parts you need through include.",
                        schema("\"name\":{\"type\":\"string\"},\"include\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"source\",\"summary\",\"rules\",\"data\",\"callers\",\"callees\",\"tables\",\"maps\",\"jcl\",\"migration_note\",\"modern\"]}}", "name"), false, (a, w) -> getProgram(a)),
                new ToolDef("get_source", "Exact UTF-8 lines of a member with original and normalized line numbers.",
                        schema("\"member\":{\"type\":\"string\"},\"line_start\":{\"type\":\"integer\",\"minimum\":1},\"line_end\":{\"type\":\"integer\",\"minimum\":1}", "member"), false, (a, w) -> getSource(a)),
                new ToolDef("get_copybook", "A record layout: level, name, PIC, usage, offset, byte length, OCCURS, REDEFINES, 88 values. expanded_for gives the layout as a program's COPY REPLACING saw it.",
                        schema("\"name\":{\"type\":\"string\"},\"expanded_for\":{\"type\":\"string\"}", "name"), false, (a, w) -> getCopybook(a)),
                new ToolDef("who_calls", "Callers of a program to a depth: CALL static or dynamic, CICS LINK and XCTL, JCL steps, each with member and line.",
                        schema("\"program\":{\"type\":\"string\"},\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}", "program"), false, (a, w) -> calls(a, true)),
                new ToolDef("calls_from", "Programs a program reaches, to a depth, each edge with type, member and line.",
                        schema("\"program\":{\"type\":\"string\"},\"depth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}", "program"), false, (a, w) -> calls(a, false)),
                new ToolDef("business_rules", "Business rules with condition, action, line range, confidence and review status. Reviewed rules first; rejected ones are not shown unless asked for.",
                        schema("\"program\":{\"type\":\"string\"},\"paragraph\":{\"type\":\"string\"},\"data_item\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"draft\",\"reviewed\",\"rejected\"]}"), false, (a, w) -> businessRules(a)),
                new ToolDef("table_usage", "Programs touching a DB2 table, grouped by statement type, with member and lines.",
                        schema("\"table\":{\"type\":\"string\"}", "table"), false, (a, w) -> tableUsage(a)));
    }

    private List<?> inventory() {
        Map<String, Object> s = new LinkedHashMap<>(reports.summary());
        return List.of(s);
    }

    private List<?> search(JsonNode a) {
        String q = need(a, "query");
        List<String> kinds = strings(a.path("kinds"));
        int limit = Math.min(a.path("limit").asInt(20), 100);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SearchHit h : hybrid.search(q, kinds, limit, embedder.getIfAvailable()).hits()) out.add(hit(h));
        return out;
    }

    static Map<String, Object> hit(SearchHit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", h.kind());
        m.put("member", h.member());
        m.put("line_start", h.lineStart());
        m.put("line_end", h.lineEnd());
        m.put("unit", h.unit());
        m.put("snippet", h.snippet());
        m.put("score", Math.round(h.score() * 10000) / 10000.0);
        m.put("artifact_id", h.artifactId());
        m.put("status", h.status());
        return m;
    }

    private List<?> getSource(JsonNode a) {
        Map<String, Object> m = lookup.member(need(a, "member"));
        String text = jdbc.queryForObject("SELECT normalized_text FROM member WHERE id = ?", String.class, m.get("id"));
        if (text == null) throw new ToolError("NOT_TEXT", "member " + m.get("path") + " is binary", "it has no source lines");
        List<String> lines = Arrays.asList(text.split("\n", -1));
        int s = a.path("line_start").asInt(1), e = a.path("line_end").asInt(lines.size());
        if (s > lines.size()) throw new ToolError("OUT_OF_RANGE", "line_start " + s + " is past the end; the member has " + lines.size() + " lines", "use a line_start up to " + lines.size());
        if (e < s) throw new ToolError("OUT_OF_RANGE", "line_end is before line_start", "pass line_start <= line_end");
        e = Math.min(e, lines.size());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = s; i <= e; i++) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("member", m.get("path"));
            l.put("line", i);
            l.put("original_line", i);
            l.put("text", lines.get(i - 1));
            out.add(l);
        }
        return out;
    }

    private List<?> getProgram(JsonNode a) {
        Map<String, Object> p = lookup.program(need(a, "name"));
        long id = ((Number) p.get("id")).longValue();
        String name = (String) p.get("program_id");
        List<String> include = strings(a.path("include"));
        for (String part : include) {
            if (!PROGRAM_PARTS.contains(part)) throw new ToolError("BAD_ARGUMENT", "unknown include part " + part, "use one of " + new java.util.TreeSet<>(PROGRAM_PARTS));
        }
        Set<String> want = include.isEmpty() ? new LinkedHashSet<>(List.of("callers", "callees", "tables", "maps", "jcl")) : new LinkedHashSet<>(include);
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(part("program", name, row(p)));
        for (String w : want) {
            Object v = switch (w) {
                case "source" -> source(p);
                case "callers" -> graph.callers(name, 1);
                case "callees" -> graph.callees(name, 1);
                case "tables" -> tables(id);
                case "maps" -> maps(id);
                case "jcl" -> jcl(name);
                case "data" -> data(id);
                default -> extraPart(w, id, name);
            };
            parts.add(part(w, name, v));
        }
        return parts;
    }

    /** Overridden as knowledge arrives; absent data says why instead of pretending to be empty. */
    protected Object extraPart(String part, long programId, String name) {
        return lookup.knowledgePart(part, programId, name);
    }

    private static Map<String, Object> part(String kind, String program, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("part", kind);
        m.put("program", program);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> row(Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("program_id", p.get("program_id"));
        m.put("kind", p.get("kind"));
        m.put("member", p.get("path"));
        m.put("line_start", p.get("line_start"));
        m.put("line_end", p.get("line_end"));
        m.put("metrics", parseJson(p.get("metrics")));
        return m;
    }

    private static Object parseJson(Object pg) {
        try {
            return pg == null ? null : JSON.readValue(pg.toString(), new TypeReference<Object>() {});
        } catch (Exception e) {
            return pg.toString();
        }
    }

    private Object source(Map<String, Object> p) {
        String text = jdbc.queryForObject("SELECT normalized_text FROM member WHERE id = ?", String.class, p.get("member_id"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("member", p.get("path"));
        m.put("line_start", 1);
        m.put("text", text);
        return m;
    }

    private List<Map<String, Object>> tables(long id) {
        return jdbc.queryForList("""
                SELECT t AS table_name, s.stmt_type, m.path AS member, s.line AS line_start, s.line_end
                FROM sql_stmt s CROSS JOIN LATERAL jsonb_array_elements_text(s.tables) t JOIN member m ON m.id = s.member_id
                WHERE s.program_id = ? AND s.stmt_type <> 'DECLARE TABLE' ORDER BY 1, 2, 4""", id);
    }

    private List<Map<String, Object>> maps(long id) {
        return jdbc.queryForList("""
                SELECT c.command, c.resource_name AS map, c.attrs->>'MAPSET' AS mapset, m.path AS member, c.line
                FROM cics_cmd c JOIN member m ON m.id = c.member_id WHERE c.program_id = ? AND c.resource_kind = 'map' ORDER BY c.line""", id);
    }

    private List<Map<String, Object>> jcl(String program) {
        return jdbc.queryForList("""
                SELECT j.name AS job, s.seq AS step_seq, s.name AS step, m.path AS member, s.line
                FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id JOIN member m ON m.id = j.member_id
                WHERE upper(s.pgm) = upper(?) ORDER BY j.name, s.seq""", program);
    }

    private List<Map<String, Object>> data(long id) {
        return jdbc.queryForList("""
                SELECT d.level, d.name, d.pic, d.usage, d.\"offset\", d.length, d.occurs_max AS occurs, d.redefines_name AS redefines, d.section, m.path AS member, d.line
                FROM data_item d JOIN member m ON m.id = d.owner_member_id WHERE d.program_id = ? ORDER BY d.id""", id);
    }

    private List<?> calls(JsonNode a, boolean callers) {
        String program = need(a, "program");
        int depth = a.path("depth").asInt(1);
        if (depth < 1 || depth > 20) throw new ToolError("BAD_ARGUMENT", "depth must be between 1 and 20", "start with 1 or 2");
        String name = lookup.programNameOrKnownTarget(program);
        List<Map<String, Object>> edges = callers ? new ArrayList<>(graph.callers(name, depth)) : new ArrayList<>(graph.callees(name, depth));
        if (callers) edges.addAll(lookup.jclCallers(name));
        return edges;
    }

    private List<?> businessRules(JsonNode a) {
        String program = a.path("program").asText(null), paragraph = a.path("paragraph").asText(null), item = a.path("data_item").asText(null), status = a.path("status").asText(null);
        if (program == null && paragraph == null && item == null) throw new ToolError("BAD_ARGUMENT", "pass program, paragraph or data_item", "for example {\"program\":\"CBACT04C\"}");
        if (program != null) lookup.program(program);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.program_id AS program, r.rule->>'rule_id' AS rule_id, r.rule->>'statement' AS statement, r.rule->>'condition' AS condition, r.rule->>'action' AS action,
                       r.rule->>'paragraph' AS paragraph, (r.rule->>'line_start')::int AS line_start, (r.rule->>'line_end')::int AS line_end, (r.rule->>'confidence')::float AS confidence,
                       r.rule->'data_items' AS data_items, a.id AS artifact_id, a.status, m.path AS member
                FROM knowledge_artifact a CROSS JOIN LATERAL jsonb_array_elements(a.content->'rules') r(rule)
                JOIN program p ON p.id = a.unit_id AND a.unit_kind = 'program' JOIN member m ON m.id = a.member_id
                WHERE a.kind = 'rule' AND (?::text IS NULL OR upper(p.program_id) = upper(?)) AND (?::text IS NULL OR upper(r.rule->>'paragraph') = upper(?))
                  AND (?::text IS NULL OR r.rule->'data_items' ?? upper(?)) AND (?::text IS NOT NULL OR a.status <> 'rejected') AND (?::text IS NULL OR a.status = ?)
                ORDER BY (a.status = 'reviewed') DESC, p.program_id, (r.rule->>'line_start')::int""", program, program, paragraph, paragraph, item, item, status, status, status);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("data_items", parseJson(r.get("data_items")));
            out.add(m);
        }
        return out;
    }

    private List<?> tableUsage(JsonNode a) {
        String table = need(a, "table");
        String bare = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.program_id AS program, s.stmt_type, t AS table_name, m.path AS member, s.line AS line_start, s.line_end
                FROM sql_stmt s CROSS JOIN LATERAL jsonb_array_elements_text(s.tables) t
                JOIN program p ON p.id = s.program_id JOIN member m ON m.id = s.member_id
                WHERE upper(regexp_replace(t, '^.*\\.', '')) = upper(?) AND s.stmt_type <> 'DECLARE TABLE'
                ORDER BY s.stmt_type, p.program_id, s.line""", bare);
        if (rows.isEmpty() && jdbc.queryForObject("SELECT count(*) FROM graph_node WHERE kind = 'table' AND upper(name) = upper(?)", Long.class, bare) == 0
                && jdbc.queryForObject("SELECT count(*) FROM db2_table WHERE upper(name) = upper(?)", Long.class, bare) == 0) {
            throw lookup.notFound("table", table, "SELECT name FROM graph_node WHERE kind = 'table' AND name % ? ORDER BY similarity(name, ?) DESC LIMIT 5");
        }
        return rows;
    }

    private List<?> getCopybook(JsonNode a) {
        Map<String, Object> cb = lookup.member(need(a, "name"), "copybook");
        long member = ((Number) cb.get("id")).longValue();
        String sql = """
                SELECT d.id, d.level, d.name, d.pic, d.usage, d.\"offset\", d.length, d.occurs_max AS occurs, d.odo_ref AS occurs_depending_on,
                       d.redefines_name AS redefines, d.line, m.path AS member,
                       (SELECT jsonb_agg(jsonb_build_object('name', c.name, 'values', c.\"values\") ORDER BY c.id) FROM condition_value c WHERE c.data_item_id = d.id)::text AS condition_values
                FROM data_item d JOIN member m ON m.id = d.owner_member_id WHERE d.owner_member_id = ? AND %s ORDER BY d.id""";
        List<Map<String, Object>> rows;
        String forProgram = a.path("expanded_for").asText(null);
        if (forProgram != null && !forProgram.isBlank()) {
            long pid = ((Number) lookup.program(forProgram).get("id")).longValue();
            rows = jdbc.queryForList(sql.formatted("d.program_id = ?"), member, pid);
            if (rows.isEmpty()) throw new ToolError("NOT_FOUND", forProgram + " does not COPY " + cb.get("name"), "drop expanded_for, or check calls with get_program");
        } else {
            rows = jdbc.queryForList(sql.formatted("d.program_id IS NULL"), member);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("condition_values", parseJson(r.get("condition_values")));
            m.put("dictionary", lookup.dictionaryEntry(String.valueOf(r.get("name"))));
            out.add(m);
        }
        return out;
    }

    private static String need(JsonNode a, String field) {
        String v = a.path(field).asText("").strip();
        if (v.isEmpty()) throw new ToolError("BAD_ARGUMENT", field + " is required", "see the tool's input schema");
        return v;
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> out.add(x.asText()));
        return out;
    }
}
