package com.modtoolkit.rulebook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks generated files the way their consumers will read them. */
public final class RulebookLint {
    /** The frozen contract: a file naming any other tool would send an agent nowhere. */
    public static final Set<String> TOOLS = Set.of("inventory_summary", "search", "get_program", "get_source", "get_copybook", "who_calls", "calls_from", "trace_field", "job_flow", "business_rules", "screen_flow", "table_usage",
            "migration_status", "record_decision", "explain_feature", "get_modern_impl", "gap_report", "deviations", "record_deviation");
    /** Parameter and option names that also appear in backticks. */
    private static final Set<String> NOT_TOOLS = Set.of("expanded_for", "migration_note", "keep_existing", "apply_rule");
    private static final Pattern CODE_WORD = Pattern.compile("`([a-z]+_[a-z_]+)`");
    private static final Pattern UNRESOLVED = Pattern.compile("\\$\\{(?!input:)|<#|</#|\\?\\?\\?");

    private RulebookLint() {}

    static List<String> lint(Path out, List<String> prompts, List<String> agents, List<String> skills) throws IOException {
        List<String> problems = new ArrayList<>();
        List<Path> files = new ArrayList<>(List.of(out.resolve(".github/copilot-instructions.md"), out.resolve("CLAUDE.md")));
        for (String p : prompts) files.add(out.resolve(".github/prompts/" + p + ".prompt.md"));
        for (String a : agents) files.add(out.resolve(".github/agents/" + a + ".agent.md"));
        for (String s : skills) files.add(out.resolve(".github/skills/" + s + "/SKILL.md"));
        for (Path f : files) {
            if (!Files.exists(f)) {
                problems.add(out.relativize(f) + ": missing");
                continue;
            }
            String t = Files.readString(f);
            String name = out.relativize(f).toString();
            if (t.isBlank()) problems.add(name + ": empty");
            if (UNRESOLVED.matcher(t).find()) problems.add(name + ": unresolved template text");
            Matcher m = CODE_WORD.matcher(t);
            while (m.find()) {
                String w = m.group(1);
                if (!TOOLS.contains(w) && !NOT_TOOLS.contains(w)) problems.add(name + ": names unknown tool " + w);
            }
            if (name.contains("prompts/") || name.contains("agents/") || name.contains("skills/")) {
                if (!t.startsWith("---\n") || t.indexOf("\n---\n", 4) < 0) problems.add(name + ": no frontmatter");
                else if (!t.substring(0, t.indexOf("\n---\n", 4)).contains("description:")) problems.add(name + ": frontmatter has no description");
            }
            if (name.contains("skills/") && !t.substring(0, Math.max(0, t.indexOf("\n---\n", 4))).contains("name: ")) problems.add(name + ": skill has no name");
            if (name.contains("skills/") && !t.contains("## ")) problems.add(name + ": skill has no rules");
        }
        for (String must : List.of(".github/copilot-instructions.md", "CLAUDE.md")) {
            Path f = out.resolve(must);
            if (!Files.exists(f)) continue;
            String t = Files.readString(f);
            if (!t.contains("never reverted to legacy behavior")) problems.add(must + ": does not state that a registered deviation is never reverted");
            for (String tool : List.of("get_program", "business_rules", "get_copybook")) if (!t.contains(tool)) problems.add(must + ": does not name " + tool);
        }
        return problems;
    }
}
