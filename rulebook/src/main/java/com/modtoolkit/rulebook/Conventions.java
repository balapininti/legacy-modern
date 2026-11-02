package com.modtoolkit.rulebook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** What the analyzer found, in a file a lead edits before generation. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Conventions {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** decision: pending until a lead sets apply_rule or keep_existing. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuleConflict {
        public String ruleId;
        public String message;
        public List<String> evidence = new ArrayList<>();
        public String decision = "pending";
        public String keepExistingNote;
    }

    public List<String> packageLayout = new ArrayList<>();
    public List<String> naming = new ArrayList<>();
    public List<String> dtoAndMapper = new ArrayList<>();
    public List<String> errorHandling = new ArrayList<>();
    public List<String> logging = new ArrayList<>();
    public List<String> persistence = new ArrayList<>();
    public List<String> angular = new ArrayList<>();
    public List<RuleConflict> conflicts = new ArrayList<>();

    public void save(Path p) throws IOException {
        YAML.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), this);
    }

    public static Conventions load(Path p) throws IOException {
        return YAML.readValue(p.toFile(), Conventions.class);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        section(sb, "Packages", packageLayout);
        section(sb, "Naming", naming);
        section(sb, "DTOs and mappers", dtoAndMapper);
        section(sb, "Error handling", errorHandling);
        section(sb, "Logging", logging);
        section(sb, "Persistence", persistence);
        section(sb, "Angular", angular);
        if (sb.isEmpty()) sb.append("No existing code to learn from yet. Follow the target stack's standard conventions.\n");
        return sb.toString().stripTrailing();
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        sb.append("### ").append(title).append("\n\n");
        lines.forEach(l -> sb.append("- ").append(l).append('\n'));
        sb.append('\n');
    }
}
