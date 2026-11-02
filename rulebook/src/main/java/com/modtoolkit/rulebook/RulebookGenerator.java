package com.modtoolkit.rulebook;

import com.modtoolkit.core.config.ToolkitConfig;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders the rulebook into the target repository from toolkit.yaml, the conventions file and the mapping rules. */
public final class RulebookGenerator {
    public record Output(List<Path> files, List<String> pendingDecisions) {}

    private static final List<String> SKILLS = List.of("cobol-to-java-mapping", "cics-to-rest", "batch-to-spring-batch", "db2-to-jpa");
    private static final List<String> PROMPTS = List.of("translate-program", "translate-batch-job", "build-screen", "write-tests");
    private static final List<String> AGENTS = List.of("cobol-analyst", "java-translator", "test-writer");

    private final Configuration fm;

    public RulebookGenerator() {
        fm = new Configuration(Configuration.VERSION_2_3_32);
        fm.setClassLoaderForTemplateLoading(RulebookGenerator.class.getClassLoader(), "rulebook/templates");
        fm.setDefaultEncoding("UTF-8");
        fm.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        fm.setLogTemplateExceptions(false);
    }

    public Output generate(Path out, ToolkitConfig cfg, MappingRules rules, Conventions conv) throws Exception {
        Map<String, Object> model = model(cfg, rules, conv);
        List<Path> written = new ArrayList<>();
        written.add(render("copilot-instructions.md.ftl", model, out.resolve(".github/copilot-instructions.md")));
        written.add(render("CLAUDE.md.ftl", model, out.resolve("CLAUDE.md")));
        for (String p : PROMPTS) written.add(render("prompts/" + p + ".prompt.md.ftl", model, out.resolve(".github/prompts/" + p + ".prompt.md")));
        for (String a : AGENTS) written.add(render("agents/" + a + ".agent.md.ftl", model, out.resolve(".github/agents/" + a + ".agent.md")));
        for (String s : SKILLS) written.add(render("skills/" + s + ".SKILL.md.ftl", model, out.resolve(".github/skills/" + s + "/SKILL.md")));
        @SuppressWarnings("unchecked") List<String> pending = (List<String>) model.get("pending");
        return new Output(written, pending);
    }

    private Map<String, Object> model(ToolkitConfig cfg, MappingRules rules, Conventions conv) {
        Map<String, Object> m = new HashMap<>();
        ToolkitConfig.Target t = cfg.target();
        m.put("client", cfg.client() == null ? "this client" : cfg.client());
        m.put("java", t != null && t.java() != null ? String.valueOf(t.java()) : "21");
        m.put("springBoot", t != null && t.springBoot() != null ? t.springBoot() : "3");
        m.put("angular", t != null && t.angular() != null ? String.valueOf(t.angular()) : "");
        m.put("packageRoot", t != null && t.packageRoot() != null ? t.packageRoot() : "the root package");
        m.put("conventionsMarkdown", conv.toMarkdown());
        List<Map<String, Object>> ruleModel = rules.asModel();
        List<String> pending = new ArrayList<>(), decisions = new ArrayList<>();
        for (Conventions.RuleConflict c : conv.conflicts) {
            switch (c.decision == null ? "pending" : c.decision) {
                case "keep_existing" -> {
                    // The shipped wording must not contradict the decision that overrides it.
                    ruleModel.stream().filter(r -> c.ruleId.equals(r.get("id"))).forEach(r -> r.put("guidance", (c.keepExistingNote != null ? c.keepExistingNote : "Existing code stays as it is.") + " Decision recorded for this client: " + c.message + "."));
                    decisions.add("`" + c.ruleId + "`: keep the existing approach. " + c.message + ".");
                }
                case "apply_rule" -> decisions.add("`" + c.ruleId + "`: apply the rule; new code follows it and existing code is migrated. " + c.message + ".");
                default -> {
                    pending.add("`" + c.ruleId + "`: " + c.message + ". Evidence: " + String.join("; ", c.evidence));
                    ruleModel.removeIf(r -> c.ruleId.equals(r.get("id")));
                }
            }
        }
        m.put("rules", ruleModel);
        m.put("pending", pending);
        m.put("decisions", decisions);
        return m;
    }

    private Path render(String template, Map<String, Object> model, Path to) throws Exception {
        Files.createDirectories(to.getParent());
        Files.writeString(to, renderToString(template, model));
        return to;
    }

    public String renderToString(String template, Map<String, Object> model) throws Exception {
        Template t = fm.getTemplate(template);
        StringWriter w = new StringWriter();
        t.process(model, w);
        return w.toString();
    }

    public static List<String> lint(Path out) throws IOException {
        return RulebookLint.lint(out, PROMPTS, AGENTS, SKILLS);
    }
}
