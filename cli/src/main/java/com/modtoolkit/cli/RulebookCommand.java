package com.modtoolkit.cli;

import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.rulebook.BriefGenerator;
import com.modtoolkit.rulebook.ConventionAnalyzer;
import com.modtoolkit.rulebook.Conventions;
import com.modtoolkit.rulebook.MappingRules;
import com.modtoolkit.rulebook.RulebookGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "rulebook", description = "Analyze existing conventions, and generate the instructions agents read.", subcommands = {RulebookCommand.Analyze.class, RulebookCommand.Generate.class})
public class RulebookCommand {
    @Command(name = "analyze", description = "Draft conventions from the existing code for a lead to edit.")
    static class Analyze implements Callable<Integer> {
        @Option(names = "--repo", required = true) Path repo;
        @Option(names = "--out", defaultValue = "conventions.yaml") Path out;
        @Option(names = "--rules", description = "Directory of client mapping rules") Path rules;

        @Override
        public Integer call() throws Exception {
            Conventions c = ConventionAnalyzer.analyze(repo, MappingRules.load(rules));
            c.save(out);
            System.out.println("wrote " + out + "; " + c.conflicts.size() + " rule conflicts await a decision");
            return 0;
        }
    }

    @Command(name = "generate", description = "Write the rulebook into the target repository.")
    static class Generate implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--conventions", description = "The edited conventions file; analyzed from --out when absent") Path conventions;
        @Option(names = "--rules", description = "Directory of client mapping rules") Path rules;
        @Option(names = "--briefs", negatable = true, defaultValue = "true", description = "Also write migration briefs from the database") boolean briefs;

        @Override
        public Integer call() throws Exception {
            ToolkitConfig cfg = ToolkitConfig.load(config);
            MappingRules r = MappingRules.load(rules);
            Conventions c = conventions != null && Files.exists(conventions) ? Conventions.load(conventions) : ConventionAnalyzer.analyze(out, r);
            RulebookGenerator.Output o = new RulebookGenerator().generate(out, cfg, r, c);
            System.out.println("wrote " + o.files().size() + " files");
            o.pendingDecisions().forEach(p -> System.out.println("DECISION NEEDED " + p));
            if (briefs) {
                try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
                    List<String> scope = cfg.scope() == null || cfg.scope().phase1Programs() == null ? List.of() : cfg.scope().phase1Programs();
                    System.out.println("wrote " + ctx.getBean(BriefGenerator.class).generate(out, scope) + " migration briefs");
                }
            }
            List<String> problems = RulebookGenerator.lint(out);
            problems.forEach(p -> System.out.println("LINT " + p));
            return problems.isEmpty() ? 0 : 1;
        }
    }
}
