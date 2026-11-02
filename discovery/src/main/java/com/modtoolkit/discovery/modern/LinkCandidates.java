package com.modtoolkit.discovery.modern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.discovery.NodeStore;
import com.modtoolkit.discovery.NodeStore.Key;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Proposes legacy-to-modern links from names and commit references; the LLM signal joins in M4. A candidate is only
 * ever a candidate: nothing here sets a link to confirmed, and a link a lead rejected stays rejected on re-run.
 */
@Service
public class LinkCandidates {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9@#$_-]{3,}");

    private final JdbcTemplate jdbc;
    private final NodeStore nodes;

    public LinkCandidates(JdbcTemplate jdbc, NodeStore nodes) {
        this.jdbc = jdbc;
        this.nodes = nodes;
    }

    public int propose(Path repoRoot, String repo) throws Exception {
        Map<String, String> programs = new HashMap<>();
        for (String p : jdbc.queryForList("SELECT DISTINCT upper(program_id) FROM program WHERE parent_id IS NULL", String.class)) {
            if (distinctive(p)) programs.put(p, p);
        }
        int n = 0;
        List<Map<String, Object>> units = jdbc.queryForList("SELECT id, path, kind, name FROM modern_unit WHERE repo = ?", repo);
        Map<String, List<Long>> unitsByPath = new HashMap<>();
        for (Map<String, Object> u : units) unitsByPath.computeIfAbsent((String) u.get("path"), k -> new ArrayList<>()).add(((Number) u.get("id")).longValue());

        // Signal 1: a program id in the unit's name, or anywhere in its file. Comments count: "ported from X" is the
        // usual way a rewrite cites its source, and a Javadoc sits outside the unit's own line range.
        for (var e : unitsOfFile(units).entrySet()) {
            Path f = repoRoot.resolve(e.getKey());
            String text = Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.ISO_8859_1) : "";
            Set<String> hits = new HashSet<>();
            Matcher w = WORD.matcher(text);
            while (w.find()) {
                String up = w.group().toUpperCase(Locale.ROOT);
                if (programs.containsKey(up)) hits.add(up);
            }
            for (Map<String, Object> u : e.getValue()) {
                String name = ((String) u.get("name")).toUpperCase(Locale.ROOT);
                long id = ((Number) u.get("id")).longValue();
                for (String p : hits) n += link(p, id, name.contains(p) ? 0.9f : 0.7f, "name_match", Map.of("signal", name.contains(p) ? "program id in the unit's name" : "program id in the unit's file", "file", e.getKey()));
            }
        }
        // Signal 2: tables the program touches, against entities mapped to them.
        for (Map<String, Object> e : jdbc.queryForList("SELECT id, name, attrs->>'table' AS tbl FROM modern_unit WHERE repo = ? AND kind = 'entity' AND attrs ?? 'table'", repo)) {
            for (String p : jdbc.queryForList("""
                    SELECT DISTINCT upper(pr.program_id) FROM sql_stmt s CROSS JOIN LATERAL jsonb_array_elements_text(s.tables) t JOIN program pr ON pr.id = s.program_id
                    WHERE upper(regexp_replace(t, '^.*\\.', '')) = upper(?) AND s.stmt_type <> 'DECLARE TABLE'""", String.class, e.get("tbl"))) {
                n += link(p, ((Number) e.get("id")).longValue(), 0.5f, "name_match", Map.of("signal", "same table", "table", e.get("tbl")));
            }
        }
        // Signal 3: commits that cite a program id, against the units in the files they changed.
        n += commitReferences(repoRoot, programs, unitsByPath);
        return n;
    }

    /** Links land on classes and components; endpoints and routes inherit them through their class. */
    private static Map<String, List<Map<String, Object>>> unitsOfFile(List<Map<String, Object>> units) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        for (Map<String, Object> u : units) {
            if (Set.of("java_class", "entity", "dto", "batch_job", "ng_component", "ng_service").contains(u.get("kind"))) {
                out.computeIfAbsent((String) u.get("path"), k -> new ArrayList<>()).add(u);
            }
        }
        return out;
    }

    private static boolean distinctive(String program) {
        return program.length() >= 5;
    }

    private int commitReferences(Path repoRoot, Map<String, String> programs, Map<String, List<Long>> unitsByPath) throws Exception {
        if (!Files.isDirectory(repoRoot.resolve(".git"))) return 0;
        Process p = new ProcessBuilder("git", "-C", repoRoot.toString(), "log", "--name-only", "--format=%x01%H%x02%s%x02%b%x03")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String log;
        try {
            log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
        } catch (IOException e) {
            return 0;
        }
        int n = 0;
        for (String entry : log.split("\u0001")) {
            int end = entry.indexOf('\u0003');
            if (end < 0) continue;
            String[] head = entry.substring(0, end).split("\u0002", 3);
            String message = head.length > 1 ? head[1] + " " + (head.length > 2 ? head[2] : "") : "";
            Set<String> cited = new HashSet<>();
            Matcher w = WORD.matcher(message);
            while (w.find()) {
                String up = w.group().toUpperCase(Locale.ROOT);
                if (programs.containsKey(up)) cited.add(up);
            }
            if (cited.isEmpty()) continue;
            for (String file : entry.substring(end + 1).split("\n")) {
                for (long unit : unitsByPath.getOrDefault(file.strip(), List.of())) {
                    for (String c : cited) n += link(c, unit, 0.8f, "commit_ref", Map.of("commit", head[0], "subject", head.length > 1 ? head[1] : ""));
                }
            }
        }
        return n;
    }

    /** Keeps a person's decision: the conflict clause never touches status or who confirmed it. */
    public int link(String program, long unit, float confidence, String source, Map<String, ?> evidence) throws Exception {
        long node = nodes.ensure(List.of(new Key("program", program, ""))).values().iterator().next();
        return jdbc.update("""
                INSERT INTO migration_link (legacy_node_id, modern_unit_id, granularity, confidence, source, status, evidence)
                VALUES (?,?,'program',?,?,'candidate',?::jsonb)
                ON CONFLICT (legacy_node_id, modern_unit_id, source, granularity) DO UPDATE SET confidence = EXCLUDED.confidence, evidence = EXCLUDED.evidence""",
                node, unit, confidence, source, JSON.writeValueAsString(evidence));
    }
}
