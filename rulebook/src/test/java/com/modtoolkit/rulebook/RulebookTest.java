package com.modtoolkit.rulebook;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.core.config.ToolkitConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulebookTest {
    @TempDir Path dir;
    static final Path SAMPLE = Path.of("../testdata/target-sample").toAbsolutePath().normalize();

    private static ToolkitConfig cfg() {
        return new ToolkitConfig("acme", null, null, null, null, new ToolkitConfig.Target(21, "3.4", 19, "maven", "postgresql", "oidc", "r", "/x", true, "com.acme.card"), null, null);
    }

    @Test
    void rulesAreDataAndTheSpecsCoreMappingsAreAllPresent() throws Exception {
        MappingRules r = MappingRules.load(null);
        for (String feature : List.of("comp-3", "pic-x", "88", "occurs", "redefines", "group-move", "vsam", "cics-link", "bms", "jcl", "sort", "gdg", "ebcdic", "yymmdd", "cics", "pic-9")) {
            assertThat(r.forFeature(feature)).as(feature).isPresent();
        }
        assertThat(r.forFeature("comp-3").get().guidance()).contains("HALF_UP").contains("RoundingMode.DOWN");
        assertThat(r.forFeature("COMP 3")).isPresent();
        assertThat(r.forFeature("nonsense")).isEmpty();
    }

    @Test
    void aClientDirectoryAddsAndReplacesRulesWithoutARebuild() throws Exception {
        Path d = Files.createDirectory(dir.resolve("rules"));
        Files.writeString(d.resolve("client.yaml"), "rules:\n  - id: comp3-decimal\n    features: [comp-3]\n    java: Money\n  - id: acme-dates\n    features: [acme-date]\n    java: AcmeDate\n");
        MappingRules r = MappingRules.load(d);
        assertThat(r.forFeature("comp-3").get().java()).isEqualTo("Money");
        assertThat(r.forFeature("acme-date").get().java()).isEqualTo("AcmeDate");
        assertThat(r.forFeature("gdg")).isPresent();
    }

    @Test
    void conventionsAreLearnedFromTheExistingCodeWithCounts() throws Exception {
        Conventions c = ConventionAnalyzer.analyze(SAMPLE, MappingRules.load(null));
        assertThat(String.join(" ", c.packageLayout)).contains("com.acme.card");
        assertThat(String.join(" ", c.dtoAndMapper)).contains("records");
        assertThat(String.join(" ", c.persistence)).contains("Spring Data").contains("1 JPA entities");
        assertThat(String.join(" ", c.angular)).contains("Standalone").contains("*Api");
        assertThat(c.conflicts).isEmpty();
    }

    @Test
    void generatedFilesLintCleanAndCarryTheConventionsAndTheDeviationRule() throws Exception {
        MappingRules rules = MappingRules.load(null);
        Conventions conv = ConventionAnalyzer.analyze(SAMPLE, rules);
        RulebookGenerator.Output o = new RulebookGenerator().generate(dir, cfg(), rules, conv);
        assertThat(o.files()).hasSize(13);
        assertThat(o.pendingDecisions()).isEmpty();
        assertThat(RulebookGenerator.lint(dir)).isEmpty();
        String inst = Files.readString(dir.resolve(".github/copilot-instructions.md"));
        assertThat(inst).contains("get_program").contains("never reverted to legacy behavior").contains("com.acme.card").contains("BigDecimal").contains("Java 21");
        assertThat(Files.readString(dir.resolve("CLAUDE.md"))).contains("never reverted to legacy behavior").contains("Standalone");
        assertThat(Files.readString(dir.resolve(".github/prompts/translate-program.prompt.md"))).contains("${input:program}").contains("com.acme.card");
        assertThat(Files.readString(dir.resolve(".github/skills/cics-to-rest/SKILL.md"))).contains("name: cics-to-rest").contains("COMMAREA").doesNotContain("COMP-3");
        assertThat(Files.readString(dir.resolve(".github/skills/db2-to-jpa/SKILL.md"))).contains("SQLCODE");
    }

    @Test
    void lintCatchesRealProblems() throws Exception {
        MappingRules rules = MappingRules.load(null);
        new RulebookGenerator().generate(dir, cfg(), rules, new Conventions());
        Path f = dir.resolve(".github/agents/test-writer.agent.md");
        Files.writeString(f, "no front matter, `made_up_tool` and ${leftover}");
        Files.delete(dir.resolve(".github/skills/db2-to-jpa/SKILL.md"));
        Files.writeString(dir.resolve("CLAUDE.md"), "nothing useful");
        List<String> p = RulebookGenerator.lint(dir);
        assertThat(String.join("\n", p)).contains("no frontmatter").contains("made_up_tool").contains("unresolved").contains("missing").contains("never reverted").contains("get_program");
    }

    /** A rule the code contradicts is flagged for a decision, and never applied silently. */
    @Test
    void aConflictingRuleIsHeldBackUntilALeadDecides() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo/src"));
        Files.writeString(repo.resolve("Ledger.java"), "package x;\nclass Ledger {\n  private double accountBalance;\n  org.springframework.jdbc.core.JdbcTemplate jdbc;\n}\n");
        MappingRules rules = MappingRules.load(null);
        Conventions c = ConventionAnalyzer.analyze(dir.resolve("repo"), rules);
        assertThat(c.conflicts).extracting(x -> x.ruleId).containsExactly("comp3-decimal", "file-vsam-repository");
        assertThat(c.conflicts.get(0).evidence.get(0)).contains("Ledger.java:3").contains("double accountBalance");
        assertThat(c.conflicts.get(0).decision).isEqualTo("pending");

        Path out = Files.createDirectory(dir.resolve("out"));
        RulebookGenerator.Output o = new RulebookGenerator().generate(out, cfg(), rules, c);
        assertThat(o.pendingDecisions()).hasSize(2);
        String inst = Files.readString(out.resolve(".github/copilot-instructions.md"));
        assertThat(inst).contains("Open decisions").contains("binary floating point");
        assertThat(inst).as("the rule's own text is withheld while undecided").doesNotContain("Never use double or float");

        // The lead edits the file: the choice survives a save and a load, and takes effect.
        Path file = dir.resolve("conv.yaml");
        c.save(file);
        Files.writeString(file, Files.readString(file).replaceFirst("decision: \"pending\"", "decision: \"apply_rule\"").replaceFirst("decision: \"pending\"", "decision: \"keep_existing\""));
        Conventions decided = Conventions.load(file);
        RulebookGenerator.Output o2 = new RulebookGenerator().generate(out, cfg(), rules, decided);
        assertThat(o2.pendingDecisions()).isEmpty();
        String after = Files.readString(out.resolve(".github/copilot-instructions.md"));
        assertThat(after).contains("Never use double or float").contains("Decisions recorded").contains("Keep JdbcTemplate").doesNotContain("Open decisions");
        assertThat(RulebookGenerator.lint(out)).isEmpty();
    }
}
