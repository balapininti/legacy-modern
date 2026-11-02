package com.modtoolkit.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.migration.MigrationService;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Writes JUnit 5 tests, fixtures and the approved deviations into the target repository. */
@Service
public class TestGenerator {
    public record Result(Path testFile, int fixtures, int deviations) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MigrationService migration;

    public TestGenerator(JdbcTemplate jdbc, MigrationService migration) {
        this.jdbc = jdbc;
        this.migration = migration;
    }

    /**
     * @param adapter fully qualified class in the target repo with {@code JsonNode run(JsonNode input)}; it binds the
     *     fixture format to the service or batch step under test
     */
    public Result generate(String program, Path repo, String testPackage, String adapter) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT f.name, f.input_json::text AS input, f.expected_json::text AS expected, c.source FROM fixture f JOIN capture c ON c.id = f.capture_id
                WHERE upper(c.subject_id) = upper(?) AND f.id IN (SELECT max(f2.id) FROM fixture f2 JOIN capture c2 ON c2.id = f2.capture_id WHERE upper(c2.subject_id) = upper(?) GROUP BY f2.name) ORDER BY f.name""", program, program);
        if (rows.isEmpty()) throw new IllegalStateException("no fixtures for " + program + ": capture it or import exported datasets first");
        Path res = Files.createDirectories(repo.resolve("src/test/resources/characterization/" + program));
        List<String> names = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String n = (String) r.get("name");
            names.add(n);
            Files.writeString(res.resolve(n + ".input.json"), pretty((String) r.get("input")));
            Files.writeString(res.resolve(n + ".expected.json"), pretty((String) r.get("expected")));
        }
        // The register at generation time; a change to it needs a regeneration, which is a visible diff in review.
        List<Object> devs = new ArrayList<>();
        for (Map<String, Object> d : migration.deviations("approved", program)) {
            Map<String, Object> o = new HashMap<>();
            o.put("id", d.get("id"));
            o.put("change", d.get("change"));
            o.put("reason", d.get("reason"));
            o.put("approved_by", d.get("approved_by"));
            o.put("comparison_rule", JSON.readTree(String.valueOf(d.get("comparison_rule"))));
            devs.add(o);
        }
        Files.writeString(res.resolve("deviations.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(devs));

        Path pkg = Files.createDirectories(repo.resolve("src/test/java/" + testPackage.replace('.', '/')));
        String className = Character.toUpperCase(program.charAt(0)) + program.substring(1).toLowerCase() + "CharacterizationTest";
        Map<String, Object> m = new HashMap<>();
        m.put("testPackage", testPackage);
        m.put("program", program);
        m.put("className", className);
        m.put("adapter", adapter);
        m.put("fixtures", names);
        m.put("source", rows.get(0).get("source"));
        Files.writeString(pkg.resolve("CharacterizationSupport.java"), render("CharacterizationSupport.java.ftl", m));
        Path test = pkg.resolve(className + ".java");
        Files.writeString(test, render("CharacterizationTest.java.ftl", m));

        for (Long id : jdbc.queryForList("SELECT id FROM program WHERE upper(program_id) = upper(?)", Long.class, program)) {
            migration.advance(id, "tests_generated", null);
            jdbc.update("UPDATE migration_status SET test_count = ?, updated_at = now() WHERE program_id = ?", names.size(), id);
        }
        return new Result(test, names.size(), devs.size());
    }

    private static String pretty(String json) throws Exception {
        JsonNode n = JSON.readTree(json);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n);
    }

    private static String render(String template, Map<String, Object> model) throws Exception {
        Configuration c = new Configuration(Configuration.VERSION_2_3_32);
        c.setClassLoaderForTemplateLoading(TestGenerator.class.getClassLoader(), "verify");
        c.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        c.setLogTemplateExceptions(false);
        StringWriter w = new StringWriter();
        c.getTemplate(template).process(model, w);
        return w.toString();
    }

    /** Reads a target repo's test results back: passing fixtures move the stage; differences become candidates. */
    public Map<String, Object> report(String program, Path repo) throws Exception {
        int passed = 0, total = 0;
        Path reports = repo.resolve("target/surefire-reports");
        String cls = Character.toUpperCase(program.charAt(0)) + program.substring(1).toLowerCase() + "CharacterizationTest";
        if (Files.isDirectory(reports)) {
            try (var s = Files.list(reports)) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(cls + ".txt")).toList()) {
                    var m = java.util.regex.Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)").matcher(Files.readString(f));
                    if (m.find()) {
                        total = Integer.parseInt(m.group(1));
                        passed = total - Integer.parseInt(m.group(2)) - Integer.parseInt(m.group(3));
                    }
                }
            }
        }
        int candidates = 0;
        Path diffs = repo.resolve("target/characterization-diffs");
        if (Files.isDirectory(diffs)) {
            try (var s = Files.list(diffs)) {
                java.util.Set<String> seen = new java.util.TreeSet<>();
                for (Path f : s.filter(p -> p.getFileName().toString().startsWith(program + "-")).sorted().toList()) {
                    JsonNode d = JSON.readTree(f.toFile());
                    for (JsonNode x : d.path("differences")) {
                        String field = x.path("field").asText();
                        if (!seen.add(field)) continue;
                        // A proposal only: a lead approves it as intended, or sends it back as a bug.
                        var legacy = JSON.createObjectNode().put("program", program).put("field", field);
                        var modern = JSON.createObjectNode().put("program", program);
                        var rule = JSON.createObjectNode().put("kind", "ignore_field").put("field", field);
                        boolean exists = jdbc.queryForObject("SELECT count(*) FROM deviation WHERE status = 'candidate' AND legacy_ref->>'program' = ? AND legacy_ref->>'field' = ?", Long.class, program, field) > 0;
                        if (!exists) {
                            migration.createDeviation(legacy, modern, "Harness found a difference in " + field + ": expected " + x.path("expected").asText() + ", got " + x.path("actual").asText(), "Unregistered difference from capture; approve as intended or send back as a bug", rule, "candidate");
                            candidates++;
                        }
                    }
                }
            }
        }
        for (Long id : jdbc.queryForList("SELECT id FROM program WHERE upper(program_id) = upper(?)", Long.class, program)) {
            jdbc.update("INSERT INTO migration_status (program_id, stage) VALUES (?, 'analyzed') ON CONFLICT DO NOTHING", id);
            jdbc.update("UPDATE migration_status SET test_count = ?, tests_passing = ?, updated_at = now() WHERE program_id = ?", total, passed, id);
            if (total > 0 && passed == total) migration.advance(id, "tests_passing", null);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("tests", total);
        out.put("passing", passed);
        out.put("candidate_deviations", candidates);
        return out;
    }
}
