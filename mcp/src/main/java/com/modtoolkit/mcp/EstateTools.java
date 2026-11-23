package com.modtoolkit.mcp;

import static com.modtoolkit.mcp.LegacyTools.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * estate_map: the names an agent needs before any other tool is useful. Everything here comes from discovery facts,
 * so it answers on an estate where the knowledge pass has never run. Each area is a handful of set queries joined in
 * memory, not one query per row, so a large estate costs the same number of round trips as a small one.
 */
@Component
public class EstateTools implements ToolSet {
    static final List<String> AREAS = List.of("overview", "jobs", "transactions", "screens", "programs", "data");
    /** What to do while summaries and rules are missing; shared with the tools that report them as not available. */
    static final String WITHOUT_KNOWLEDGE = "read the code with get_source and describe it from the lines you read, citing them; the knowledge pass (toolkit knowledge run) adds reviewed summaries and rules";
    private static final int OVERVIEW_ROWS = 25;
    private static final int NAMES = 5;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate jdbc;

    public EstateTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ToolDef> tools() {
        return List.of(new ToolDef("estate_map",
                "Start here on an unfamiliar estate. The entry points and inventory by name: jobs, transactions, screens, programs and data stores, each with member and line. "
                        + "Works before the knowledge pass. area: overview (default; counts, knowledge-pass state, suggested next calls, then the jobs and transactions to start from), "
                        + "jobs, transactions, screens, programs or data. name_prefix narrows a large estate. "
                        + "Then use job_flow, screen_flow, get_program and get_source on the names it returns.",
                schema("\"area\":{\"type\":\"string\",\"enum\":[\"overview\",\"jobs\",\"transactions\",\"screens\",\"programs\",\"data\"],\"description\":\"which list to return; overview when omitted\"},"
                        + "\"name_prefix\":{\"type\":\"string\",\"description\":\"keep only names starting with this, case-insensitive\"}"), false, (a, w) -> estateMap(a)));
    }

    private List<?> estateMap(JsonNode a) {
        String area = a.path("area").asText("overview").strip().toLowerCase(Locale.ROOT);
        if (area.isEmpty()) area = "overview";
        if (!AREAS.contains(area)) throw new ToolError("BAD_ARGUMENT", "unknown area " + a.path("area").asText(), "use one of " + AREAS);
        String prefix = a.path("name_prefix").asText("").strip().toUpperCase(Locale.ROOT);
        return switch (area) {
            case "jobs" -> named(jobs(), "job", prefix);
            case "transactions" -> named(transactions(), "transaction", prefix);
            case "screens" -> named(screens(), "map", prefix);
            case "programs" -> named(programs(), "program", prefix);
            case "data" -> named(data(), "name", prefix);
            default -> overview(prefix);
        };
    }

