package com.modtoolkit.discovery.modern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.discovery.NodeStore;
import com.modtoolkit.discovery.NodeStore.Key;
import com.modtoolkit.parsers.normalize.Normalizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Indexes the modern repository into modern_unit and the graph. Unchanged files are skipped by content hash. */
@Service
public class TargetIndexer {
    private static final Logger log = LoggerFactory.getLogger(TargetIndexer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target", "build", "node_modules", "dist", ".angular", ".gradle", ".idea");

    public record Stats(int javaFiles, int changedFiles, int units, List<String> errors, String angular) {}

    private final JdbcTemplate jdbc;
    private final NodeStore nodes;

    public TargetIndexer(JdbcTemplate jdbc, NodeStore nodes) {
        this.jdbc = jdbc;
        this.nodes = nodes;
    }

    public Stats index(Path repoRoot, String repo) throws Exception {
        Map<String, String> known = new HashMap<>();
        jdbc.query("SELECT DISTINCT path, content_hash FROM modern_unit WHERE repo = ?", rs -> { known.put(rs.getString(1), rs.getString(2)); }, repo);
        JavaExtractor java = new JavaExtractor();
        List<String> errors = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(repoRoot)) {
            s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java") && !skipped(repoRoot, p)).sorted().forEach(files::add);
        }
        Set<String> seen = new HashSet<>();
        int changed = 0;
        int units = 0;
        for (Path f : files) {
            String rel = repoRoot.relativize(f).toString();
            seen.add(rel);
            byte[] bytes = Files.readAllBytes(f);
            String hash = Normalizer.sha256(bytes);
            if (hash.equals(known.get(rel))) continue;
            changed++;
            JavaExtractor.Result r = java.extract(rel, new String(bytes, StandardCharsets.UTF_8), hash);
            if (r.error() != null) errors.add(rel + ": " + r.error());
            units += replace(repo, rel, r.units(), new String(bytes, StandardCharsets.UTF_8));
        }
        String angular = angular(repoRoot, repo, known, seen);
        for (String gone : known.keySet()) {
            if (!seen.contains(gone)) jdbc.update("DELETE FROM modern_unit WHERE repo = ? AND path = ?", repo, gone);
        }
        linkEndpointsAndTables(repo);
        log.info("target: {} java files, {} changed, {} units", files.size(), changed, units);
        return new Stats(files.size(), changed, units, errors, angular);
    }

    private static boolean skipped(Path root, Path p) {
        for (Path part : root.relativize(p)) if (SKIP_DIRS.contains(part.toString())) return true;
        return false;
    }

    private int replace(String repo, String rel, List<ModernUnit> units, String source) throws Exception {
        jdbc.update("DELETE FROM modern_unit WHERE repo = ? AND path = ?", repo, rel);
        List<String> lines = List.of(source.split("\n", -1));
        for (ModernUnit u : units) {
            long id = jdbc.queryForObject("INSERT INTO modern_unit (repo, path, kind, name, signature, content_hash, line_start, line_end, attrs) VALUES (?,?,?,?,?,?,?,?,?::jsonb) RETURNING id", Long.class,
                    repo, rel, u.kind(), u.name(), u.signature(), u.contentHash(), u.lineStart(), u.lineEnd(), JSON.writeValueAsString(u.attrs()));
            long node = nodes.ensure(List.of(new Key(u.kind(), u.name(), repo))).values().iterator().next();
            jdbc.update("UPDATE graph_node SET modern_unit_id = ? WHERE id = ?", id, node);
            String text = String.join("\n", lines.subList(Math.max(0, u.lineStart() - 1), Math.min(lines.size(), u.lineEnd())));
            if (text.isEmpty()) text = u.name();
            String body = u.name() + "\n" + (u.signature() == null ? "" : u.signature()) + "\n" + text;
            jdbc.update("INSERT INTO chunk (modern_unit_id, kind, line_start, line_end, text, tsv, metadata) VALUES (?,'modern',?,?,?,cobol_tsvector(?),jsonb_build_object('unit',?::text,'path',?::text))",
                    id, u.lineStart(), u.lineEnd(), body, body, u.name(), rel);
        }
        return units.size();
    }

