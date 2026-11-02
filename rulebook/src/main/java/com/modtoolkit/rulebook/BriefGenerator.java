package com.modtoolkit.rulebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.migration.MigrationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** docs/migration-briefs/<program>.md: what an agent reads before translating. Writing one moves the program's stage. */
@Service
public class BriefGenerator {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MigrationService migration;
    private final RulebookGenerator renderer = new RulebookGenerator();

    public BriefGenerator(JdbcTemplate jdbc, MigrationService migration) {
        this.jdbc = jdbc;
        this.migration = migration;
    }

    public int generate(Path out, List<String> scope) throws Exception {
        Path dir = Files.createDirectories(out.resolve("docs/migration-briefs"));
        int n = 0;
        for (Map<String, Object> p : jdbc.queryForList("""
                SELECT p.id, p.program_id, p.kind, p.line_start, p.line_end, m.path, coalesce(s.stage, 'analyzed') AS stage
                FROM program p JOIN member m ON m.id = p.member_id LEFT JOIN migration_status s ON s.program_id = p.id WHERE p.parent_id IS NULL ORDER BY p.program_id""")) {
            String name = (String) p.get("program_id");
            if (!scope.isEmpty() && scope.stream().noneMatch(name::equalsIgnoreCase)) continue;
            long id = ((Number) p.get("id")).longValue();
            Files.writeString(dir.resolve(name + ".md"), renderer.renderToString("migration-brief.md.ftl", model(p, id, name)));
            migration.advance(id, "brief_generated", null);
            n++;
        }
        return n;
    }

    private Map<String, Object> model(Map<String, Object> p, long id, String name) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("program", name);
        m.put("member", p.get("path"));
        m.put("lineStart", p.get("line_start"));
        m.put("lineEnd", p.get("line_end"));
        m.put("kind", p.get("kind"));
        m.put("stage", p.get("stage"));
        m.put("callers", names("SELECT DISTINCT s.name FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id WHERE d.kind='program' AND d.name=? AND e.type IN ('CALLS','LINKS','XCTLS') ORDER BY 1", name));
        m.put("callees", names("SELECT DISTINCT d.name FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id WHERE s.kind='program' AND s.name=? AND e.type IN ('CALLS','LINKS','XCTLS') ORDER BY 1", name));
        m.put("tables", names("SELECT DISTINCT d.name FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id JOIN program q ON q.id=e.program_id WHERE q.program_id=? AND d.kind='table' ORDER BY 1", name));
        m.put("jobs", names("SELECT DISTINCT j.name FROM jcl_step s JOIN jcl_job j ON j.id=s.job_id WHERE upper(s.pgm)=upper(?) ORDER BY 1", name));
        JsonNode sum = artifact(id, "summary");
        if (sum != null) {
            Map<String, Object> s = new HashMap<>();
            s.put("purpose", sum.get("c").path("purpose").asText());
            s.put("inputs", strings(sum.get("c").path("inputs")));
            s.put("outputs", strings(sum.get("c").path("outputs")));
            s.put("sideEffects", strings(sum.get("c").path("side_effects")));
            s.put("errorHandling", sum.get("c").path("error_handling").asText());
            s.put("status", sum.get("s").asText());
            m.put("summary", s);
        } else {
            m.put("summary", "");
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        JsonNode r = artifact(id, "rule");
        if (r != null) {
            for (JsonNode x : r.get("c").path("rules")) {
                rules.add(Map.of("id", x.path("rule_id").asText(), "statement", x.path("statement").asText(), "lineStart", x.path("line_start").asInt(), "lineEnd", x.path("line_end").asInt(), "status", r.get("s").asText()));
            }
        }
        m.put("rules", rules);
        List<Map<String, Object>> notes = new ArrayList<>();
        JsonNode nn = artifact(id, "migration_note");
        if (nn != null) {
            for (JsonNode x : nn.get("c").path("items")) {
                notes.add(Map.of("feature", x.path("feature").asText(), "guidance", x.path("guidance").asText(), "lineStart", x.path("line_start").asInt(), "lineEnd", x.path("line_end").asInt()));
            }
        }
        m.put("notes", notes);
        List<String> devs = new ArrayList<>();
        for (Map<String, Object> d : migration.deviations("approved", name)) devs.add(d.get("change") + " Why: " + d.get("reason") + " Approved by " + d.get("approved_by") + ".");
        m.put("deviations", devs);
        return m;
    }

    private JsonNode artifact(long id, String kind) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT content::text AS c, status AS s FROM knowledge_artifact WHERE unit_kind='program' AND unit_id=? AND kind=? AND status <> 'rejected' ORDER BY (status='reviewed') DESC, id DESC LIMIT 1", id, kind);
        if (rows.isEmpty()) return null;
        Map<String, Object> o = new HashMap<>();
        o.put("c", JSON.readTree((String) rows.get(0).get("c")));
        o.put("s", rows.get(0).get("s"));
        return JSON.valueToTree(o);
    }

    private List<String> names(String sql, String arg) {
        List<String> l = jdbc.queryForList(sql, String.class, arg);
        return l.isEmpty() ? List.of("none") : l;
    }

    private static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asText()));
        return out.isEmpty() ? List.of("none") : out;
    }
}