    private static List<Map<String, Object>> named(List<Map<String, Object>> rows, String field, String prefix) {
        if (prefix.isEmpty()) return rows;
        return rows.stream().filter(r -> String.valueOf(r.get(field)).toUpperCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    // ---- overview ----

    private List<?> overview(String prefix) {
        List<Map<String, Object>> allJobs = jobs(), allTx = transactions(), allData = data();
        // A job that runs a program whose source is here says more about the business than one that only runs utilities.
        List<Map<String, Object>> jobs = new ArrayList<>(named(allJobs, "job", prefix));
        jobs.sort(Comparator.comparing((Map<String, Object> j) -> ((List<?>) j.get("programs_with_source")).isEmpty()));
        List<Map<String, Object>> tx = named(allTx, "transaction", prefix);
        List<Map<String, Object>> shownJobs = jobs.subList(0, Math.min(OVERVIEW_ROWS, jobs.size())), shownTx = tx.subList(0, Math.min(OVERVIEW_ROWS, tx.size()));

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("kind", "overview");
        Map<String, Object> programs = new LinkedHashMap<>();
        programs.put("total", count("SELECT count(*) FROM program"));
        programs.put("by_kind", counts("SELECT coalesce(kind, 'unknown'), count(*) FROM program GROUP BY 1 ORDER BY 1"));
        o.put("programs", programs);
        o.put("jobs", allJobs.size());
        o.put("transactions", allTx.stream().map(t -> t.get("transaction")).distinct().count());
        o.put("screens", count("SELECT count(*) FROM bms_map"));
        Map<String, Object> stores = new LinkedHashMap<>();
        for (String k : List.of("db2_table", "file", "cics_file", "queue")) stores.put(k + "s", allData.stream().filter(d -> k.equals(d.get("kind"))).count());
        stores.put("db2_tables_with_ddl", allData.stream().filter(d -> Boolean.TRUE.equals(d.get("ddl_in_export"))).count());
        stores.put("datasets_in_jcl", count("SELECT count(*) FROM graph_node WHERE kind = 'dataset'"));
        o.put("data", stores);
        o.put("knowledge", knowledge());
        o.put("program_prefixes", jdbc.queryForList("SELECT upper(left(program_id, 2)) AS prefix, count(*) AS programs FROM program GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 12"));
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("jobs_listed", shownJobs.size());
        entries.put("jobs_total", jobs.size());
        entries.put("transactions_listed", shownTx.size());
        entries.put("transactions_total", tx.size());
        o.put("entry_points", entries);
        o.put("next", next(jobs, tx));

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(o);
        out.addAll(shownJobs);
        out.addAll(shownTx);
        return out;
    }

    private Map<String, Object> knowledge() {
        long total = count("SELECT count(*) FROM program");
        long summaries = count("SELECT count(DISTINCT a.unit_id) FROM knowledge_artifact a WHERE a.unit_kind = 'program' AND a.kind = 'summary' AND a.status <> 'rejected'");
        Map<String, Long> rules = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT a.status, coalesce(sum(jsonb_array_length(a.content->'rules')), 0) AS n FROM knowledge_artifact a WHERE a.kind = 'rule' AND jsonb_typeof(a.content->'rules') = 'array' GROUP BY a.status")) {
            rules.put(String.valueOf(r.get("status")), ((Number) r.get("n")).longValue());
        }
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("programs_with_summary", summaries);
        k.put("programs_total", total);
        k.put("rules_reviewed", rules.getOrDefault("reviewed", 0L));
        k.put("rules_draft", rules.getOrDefault("draft", 0L));
        if (summaries < total) k.put("advice", WITHOUT_KNOWLEDGE);
        return k;
    }

    /** Calls an agent can copy as they are, on names that exist in this estate. */
    private List<String> next(List<Map<String, Object>> jobs, List<Map<String, Object>> tx) {
        List<String> next = new ArrayList<>();
        for (String a : List.of("jobs", "transactions", "screens", "programs", "data")) next.add("estate_map {\"area\":\"" + a + "\"}");
        String program = null;
        if (!jobs.isEmpty()) {
            next.add("job_flow {\"job\":" + quoted(jobs.get(0).get("job")) + "}");
            List<?> withSource = (List<?>) jobs.get(0).get("programs_with_source");
            if (!withSource.isEmpty()) program = String.valueOf(withSource.get(0));
        }
        if (!tx.isEmpty()) {
            next.add("screen_flow {\"transaction\":" + quoted(tx.get(0).get("transaction")) + "}");
            if (program == null && tx.get(0).get("program_member") != null) program = String.valueOf(tx.get(0).get("program"));
        }
        if (program == null) {
            List<String> any = jdbc.queryForList("SELECT program_id FROM program ORDER BY program_id LIMIT 1", String.class);
            if (!any.isEmpty()) program = any.get(0);
        }
        if (program != null) {
            next.add("get_program {\"name\":" + quoted(program) + ",\"include\":[\"callers\",\"callees\",\"tables\",\"jcl\",\"maps\"]}");
            List<String> member = jdbc.queryForList("SELECT m.path FROM program p JOIN member m ON m.id = p.member_id WHERE p.program_id = ? ORDER BY length(m.path), m.path LIMIT 1", String.class, program);
            if (!member.isEmpty()) next.add("get_source {\"member\":" + quoted(member.get(0)) + ",\"line_start\":1,\"line_end\":200}");
        }
        return next;
    }

    // ---- jobs ----

    private List<Map<String, Object>> jobs() {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> j : jdbc.queryForList("SELECT j.id, j.name AS job, m.path AS member, j.line FROM jcl_job j JOIN member m ON m.id = j.member_id ORDER BY j.name, m.path, j.line")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "job");
            r.put("job", j.get("job"));
            r.put("member", j.get("member"));
            r.put("line", j.get("line"));
            r.put("steps", 0);
            r.put("programs", new ArrayList<String>());
            r.put("programs_with_source", new ArrayList<String>());
            byId.put(((Number) j.get("id")).longValue(), r);
        }
        for (Map<String, Object> s : jdbc.queryForList("""
                SELECT s.job_id, upper(s.pgm) AS pgm, EXISTS (SELECT 1 FROM program p WHERE upper(p.program_id) = upper(s.pgm)) AS has_source
                FROM jcl_step s ORDER BY s.job_id, s.seq""")) {
            Map<String, Object> r = byId.get(((Number) s.get("job_id")).longValue());
            if (r == null) continue;
            r.put("steps", (Integer) r.get("steps") + 1);
            String pgm = (String) s.get("pgm");
            if (pgm == null) continue;
            addOnce(r.get("programs"), pgm);
            if (Boolean.TRUE.equals(s.get("has_source"))) addOnce(r.get("programs_with_source"), pgm);
        }
        SchedulerText scheduler = schedulerText();
        for (Map<String, Object> r : byId.values()) r.put("scheduler_hint", scheduler.names(String.valueOf(r.get("job"))));
        return new ArrayList<>(byId.values());
    }

    @SuppressWarnings("unchecked")
    private static void addOnce(Object list, String value) {
        List<String> l = (List<String>) list;
        if (!l.contains(value)) l.add(value);
    }

    /**
     * The same members and the same whole-word match as job_flow's scheduler_hints, read once for every job instead of
     * once per job.
     */
    private SchedulerText schedulerText() {
        List<String> texts = jdbc.queryForList("""
                SELECT m.normalized_text FROM member m
                WHERE m.type = 'unknown' AND m.normalized_text IS NOT NULL AND lower(m.path) ~ '(sched|ca7|controlm|control-m|tws|opc)'""", String.class);
        Set<String> words = new HashSet<>();
        for (String t : texts) {
            var m = WORD.matcher(t);
            while (m.find()) words.add(m.group().toUpperCase(Locale.ROOT));
        }
        return new SchedulerText(texts, words);
    }

    private record SchedulerText(List<String> texts, Set<String> words) {
        boolean names(String job) {
            if (WORD.matcher(job).matches()) return words.contains(job.toUpperCase(Locale.ROOT));
            // A name with #, @ or $ is not one word to the tokenizer above.
            Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(job) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
            return texts.stream().anyMatch(t -> p.matcher(t).find());
        }
    }

    // ---- transactions and screens ----

    private List<Map<String, Object>> transactions() {
        Map<String, List<String>> mapsOf = new TreeMap<>();
        for (Map<String, Object> e : mapEdges()) mapsOf.computeIfAbsent((String) e.get("program"), k -> new ArrayList<>()).add((String) e.get("map"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : runsIn()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "transaction");
            r.put("transaction", t.get("transaction"));
            r.put("program", t.get("program"));
            r.put("program_member", t.get("program_member"));
            r.put("maps", mapsOf.getOrDefault((String) t.get("program"), List.of()));
            r.put("evidence", "CSD".equals(t.get("via")) ? "CSD definition" : "definition");
            r.put("member", t.get("member"));
            r.put("line", t.get("line"));
            out.add(r);
        }
        // Without a CSD the only trace of a transaction is the code that names it. That says who names it, not what it starts.
        Map<String, Map<String, Object>> codeOnly = new LinkedHashMap<>();
        for (Map<String, Object> t : jdbc.queryForList("""
                SELECT t.name AS transaction, p.name AS program, m.path AS member, e.line
                FROM graph_edge e JOIN graph_node t ON t.id = e.dst_id AND t.kind = 'transaction' JOIN graph_node p ON p.id = e.src_id JOIN member m ON m.id = e.member_id
                WHERE e.type = 'STARTS_TRANSACTION' AND NOT EXISTS (SELECT 1 FROM graph_edge r WHERE r.dst_id = t.id AND r.type = 'RUNS_IN')
                ORDER BY t.name, p.name, m.path, e.line""")) {
            Map<String, Object> r = codeOnly.computeIfAbsent((String) t.get("transaction"), k -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("kind", "transaction");
                n.put("transaction", k);
                n.put("program", null);
                n.put("program_member", null);
                n.put("maps", List.of());
                n.put("evidence", "named in code (RETURN or START TRANSID); no definition in the export");
                n.put("named_by", new ArrayList<String>());
                n.put("member", t.get("member"));
                n.put("line", t.get("line"));
                return n;
            });
            addOnce(r.get("named_by"), (String) t.get("program"));
        }
        out.addAll(codeOnly.values());
        out.sort(Comparator.comparing(r -> String.valueOf(r.get("transaction"))));
        return out;
    }

    private List<Map<String, Object>> runsIn() {
        return jdbc.queryForList("""
                SELECT t.name AS transaction, p.name AS program, e.attrs->>'via' AS via, m.path AS member, e.line,
                       (SELECT pm.path FROM program pr JOIN member pm ON pm.id = pr.member_id WHERE upper(pr.program_id) = upper(p.name) ORDER BY length(pm.path), pm.path LIMIT 1) AS program_member
                FROM graph_edge e JOIN graph_node t ON t.id = e.dst_id AND t.kind = 'transaction' JOIN graph_node p ON p.id = e.src_id AND p.kind = 'program' JOIN member m ON m.id = e.member_id
                WHERE e.type = 'RUNS_IN' ORDER BY t.name, p.name, m.path, e.line""");
    }

    private List<Map<String, Object>> mapEdges() {
        return jdbc.queryForList("""
                SELECT DISTINCT s.name AS program, upper(d.name) AS map
                FROM graph_edge e JOIN graph_node s ON s.id = e.src_id JOIN graph_node d ON d.id = e.dst_id AND d.kind = 'map'
                WHERE e.type IN ('SENDS_MAP','RECEIVES_MAP') ORDER BY 1, 2""");
    }

    private List<Map<String, Object>> screens() {
        Map<String, List<String>> programsOf = new TreeMap<>();
        for (Map<String, Object> e : mapEdges()) programsOf.computeIfAbsent((String) e.get("map"), k -> new ArrayList<>()).add((String) e.get("program"));
        Map<String, List<String>> txOf = new TreeMap<>();
        for (Map<String, Object> t : runsIn()) addOnce(txOf.computeIfAbsent((String) t.get("program"), k -> new ArrayList<>()), (String) t.get("transaction"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : jdbc.queryForList("""
                SELECT b.name AS map, b.mapset, m.path AS member, b.line,
                       (SELECT count(*) FROM bms_field f WHERE f.map_id = b.id) AS fields,
                       (SELECT count(*) FROM bms_field f WHERE f.map_id = b.id AND f.name IS NOT NULL) AS named_fields
                FROM bms_map b JOIN member m ON m.id = b.member_id ORDER BY b.name, m.path, b.line""")) {
            List<String> programs = programsOf.getOrDefault(String.valueOf(b.get("map")).toUpperCase(Locale.ROOT), List.of());
            List<String> tx = new ArrayList<>();
            for (String p : programs) for (String t : txOf.getOrDefault(p, List.of())) addOnce(tx, t);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "screen");
            r.put("map", b.get("map"));
            r.put("mapset", b.get("mapset"));
            r.put("member", b.get("member"));
            r.put("line", b.get("line"));
            r.put("fields", b.get("fields"));
            r.put("named_fields", b.get("named_fields"));
            r.put("programs", programs);
            r.put("transactions", tx);
            out.add(r);
        }
        return out;
    }

    // ---- programs ----

    private List<Map<String, Object>> programs() {
        Map<String, List<String>> steps = new TreeMap<>();
        for (Map<String, Object> s : jdbc.queryForList("""
                SELECT upper(s.pgm) AS pgm, j.name AS job, coalesce(s.name, '#' || s.seq) AS step
                FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id WHERE s.pgm IS NOT NULL ORDER BY j.name, s.seq""")) {
            steps.computeIfAbsent((String) s.get("pgm"), k -> new ArrayList<>()).add("job " + s.get("job") + " step " + s.get("step"));
        }
        Map<String, List<String>> tx = new TreeMap<>();
        for (Map<String, Object> t : runsIn()) addOnce(tx.computeIfAbsent(String.valueOf(t.get("program")).toUpperCase(Locale.ROOT), k -> new ArrayList<>()), "transaction " + t.get("transaction"));
        Map<String, Long> callers = new TreeMap<>();
        for (Map<String, Object> c : jdbc.queryForList("""
                SELECT upper(d.name) AS program, count(DISTINCT e.src_id) AS n
                FROM graph_edge e JOIN graph_node d ON d.id = e.dst_id AND d.kind = 'program'
                WHERE e.type IN ('CALLS','LINKS','XCTLS') AND e.src_id <> e.dst_id GROUP BY 1""")) {
            callers.put((String) c.get("program"), ((Number) c.get("n")).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : jdbc.queryForList("""
                SELECT p.program_id AS program, p.kind AS program_kind, m.path AS member, p.line_start, p.line_end, p.metrics::text AS metrics,
                       EXISTS (SELECT 1 FROM knowledge_artifact a WHERE a.unit_kind = 'program' AND a.unit_id = p.id AND a.kind = 'summary' AND a.status <> 'rejected') AS has_summary
                FROM program p JOIN member m ON m.id = p.member_id ORDER BY p.program_id, m.path""")) {
            String key = String.valueOf(p.get("program")).toUpperCase(Locale.ROOT);
            JsonNode metrics = metrics(p.get("metrics"));
            List<String> entered = new ArrayList<>();
            List<String> jobSteps = steps.getOrDefault(key, List.of());
            entered.addAll(jobSteps.subList(0, Math.min(NAMES, jobSteps.size())));
            if (jobSteps.size() > NAMES) entered.add("and " + (jobSteps.size() - NAMES) + " more job steps");
            entered.addAll(tx.getOrDefault(key, List.of()));
            boolean entry = !entered.isEmpty();
            long n = callers.getOrDefault(key, 0L);
            if (n > 0) entered.add("called by " + n + (n == 1 ? " program" : " programs"));
            if (entered.isEmpty()) entered.add("no caller found");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "program");
            r.put("program", p.get("program"));
            r.put("program_kind", p.get("program_kind"));
            r.put("member", p.get("member"));
            r.put("line_start", p.get("line_start"));
            r.put("line_end", p.get("line_end"));
            for (String m : List.of("loc", "cyclomatic", "fan_in", "fan_out")) r.put(m, metrics.path(m).isNumber() ? metrics.get(m).numberValue() : null);
            r.put("entry_point", entry);
            r.put("entered_by", entered);
            r.put("has_summary", p.get("has_summary"));
            out.add(r);
        }
        // Stable, so the order by name within each group survives.
        out.sort(Comparator.comparing(r -> !Boolean.TRUE.equals(r.get("entry_point"))));
        return out;
    }

    private static JsonNode metrics(Object text) {
        try {
            return text == null ? JSON.createObjectNode() : JSON.readTree(text.toString());
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    // ---- data ----

    /** One store being assembled: who reads it, who writes it, and the first place the source names it. */
    private static final class Store {
        final String kind, name;
        final Set<String> readers = new LinkedHashSet<>(), writers = new LinkedHashSet<>(), datasets = new LinkedHashSet<>();
        Object member, line;
        Boolean ddl;

        Store(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        void cite(Map<String, Object> row) {
            if (member == null) {
                member = row.get("member");
                line = row.get("line");
            }
        }

        Map<String, Object> item() {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", kind);
            r.put("name", name);
            r.put("read_by", readers.size());
            r.put("written_by", writers.size());
            r.put("readers", readers.stream().limit(NAMES).toList());
            r.put("writers", writers.stream().limit(NAMES).toList());
            if (ddl != null) r.put("ddl_in_export", ddl);
            if (!kind.equals("db2_table") && !kind.equals("queue")) r.put("datasets", datasets.stream().limit(NAMES).toList());
            r.put("member", member);
            r.put("line", line);
            return r;
        }
    }

    private List<Map<String, Object>> data() {
        List<Map<String, Object>> out = new ArrayList<>();
        tables().forEach(s -> out.add(s.item()));
        Map<Long, Set<String>> datasets = new LinkedHashMap<>();
        for (Map<String, Object> d : jdbc.queryForList("""
                SELECT DISTINCT e.src_id AS file_id, ds.name AS dataset
                FROM graph_edge e JOIN graph_node f ON f.id = e.src_id AND f.kind = 'file' JOIN graph_node ds ON ds.id = e.dst_id AND ds.kind = 'dataset'
                WHERE e.type = 'USES_DATASET' ORDER BY 1, 2""")) {
            datasets.computeIfAbsent(((Number) d.get("file_id")).longValue(), k -> new LinkedHashSet<>()).add((String) d.get("dataset"));
        }
        batchFiles(datasets).forEach(s -> out.add(s.item()));
        cicsFiles(datasets).forEach(s -> out.add(s.item()));
        queues().forEach(s -> out.add(s.item()));
        return out;
    }

    /** Tables the programs touch and tables that only have DDL; the DDL is the citation when the export has it. */
    private List<Store> tables() {
        Map<String, Store> t = new TreeMap<>();
        for (Map<String, Object> d : jdbc.queryForList("""
                SELECT upper(t.name) AS name, m.path AS member, t.line FROM db2_table t JOIN member m ON m.id = t.member_id
                ORDER BY 1, (m.type = 'ddl') DESC, m.path, t.line""")) {
            Store s = t.computeIfAbsent((String) d.get("name"), k -> new Store("db2_table", k));
            s.ddl = true;
            s.cite(d);
        }
        for (Map<String, Object> e : jdbc.queryForList("""
                SELECT upper(n.name) AS name, p.name AS program, e.type, m.path AS member, e.line
                FROM graph_edge e JOIN graph_node n ON n.id = e.dst_id AND n.kind = 'table' JOIN graph_node p ON p.id = e.src_id JOIN member m ON m.id = e.member_id
                WHERE e.type IN ('SELECTS','INSERTS','UPDATES','DELETES') ORDER BY 1, p.name, m.path, e.line""")) {
            Store s = t.computeIfAbsent((String) e.get("name"), k -> new Store("db2_table", k));
            if (s.ddl == null) s.ddl = false;
            (e.get("type").equals("SELECTS") ? s.readers : s.writers).add((String) e.get("program"));
            s.cite(e);
        }
        return new ArrayList<>(t.values());
    }

    /**
     * A batch file is known across programs by the name in SELECT ... ASSIGN TO, which is the DD name in the JCL; the
     * COBOL file name is private to each program. WRITE and REWRITE name a record, not the file, so a writer is a
     * program that opens the file for output, extend or I-O.
     */
    private List<Store> batchFiles(Map<Long, Set<String>> datasets) {
        Map<String, Store> f = new TreeMap<>();
        for (Map<String, Object> d : jdbc.queryForList("""
                SELECT upper(coalesce(nullif(d.attrs->>'assign', ''), n.name)) AS name, p.name AS program, m.path AS member, d.line, d.dst_id AS file_id,
                       EXISTS (SELECT 1 FROM graph_edge e WHERE e.src_id = d.src_id AND e.dst_id = d.dst_id AND e.type = 'READS') AS reads,
                       EXISTS (SELECT 1 FROM graph_edge e WHERE e.src_id = d.src_id AND e.dst_id = d.dst_id AND (e.type = 'WRITES' OR e.attrs->>'verb' = 'OPEN I-O')) AS writes
                FROM graph_edge d JOIN graph_node n ON n.id = d.dst_id JOIN graph_node p ON p.id = d.src_id JOIN member m ON m.id = d.member_id
                WHERE d.type = 'DECLARES_FILE' ORDER BY 1, p.name, m.path, d.line""")) {
            Store s = f.computeIfAbsent((String) d.get("name"), k -> new Store("file", k));
            if (Boolean.TRUE.equals(d.get("reads"))) s.readers.add((String) d.get("program"));
            if (Boolean.TRUE.equals(d.get("writes"))) s.writers.add((String) d.get("program"));
            s.datasets.addAll(datasets.getOrDefault(((Number) d.get("file_id")).longValue(), Set.of()));
            s.cite(d);
        }
        return new ArrayList<>(f.values());
    }

    private List<Store> cicsFiles(Map<Long, Set<String>> datasets) {
        Map<String, Store> f = new TreeMap<>();
        for (Map<String, Object> e : jdbc.queryForList("""
                SELECT upper(n.name) AS name, n.id AS file_id, p.name AS program, e.type, m.path AS member, e.line
                FROM graph_edge e JOIN graph_node n ON n.id = e.dst_id AND n.kind = 'file' AND coalesce(n.scope, '') = ''
                JOIN graph_node p ON p.id = e.src_id AND p.kind = 'program' JOIN member m ON m.id = e.member_id
                WHERE e.type IN ('READS','WRITES') ORDER BY 1, p.name, m.path, e.line""")) {
            Store s = f.computeIfAbsent((String) e.get("name"), k -> new Store("cics_file", k));
            (e.get("type").equals("READS") ? s.readers : s.writers).add((String) e.get("program"));
            s.datasets.addAll(datasets.getOrDefault(((Number) e.get("file_id")).longValue(), Set.of()));
            s.cite(e);
        }
        return new ArrayList<>(f.values());
    }

    private List<Store> queues() {
        Map<String, Store> q = new TreeMap<>();
        for (Map<String, Object> e : jdbc.queryForList("""
                SELECT n.name, p.name AS program, e.type, m.path AS member, e.line
                FROM graph_edge e JOIN graph_node n ON n.id = e.dst_id AND n.kind = 'queue' JOIN graph_node p ON p.id = e.src_id JOIN member m ON m.id = e.member_id
                WHERE e.type IN ('GETS_QUEUE','PUTS_QUEUE') ORDER BY 1, p.name, m.path, e.line""")) {
            Store s = q.computeIfAbsent((String) e.get("name"), k -> new Store("queue", k));
            (e.get("type").equals("GETS_QUEUE") ? s.readers : s.writers).add((String) e.get("program"));
            s.cite(e);
        }
        return new ArrayList<>(q.values());
    }

    // ---- helpers ----

    /** A name as a JSON string, so a suggested call stays valid JSON whatever the source named things. */
    private static String quoted(Object name) {
        return JSON.valueToTree(String.valueOf(name)).toString();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        });
        return m;
    }
}
