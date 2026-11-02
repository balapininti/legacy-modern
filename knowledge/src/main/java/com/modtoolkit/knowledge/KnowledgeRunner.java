package com.modtoolkit.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modtoolkit.core.Hashes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The knowledge pass. A unit whose (content hash, prompt version, model) has been answered costs nothing, a budget
 * stop leaves everything so far intact, and one bad unit is recorded rather than stopping the run.
 */
@Service
public class KnowledgeRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeRunner.class);
    /** Sorted because this text is hashed into the cache key, and Map.of orders its keys differently in each JVM. */
    private static final ObjectMapper JSON = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final List<String> PROGRAM_KINDS = List.of("summary", "rule", "migration_note");

    public record Stats(long runId, String status, int units, int fromCache, int llmCalls, int failed, long tokensIn, long tokensOut, double costUsd) {}

    private record Unit(String unitKind, long unitId, String name, String kind, long memberId, String source, String context, ArtifactValidator.Facts facts, List<Section> sections) {}
    private record Section(String source) {}

    private final JdbcTemplate jdbc;

    public KnowledgeRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final class Run {
        final LlmClient llm;
        final KnowledgeOptions o;
        final PiiRedactor redactor;
        final long id;
        final AtomicLong in = new AtomicLong(), out = new AtomicLong();
        final AtomicInteger cached = new AtomicInteger(), calls = new AtomicInteger(), failed = new AtomicInteger(), done = new AtomicInteger();
        final AtomicBoolean halted = new AtomicBoolean();

        Run(LlmClient llm, KnowledgeOptions o, long id) {
            this.llm = llm;
            this.o = o;
            this.id = id;
            this.redactor = new PiiRedactor(o.redactPii(), o.piiPatterns());
        }

        double cost() {
            return in.get() / 1e6 * o.inputUsdPerMtok() + out.get() / 1e6 * o.outputUsdPerMtok();
        }

        boolean overBudget() {
            if (o.budgetTokens() != null && in.get() + out.get() >= o.budgetTokens()) return true;
            return o.budgetUsd() != null && cost() >= o.budgetUsd();
        }
    }

    public Stats run(LlmClient llm, KnowledgeOptions o) throws InterruptedException {
        long runId = jdbc.queryForObject("INSERT INTO llm_run (model, status) VALUES (?, 'running') RETURNING id", Long.class, llm.model());
        Run run = new Run(llm, o, runId);
        List<Unit> units = units(o);
        log.info("knowledge: {} units, model {}", units.size(), llm.model());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, o.concurrency()));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Unit u : units) fs.add(pool.submit(() -> process(run, u)));
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    log.error("knowledge worker failed", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        String status = run.halted.get() ? "budget_reached" : "completed";
        jdbc.update("UPDATE llm_run SET units = ?, tokens_in = ?, tokens_out = ?, cost_usd = ?, status = ?, ended_at = now() WHERE id = ?",
                run.done.get(), run.in.get(), run.out.get(), BigDecimal.valueOf(run.cost()), status, runId);
        log.info("knowledge {}: {} units, {} cached, {} calls, {} failed, tokens {}+{}", status, run.done.get(), run.cached.get(), run.calls.get(), run.failed.get(), run.in.get(), run.out.get());
        return new Stats(runId, status, run.done.get(), run.cached.get(), run.calls.get(), run.failed.get(), run.in.get(), run.out.get(), run.cost());
    }

    private void process(Run run, Unit u) {
        if (run.overBudget()) {
            run.halted.set(true);
            return;
        }
        String version = Prompts.VERSIONS.get(u.kind());
        String contentHash = Hashes.sha256(u.source() + "\u0000" + u.context());
        String key = Hashes.sha256(contentHash + "\u0000" + version + "\u0000" + run.llm.model() + "\u0000" + u.unitKind() + u.name() + u.kind());
        try {
            List<Map<String, Object>> hit = jdbc.queryForList("SELECT response::text AS r FROM llm_cache WHERE cache_key = ?", key);
            ObjectNode answer;
            if (!hit.isEmpty()) {
                answer = (ObjectNode) JSON.readTree((String) hit.get(0).get("r"));
                run.cached.incrementAndGet();
            } else {
                answer = u.sections().size() > 1 ? sections(run, u) : call(run, u, u.source(), u.kind() + "/" + u.name());
                if (answer == null) return; // Budget stop in the middle of a unit: nothing stored, it runs again next time.
                jdbc.update("INSERT INTO llm_cache (cache_key, response) VALUES (?, ?::jsonb) ON CONFLICT (cache_key) DO UPDATE SET response = EXCLUDED.response", key, JSON.writeValueAsString(answer));
            }
            store(run, u, answer, contentHash, version);
            jdbc.update("DELETE FROM knowledge_failure WHERE unit_kind = ? AND unit_name = ? AND kind = ?", u.unitKind(), u.name(), u.kind());
            run.done.incrementAndGet();
        } catch (Exception e) {
            run.failed.incrementAndGet();
            log.warn("knowledge {} {}: {}", u.kind(), u.name(), e.getMessage());
            jdbc.update("INSERT INTO knowledge_failure (llm_run_id, unit_kind, unit_name, kind, error) VALUES (?,?,?,?,?)", run.id, u.unitKind(), u.name(), u.kind(), String.valueOf(e.getMessage()));
        }
    }

    /** Invalid output is retried once with the reason; transport trouble is retried with backoff. */
    private ObjectNode call(Run run, Unit u, String source, String recordingKey) throws InterruptedException {
        String user = run.redactor.redact(Prompts.render(u.kind(), u.name(), u.context(), source));
        String system = Prompts.system();
        String error = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (run.overBudget()) {
                run.halted.set(true);
                return null;
            }
            String prompt = error == null ? user : user + "\n\nYour previous answer was rejected: " + error + "\nAnswer again with corrected JSON only.";
            LlmClient.Response r = withBackoff(run, recordingKey + (attempt == 0 ? "" : ".retry"), system, prompt);
            run.calls.incrementAndGet();
            run.in.addAndGet(r.tokensIn());
            run.out.addAndGet(r.tokensOut());
            try {
                return ArtifactValidator.validate(u.kind(), r.text(), u.facts());
            } catch (ArtifactValidator.Invalid e) {
                error = e.getMessage();
                log.info("rejected {} {}: {}", u.kind(), u.name(), error);
            }
        }
        throw new ArtifactValidator.Invalid("rejected twice: " + error);
    }

    private LlmClient.Response withBackoff(Run run, String key, String system, String prompt) throws InterruptedException {
        long delay = 2000;
        for (int i = 0; ; i++) {
            try {
                return run.llm.complete(key, system, prompt);
            } catch (LlmClient.TransientFailure e) {
                if (i >= 3) throw e;
                Thread.sleep(delay);
                delay *= 4;
            }
        }
    }

    /** A program beyond the context window goes paragraph group by group, and the parts merge. */
    private ObjectNode sections(Run run, Unit u) throws InterruptedException {
        List<ObjectNode> parts = new ArrayList<>();
        int i = 0;
        for (Section s : u.sections()) {
            ObjectNode p = call(run, u, s.source(), u.kind() + "/" + u.name() + "/part" + (++i));
            if (p == null) return null;
            parts.add(p);
        }
        ObjectNode merged = parts.get(0);
        if (u.kind().equals("rule") || u.kind().equals("migration_note")) {
            String field = u.kind().equals("rule") ? "rules" : "items";
            ArrayNode all = JSON.createArrayNode();
            int n = 0;
            for (ObjectNode p : parts) {
                for (JsonNode x : p.get(field)) {
                    if (u.kind().equals("rule")) ((ObjectNode) x).put("rule_id", u.name() + "-R" + (++n));
                    all.add(x);
                }
            }
            merged.set(field, all);
            return merged;
        }
        // A summary needs one voice: the final call merges the parts.
        String joined;
        try {
            joined = JSON.writeValueAsString(parts);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return call(run, new Unit(u.unitKind(), u.unitId(), u.name(), u.kind(), u.memberId(), u.source(), u.context() + "\nThese are summaries of the program's parts, in order. Merge them into one:\n" + joined, u.facts(), u.sections()),
                "(see the part summaries in the context)", u.kind() + "/" + u.name() + "/merge");
    }

    /** A reviewed artifact is kept while its source is unchanged; a changed source yields a new draft beside it. */
    private void store(Run run, Unit u, ObjectNode answer, String contentHash, String version) throws Exception {
        if (u.kind().equals("dictionary")) {
            for (JsonNode e : answer.get("entries")) {
                ObjectNode one = (ObjectNode) e;
                insert(u, one, contentHash, version, run.llm.model(), one.get("line").asInt(), one.get("line").asInt());
            }
            return;
        }
        int[] range = range(u.kind(), answer, u);
        insert(u, answer, contentHash, version, run.llm.model(), range[0], range[1]);
    }

    private int[] range(String kind, JsonNode a, Unit u) {
        return switch (kind) {
            case "summary" -> new int[] {min(a.get("evidence")), max(a.get("evidence"))};
            case "rule" -> a.get("rules").isEmpty() ? new int[] {u.facts().lineMin(), u.facts().lineMax()} : new int[] {min(a.get("rules")), max(a.get("rules"))};
            case "migration_note" -> a.get("items").isEmpty() ? new int[] {u.facts().lineMin(), u.facts().lineMax()} : new int[] {min(a.get("items")), max(a.get("items"))};
            default -> new int[] {a.get("line_start").asInt(), a.get("line_end").asInt()};
        };
    }

    private static int min(JsonNode arr) {
        int m = Integer.MAX_VALUE;
        for (JsonNode x : arr) m = Math.min(m, x.get("line_start").asInt());
        return m;
    }

    private static int max(JsonNode arr) {
        int m = 0;
        for (JsonNode x : arr) m = Math.max(m, x.get("line_end").asInt());
        return m;
    }

    private void insert(Unit u, JsonNode content, String contentHash, String version, String model, int start, int end) throws Exception {
        jdbc.update("""
                INSERT INTO knowledge_artifact (unit_kind, unit_id, kind, content, model, prompt_version, content_hash, member_id, line_start, line_end)
                VALUES (?,?,?,?::jsonb,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
                u.unitKind(), u.unitId(), u.kind(), JSON.writeValueAsString(content), model, version, contentHash, u.memberId(), start, end);
    }

    private List<Unit> units(KnowledgeOptions o) {
        List<Unit> out = new ArrayList<>();
        boolean any = o.kinds().isEmpty();
        Set<String> programs = new TreeSet<>();
        o.programs().forEach(p -> programs.add(p.toUpperCase(Locale.ROOT)));
        // Programs first: others use their summaries as context.
        for (Map<String, Object> p : jdbc.queryForList("""
                SELECT p.id, p.program_id, p.member_id, p.kind, p.line_start, p.line_end, m.normalized_text FROM program p JOIN member m ON m.id = p.member_id
                WHERE m.parse_status = 'parsed' AND p.parent_id IS NULL ORDER BY p.program_id""")) {
            String name = (String) p.get("program_id");
            if (!programs.isEmpty() && !programs.contains(name.toUpperCase(Locale.ROOT))) continue;
            long id = ((Number) p.get("id")).longValue();
            for (String kind : PROGRAM_KINDS) if (any || o.kinds().contains(kind)) out.add(programUnit(p, id, name, kind, o));
        }
        List<Unit> others = new ArrayList<>();
        if (any || o.kinds().contains("dictionary")) others.addAll(copybooks());
        if (any || o.kinds().contains("job_narrative")) others.addAll(jobs());
        if (any || o.kinds().contains("screen_flow")) others.addAll(maps());
        for (Unit u : others) if (programs.isEmpty() || programs.contains(u.name().toUpperCase(Locale.ROOT))) out.add(u);
        return out;
    }

    private Unit programUnit(Map<String, Object> p, long id, String name, String kind, KnowledgeOptions o) {
        long memberId = ((Number) p.get("member_id")).longValue();
        List<String> lines = Arrays.asList(((String) p.get("normalized_text")).split("\n", -1));
        int start = (Integer) p.get("line_start"), end = Math.min((Integer) p.get("line_end"), lines.size());
        String source = numbered(lines, start, end);
        Set<String> paragraphs = new TreeSet<>(jdbc.queryForList("SELECT upper(name) FROM paragraph WHERE program_id = ?", String.class, id));
        Set<String> items = new TreeSet<>(jdbc.queryForList("SELECT upper(name) FROM data_item WHERE program_id = ? AND name IS NOT NULL", String.class, id));
        ArtifactValidator.Facts facts = new ArtifactValidator.Facts(start, end, paragraphs, items);
        String context = programContext(id, name, (String) p.get("kind"));
        List<Section> sections = List.of(new Section(source));
        // chars / 4 is the usual rough token count; a program past the window is split on paragraph boundaries.
        if ((source.length() + context.length()) / 4 > o.maxContextTokens()) {
            sections = split(id, lines, start, end, o.maxContextTokens() * 4 - context.length() - 4000);
        }
        return new Unit("program", id, name, kind, memberId, source, context, facts, sections);
    }

    private List<Section> split(long programId, List<String> lines, int start, int end, int maxChars) {
        List<Integer> cuts = new ArrayList<>();
        cuts.add(start);
        cuts.addAll(jdbc.queryForList("SELECT line_start FROM paragraph WHERE program_id = ? AND line_start > ? AND line_start <= ? ORDER BY line_start", Integer.class, programId, start, end));
        List<Section> out = new ArrayList<>();
        int from = start;
        int chars = 0;
        for (int i = 1; i <= cuts.size(); i++) {
            int segEnd = i < cuts.size() ? cuts.get(i) - 1 : end;
            int segStart = cuts.get(i - 1);
            int len = 0;
            for (int l = segStart; l <= segEnd; l++) len += lines.get(l - 1).length() + 8;
            if (chars > 0 && chars + len > maxChars) {
                out.add(new Section(numbered(lines, from, segStart - 1)));
                from = segStart;
                chars = 0;
            }
            chars += len;
        }
        out.add(new Section(numbered(lines, from, end)));
        return out;
    }

    private String programContext(long id, String name, String kind) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("program", name);
        c.put("kind", kind);
        c.put("callers", jdbc.queryForList("SELECT DISTINCT s.name FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id WHERE d.kind='program' AND d.name=? AND e.type IN ('CALLS','LINKS','XCTLS') ORDER BY 1", String.class, name));
        c.put("callees", jdbc.queryForList("SELECT DISTINCT d.name FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id WHERE s.kind='program' AND s.name=? AND e.type IN ('CALLS','LINKS','XCTLS') ORDER BY 1", String.class, name));
        c.put("tables", jdbc.queryForList("SELECT DISTINCT d.name FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id WHERE e.program_id=? AND d.kind='table' ORDER BY 1", String.class, id));
        c.put("copybook_fields", jdbc.queryForList("""
                SELECT m.name AS copybook, d.level, d.name, d.pic, d.usage FROM data_item d JOIN member m ON m.id = d.owner_member_id
                WHERE d.program_id = ? AND d.owner_member_id <> (SELECT member_id FROM program WHERE id = ?) AND d.name IS NOT NULL ORDER BY d.id LIMIT 300""", id, id));
        c.put("jobs", jdbc.queryForList("SELECT DISTINCT j.name FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id WHERE upper(s.pgm) = upper(?) ORDER BY 1", String.class, name));
        c.put("transactions", jdbc.queryForList("SELECT DISTINCT t.name FROM graph_edge e JOIN graph_node p ON p.id=e.src_id JOIN graph_node t ON t.id=e.dst_id WHERE p.kind='program' AND p.name=? AND t.kind='transaction' AND e.type='RUNS_IN' ORDER BY 1", String.class, name));
        return json(c);
    }

    private List<Unit> copybooks() {
        List<Unit> out = new ArrayList<>();
        for (Map<String, Object> m : jdbc.queryForList("""
                SELECT m.id, m.name, m.normalized_text FROM member m WHERE m.type = 'copybook' AND m.parse_status = 'parsed'
                  AND EXISTS (SELECT 1 FROM data_item d WHERE d.owner_member_id = m.id AND d.program_id IS NULL) ORDER BY m.name""")) {
            long id = ((Number) m.get("id")).longValue();
            List<String> lines = Arrays.asList(((String) m.get("normalized_text")).split("\n", -1));
            Set<String> items = new TreeSet<>(jdbc.queryForList("SELECT upper(name) FROM data_item WHERE owner_member_id = ? AND program_id IS NULL AND name IS NOT NULL", String.class, id));
            String context = json(Map.of("copybook", m.get("name"), "used_by", jdbc.queryForList("SELECT DISTINCT p.program_id FROM copy_use u JOIN program p ON p.id = u.program_id WHERE upper(u.copybook_name) = upper(?) ORDER BY 1 LIMIT 30", String.class, m.get("name"))));
            out.add(new Unit("copybook", id, (String) m.get("name"), "dictionary", id, numbered(lines, 1, lines.size()), context, new ArtifactValidator.Facts(1, lines.size(), Set.of(), items), List.of(new Section(numbered(lines, 1, lines.size())))));
        }
        return out;
    }

    private List<Unit> jobs() {
        List<Unit> out = new ArrayList<>();
        for (Map<String, Object> j : jdbc.queryForList("SELECT j.id, j.name, j.member_id, m.normalized_text FROM jcl_job j JOIN member m ON m.id = j.member_id ORDER BY j.name")) {
            List<String> lines = Arrays.asList(((String) j.get("normalized_text")).split("\n", -1));
            String source = numbered(lines, 1, lines.size());
            String ctx = json(Map.of("job", j.get("name"), "steps", jdbc.queryForList("SELECT seq, name, pgm, proc, cond FROM jcl_step WHERE job_id = ? ORDER BY seq", j.get("id"))));
            long member = ((Number) j.get("member_id")).longValue();
            out.add(new Unit("job", member, (String) j.get("name"), "job_narrative", member, source, ctx, new ArtifactValidator.Facts(1, lines.size(), Set.of(), Set.of()), List.of(new Section(source))));
        }
        return out;
    }

    private List<Unit> maps() {
        List<Unit> out = new ArrayList<>();
        for (Map<String, Object> b : jdbc.queryForList("SELECT b.id, b.name, b.member_id, m.normalized_text FROM bms_map b JOIN member m ON m.id = b.member_id ORDER BY b.name")) {
            List<String> lines = Arrays.asList(((String) b.get("normalized_text")).split("\n", -1));
            String source = numbered(lines, 1, lines.size());
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("map", b.get("name"));
            ctx.put("programs", jdbc.queryForList("""
                    SELECT DISTINCT p.name, e.type, e.line FROM graph_edge e JOIN graph_node n ON n.id=e.dst_id JOIN graph_node p ON p.id=e.src_id
                    WHERE n.kind='map' AND n.name=? AND e.type IN ('SENDS_MAP','RECEIVES_MAP') ORDER BY 1,3""", b.get("name")));
            long member = ((Number) b.get("member_id")).longValue();
            out.add(new Unit("map", member, (String) b.get("name"), "screen_flow", member, source, json(ctx), new ArtifactValidator.Facts(1, lines.size(), Set.of(), Set.of()), List.of(new Section(source))));
        }
        return out;
    }

    static String numbered(List<String> lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end && i <= lines.size(); i++) sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
        return sb.toString();
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