    /** The compiler API runs in a separate Node step. No Node or no TypeScript is reported, and Java indexing still stands. */
    private String angular(Path root, String repo, Map<String, String> known, Set<String> seen) throws Exception {
        boolean hasTs;
        try (Stream<Path> s = Files.walk(root)) {
            hasTs = s.anyMatch(p -> p.toString().endsWith(".ts") && !skipped(root, p));
        }
        if (!hasTs) return "no TypeScript";
        Path script = locateScript();
        if (script == null) return "skipped: tools/ng-extract not found (set TOOLKIT_NG_EXTRACT)";
        ProcessBuilder b = new ProcessBuilder("node", script.toString(), root.toString()).redirectError(ProcessBuilder.Redirect.PIPE);
        Process p;
        try {
            p = b.start();
        } catch (IOException e) {
            return "skipped: node is not available";
        }
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) return "failed: " + err.strip();
        Map<String, List<ModernUnit>> byPath = new HashMap<>();
        for (JsonNode u : JSON.readTree(out).get("units")) {
            ModernUnit m = new ModernUnit(u.get("kind").asText(), u.get("name").asText(), u.get("path").asText(), u.get("kind").asText() + " " + u.get("name").asText(),
                    u.get("line_start").asInt(), u.get("line_end").asInt(), u.get("content_hash").asText(), JSON.convertValue(u.get("attrs"), Map.class));
            byPath.computeIfAbsent(m.path(), k -> new ArrayList<>()).add(m);
        }
        int n = 0;
        for (var e : byPath.entrySet()) {
            seen.add(e.getKey());
            if (e.getValue().get(0).contentHash().equals(known.get(e.getKey()))) continue;
            n += replace(repo, e.getKey(), e.getValue(), Files.readString(root.resolve(e.getKey())));
        }
        return "indexed " + byPath.size() + " files, " + n + " units changed";
    }

    private static Path locateScript() {
        String env = System.getenv("TOOLKIT_NG_EXTRACT");
        if (env != null) return Path.of(env);
        for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path c = base.resolve("tools/ng-extract/extract.js");
            if (Files.exists(c)) return c;
        }
        return null;
    }

    private void linkEndpointsAndTables(String repo) throws Exception {
        jdbc.update("DELETE FROM graph_edge e USING graph_node s WHERE e.src_id = s.id AND s.scope = ? AND e.type IN ('MAPS_TO_TABLE','CALLS_ENDPOINT')", repo);
        for (Map<String, Object> ent : jdbc.queryForList("SELECT id, name, attrs->>'table' AS tbl, line_start FROM modern_unit WHERE repo = ? AND kind = 'entity'", repo)) {
            String tbl = (String) ent.get("tbl");
            if (tbl == null) continue;
            Map<Key, Long> ids = nodes.ensure(List.of(new Key("entity", (String) ent.get("name"), repo), new Key("table", tbl, "")));
            jdbc.update("INSERT INTO graph_edge (src_id, dst_id, type, line) VALUES (?,?,'MAPS_TO_TABLE',?)", ids.get(new Key("entity", (String) ent.get("name"), repo)), ids.get(new Key("table", tbl, "")), ent.get("line_start"));
        }
        // A component calls an endpoint directly, or through a service it injects.
        List<Map<String, Object>> eps = jdbc.queryForList("SELECT name, attrs->>'method' AS method, attrs->>'path' AS path FROM modern_unit WHERE repo = ? AND kind = 'endpoint'", repo);
        Map<String, List<JsonNode>> serviceCalls = new HashMap<>();
        for (Map<String, Object> s : jdbc.queryForList("SELECT name, attrs::text AS a FROM modern_unit WHERE repo = ? AND kind = 'ng_service'", repo)) {
            List<JsonNode> calls = new ArrayList<>();
            JSON.readTree((String) s.get("a")).path("http_calls").forEach(calls::add);
            serviceCalls.put((String) s.get("name"), calls);
        }
        for (Map<String, Object> c : jdbc.queryForList("SELECT name, attrs::text AS a FROM modern_unit WHERE repo = ? AND kind = 'ng_component'", repo)) {
            JsonNode a = JSON.readTree((String) c.get("a"));
            List<JsonNode> calls = new ArrayList<>();
            a.path("http_calls").forEach(calls::add);
            a.path("injects").forEach(i -> calls.addAll(serviceCalls.getOrDefault(i.asText(), List.of())));
            for (JsonNode call : calls) {
                for (Map<String, Object> ep : eps) {
                    if (!ep.get("method").equals(call.get("verb").asText()) || !samePath((String) ep.get("path"), call.get("url").asText())) continue;
                    Map<Key, Long> ids = nodes.ensure(List.of(new Key("ng_component", (String) c.get("name"), repo), new Key("endpoint", (String) ep.get("name"), repo)));
                    jdbc.update("INSERT INTO graph_edge (src_id, dst_id, type, line) VALUES (?,?,'CALLS_ENDPOINT',?)", ids.get(new Key("ng_component", (String) c.get("name"), repo)), ids.get(new Key("endpoint", (String) ep.get("name"), repo)), call.get("line").asInt());
                }
            }
        }
    }

    /** {id} on the server and ${id}, seen as {}, on the client are the same segment. */
    private static boolean samePath(String server, String client) {
        String a = server.replaceAll("\\{[^}]*\\}", "{}");
        String b = client.replaceAll("^https?://[^/]+", "").replaceAll("\\?.*$", "");
        return a.equals(b);
    }
}
