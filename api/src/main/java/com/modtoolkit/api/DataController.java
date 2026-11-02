package com.modtoolkit.api;

import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.core.migration.MigrationService;
import com.modtoolkit.discovery.DiscoveryReports;
import com.modtoolkit.knowledge.EmbedderFactory;
import com.modtoolkit.retrieval.Embedder;
import com.modtoolkit.retrieval.GraphQueries;
import com.modtoolkit.retrieval.HybridSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the read pages show: inventory, program explorer, graphs, gap report and tracker. Any signed-in role. */
@RestController
@RequestMapping("/api")
@ConditionalOnWebApplication
public class DataController {
    private final JdbcTemplate jdbc;
    private final DiscoveryReports reports;
    private final GraphQueries graph;
    private final HybridSearch search;
    private final MigrationService migration;

    public DataController(JdbcTemplate jdbc, DiscoveryReports reports, GraphQueries graph, HybridSearch search, MigrationService migration) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.graph = graph;
        this.search = search;
        this.migration = migration;
    }

    @GetMapping("/inventory")
    public Map<String, Object> inventory() {
        Map<String, Object> m = new LinkedHashMap<>(reports.summary());
        m.put("parse_failures", reports.parseFailures());
        m.put("unresolved_calls", reports.unresolvedCalls());
        m.put("calls_to_missing_programs", reports.callsToMissingPrograms());
        m.put("missing_copybooks", reports.missingCopybooks());
        m.put("missing_procs", reports.missingProcs());
        m.put("dead_code", reports.deadCodeCandidates());
        m.put("unknown_members", reports.unknownMembers());
        return m;
    }

    @GetMapping("/programs")
    public List<Map<String, Object>> programs(@RequestParam(required = false) String q, @RequestParam(defaultValue = "200") int limit) {
        return jdbc.queryForList("""
                SELECT p.program_id, p.kind, m.path AS member, p.line_start, p.line_end, p.metrics::text AS metrics, coalesce(s.stage,'analyzed') AS stage
                FROM program p JOIN member m ON m.id = p.member_id LEFT JOIN migration_status s ON s.program_id = p.id
                WHERE p.parent_id IS NULL AND (?::text IS NULL OR p.program_id ILIKE '%' || ? || '%') ORDER BY p.program_id LIMIT ?""", q, q, Math.min(limit, 1000));
    }

    @GetMapping("/programs/{name}")
    public Map<String, Object> program(@PathVariable String name) {
        Map<String, Object> p;
        try {
            p = jdbc.queryForMap("""
                    SELECT p.id, p.program_id, p.kind, p.member_id, m.path AS member, p.line_start, p.line_end, p.metrics::text AS metrics, m.normalized_text, coalesce(s.stage,'analyzed') AS stage
                    FROM program p JOIN member m ON m.id = p.member_id LEFT JOIN migration_status s ON s.program_id = p.id
                    WHERE upper(p.program_id) = upper(?) ORDER BY length(m.path) LIMIT 1""", name);
        } catch (EmptyResultDataAccessException e) {
            throw new MigrationService.NotFound("no program named " + name);
        }
        long id = ((Number) p.get("id")).longValue();
        String pgm = (String) p.get("program_id");
        Map<String, Object> out = new LinkedHashMap<>(p);
        out.remove("normalized_text");
        out.put("source", Arrays.asList(String.valueOf(p.get("normalized_text")).split("\n", -1)));
        out.put("paragraphs", jdbc.queryForList("SELECT name, section, line_start, line_end, complexity, reachable FROM paragraph WHERE program_id = ? ORDER BY line_start", id));
        out.put("data_items", jdbc.queryForList("""
                SELECT d.level, d.name, d.pic, d.usage, d."offset", d.length, d.occurs_max AS occurs, d.redefines_name AS redefines, m.path AS member, d.line
                FROM data_item d JOIN member m ON m.id = d.owner_member_id WHERE d.program_id = ? AND d.name IS NOT NULL ORDER BY d.id""", id));
        out.put("callers", graph.callers(pgm, 1));
        out.put("callees", graph.callees(pgm, 1));
        out.put("artifacts", jdbc.queryForList("SELECT id, kind, status, reviewer, content::text AS content, line_start, line_end FROM knowledge_artifact WHERE unit_kind='program' AND unit_id=? AND status <> 'rejected' ORDER BY kind, id", id));
        return out;
    }

    /** Nodes and edges in the shape Cytoscape.js takes, for the three views. */
    @GetMapping("/graph/calls")
    public Map<String, Object> callGraph(@RequestParam String program, @RequestParam(defaultValue = "2") int depth, @RequestParam(defaultValue = "both") String direction) {
        int d = Math.max(1, Math.min(depth, 10));
        List<Map<String, Object>> edges = new ArrayList<>();
        if (!direction.equals("callees")) edges.addAll(graph.callers(program, d));
        if (!direction.equals("callers")) edges.addAll(graph.callees(program, d));
        List<Map<String, Object>> e = new ArrayList<>();
        for (Map<String, Object> x : edges) e.add(edge((String) x.get("from_program"), (String) x.get("to_program"), (String) x.get("edge_type"), x));
        return elements(e, program.toUpperCase());
    }

    /** What the job-flow view can draw. */
    @GetMapping("/jobs")
    public List<Map<String, Object>> jobNames() {
        return jdbc.queryForList("SELECT j.name, m.path AS member FROM jcl_job j JOIN member m ON m.id = j.member_id ORDER BY j.name, m.path");
    }

    @GetMapping("/graph/job")
    public Map<String, Object> jobGraph(@RequestParam String job) {
        List<Map<String, Object>> e = new ArrayList<>();
        String prev = null;
        for (Map<String, Object> s : jdbc.queryForList("""
                SELECT s.id, s.seq, s.name, s.pgm FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id WHERE upper(j.name) = upper(?) ORDER BY s.seq""", job)) {
            String step = s.get("seq") + ":" + s.get("name");
            if (prev != null) e.add(edge(prev, step, "NEXT", s));
            prev = step;
            if (s.get("pgm") != null) e.add(edge(step, (String) s.get("pgm"), "RUNS", s));
            for (Map<String, Object> dd : jdbc.queryForList("SELECT dd_name, dsn, disp FROM jcl_dd WHERE step_id = ? AND dsn IS NOT NULL", s.get("id"))) {
                boolean out = String.valueOf(dd.get("disp")).toUpperCase().matches("\\(?(NEW|MOD|OLD).*");
                e.add(out ? edge(step, (String) dd.get("dsn"), "WRITES", dd) : edge((String) dd.get("dsn"), step, "READS", dd));
            }
        }
        return elements(e, job.toUpperCase());
    }

    @GetMapping("/graph/lineage")
    public Map<String, Object> lineage(@RequestParam String item, @RequestParam(required = false) String program, @RequestParam(defaultValue = "4") int depth) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH RECURSIVE w(id, depth, path) AS (
                  SELECT e.id, 1, ARRAY[n.id, e.dst_id] FROM graph_node n JOIN graph_edge e ON e.src_id = n.id
                  WHERE n.kind = 'data_item' AND upper(n.name) = upper(?) AND (?::text IS NULL OR upper(n.scope) = upper(?)) AND e.type = 'MOVES_TO'
                  UNION ALL
                  SELECT e.id, w.depth + 1, w.path || e.dst_id FROM w JOIN graph_edge p ON p.id = w.id JOIN graph_edge e ON e.src_id = p.dst_id
                  WHERE w.depth < ? AND e.type = 'MOVES_TO' AND NOT e.dst_id = ANY(w.path)
                )
                SELECT DISTINCT s.scope || '.' || s.name AS src, d.scope || '.' || d.name AS dst, e.attrs->>'op' AS op, e.line
                FROM w JOIN graph_edge e ON e.id = w.id JOIN graph_node s ON s.id = e.src_id JOIN graph_node d ON d.id = e.dst_id LIMIT 500""", item, program, program, Math.max(1, Math.min(depth, 12)));
        List<Map<String, Object>> e = new ArrayList<>();
        for (Map<String, Object> r : rows) e.add(edge((String) r.get("src"), (String) r.get("dst"), (String) r.get("op"), r));
        return elements(e, item.toUpperCase());
    }

    private static Map<String, Object> edge(String from, String to, String type, Map<String, Object> attrs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", from);
        m.put("target", to);
        m.put("type", type);
        m.put("line", attrs.get("line"));
        m.put("member", attrs.get("member"));
        return m;
    }

    private static Map<String, Object> elements(List<Map<String, Object>> edges, String root) {
        java.util.Set<String> nodes = new java.util.TreeSet<>();
        for (Map<String, Object> e : edges) {
            nodes.add((String) e.get("source"));
            nodes.add((String) e.get("target"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("root", root);
        m.put("nodes", nodes);
        m.put("edges", edges);
        return m;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q, @RequestParam(required = false) List<String> kinds, @RequestParam(defaultValue = "20") int limit) {
        Embedder e = null;
        String cfg = System.getenv("TOOLKIT_CONFIG");
        if (cfg != null && Files.exists(Path.of(cfg))) {
            ToolkitConfig.Llm l = ToolkitConfig.load(Path.of(cfg)).llm();
            if (l != null) e = EmbedderFactory.create(l, System::getenv);
        }
        var r = search.search(q, kinds, Math.min(limit, 100), e);
        return Map.of("mode", r.mode(), "hits", r.hits());
    }

    @GetMapping("/gap-report")
    public List<Map<String, Object>> gap(@RequestParam(required = false) String program) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM program WHERE parent_id IS NULL AND (?::text IS NULL OR upper(program_id) = upper(?)) ORDER BY program_id", Long.class, program, program);
        return migration.gapReport(ids);
    }

    @GetMapping("/tracker")
    public Map<String, Object> tracker() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.program_id, p.kind, m.path AS member, coalesce(s.stage,'analyzed') AS stage, s.owner, coalesce(s.test_count,0) AS test_count, coalesce(s.tests_passing,0) AS tests_passing, coalesce(s.target_refs,'[]'::jsonb)::text AS target_refs
                FROM program p JOIN member m ON m.id = p.member_id LEFT JOIN migration_status s ON s.program_id = p.id WHERE p.parent_id IS NULL ORDER BY p.program_id""");
        return Map.of("stages", List.of("analyzed", "brief_generated", "translated", "tests_generated", "tests_passing", "sme_signed_off"), "programs", rows);
    }

    @GetMapping("/captures")
    public List<Map<String, Object>> captures() {
        return jdbc.queryForList("""
                SELECT c.id, c.subject_kind, c.subject_id, c.status, c.source, c.created_at::text AS created_at, (SELECT count(*) FROM fixture f WHERE f.capture_id = c.id) AS fixtures FROM capture c ORDER BY c.id DESC""");
    }

    @GetMapping("/captures/{id}/fixtures")
    public List<Map<String, Object>> fixtures(@PathVariable long id) {
        return jdbc.queryForList("SELECT id, name, input_json::text AS input, expected_json::text AS expected FROM fixture WHERE capture_id = ? ORDER BY name", id);
    }
}
