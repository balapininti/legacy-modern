package com.modtoolkit.rulebook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Rules from rulebook/rules/*.yaml. A client directory adds rules, or replaces a shipped rule with the same id. */
public final class MappingRules {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conflict(String regex, String message, String keepExistingNote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String id, List<String> features, String cobol, String java, String guidance, String example, Conflict conflict) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    private static final Map<String, List<String>> SKILLS = Map.of(
            "cobol-to-java-mapping", List.of("numeric-integer", "comp3-decimal", "pic-x-string", "level-88", "occurs-list", "redefines-views", "group-move", "date-formats", "dynamic-call"),
            "cics-to-rest", List.of("cics-rest", "bms-angular"),
            "batch-to-spring-batch", List.of("jcl-batch", "sort-step", "gdg-versioning", "ebcdic-collation"),
            "db2-to-jpa", List.of("sql-jpa", "file-vsam-repository"));

    private final Map<String, Rule> rules = new LinkedHashMap<>();

    public static MappingRules load(Path extraDir) throws IOException {
        MappingRules m = new MappingRules();
        try (InputStream in = MappingRules.class.getResourceAsStream("/rulebook/rules/core-mappings.yaml")) {
            m.read(YAML.readTree(in));
        }
        if (extraDir != null) {
            try (Stream<Path> s = Files.list(extraDir)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".yaml")).sorted().toList()) m.read(YAML.readTree(p.toFile()));
            }
        }
        return m;
    }

    private void read(JsonNode doc) throws IOException {
        for (JsonNode r : doc.path("rules")) {
            Rule rule = YAML.treeToValue(r, Rule.class);
            if (rule.id() == null || rule.java() == null) throw new IOException("a mapping rule needs id and java: " + r);
            rules.put(rule.id(), rule);
        }
    }

    public List<Rule> all() {
        return new ArrayList<>(rules.values());
    }

    public Optional<Rule> forFeature(String feature) {
        String f = feature.toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        for (Rule r : rules.values()) {
            if (r.id().equals(f) || (r.features() != null && r.features().contains(f))) return Optional.of(r);
        }
        return Optional.empty();
    }

    public List<String> knownFeatures() {
        List<String> out = new ArrayList<>();
        rules.values().forEach(r -> {
            if (r.features() != null) out.addAll(r.features());
        });
        return out;
    }

    /** For templates: each rule as a map, with the skills it belongs in. */
    public List<Map<String, Object>> asModel() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rule r : rules.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("cobol", r.cobol() == null ? r.id() : r.cobol());
            m.put("java", r.java());
            m.put("guidance", r.guidance() == null ? "" : r.guidance());
            m.put("example", r.example() == null ? "" : r.example());
            List<String> skills = new ArrayList<>();
            SKILLS.forEach((k, v) -> {
                if (v.contains(r.id())) skills.add(k);
            });
            if (skills.isEmpty()) skills.add("cobol-to-java-mapping");
            m.put("skills", skills);
            out.add(m);
        }
        return out;
    }
}
