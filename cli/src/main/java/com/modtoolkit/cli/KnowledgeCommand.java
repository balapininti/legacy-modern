package com.modtoolkit.cli;

import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.knowledge.KnowledgeJobConfig;
import com.modtoolkit.knowledge.KnowledgeOptions;
import com.modtoolkit.knowledge.KnowledgeRunner;
import com.modtoolkit.knowledge.LlmClient;
import com.modtoolkit.knowledge.LlmFactory;
import com.modtoolkit.knowledge.RecordedLlm;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "knowledge", description = "The LLM knowledge pass.", subcommands = {KnowledgeCommand.Run.class, KnowledgeCommand.Match.class})
public class KnowledgeCommand {
    @Command(name = "match", description = "LLM link candidates from summaries, then rule coverage for programs with confirmed links.")
    static class Match implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;
        @Option(names = "--programs", split = ",") List<String> programs;
        @Option(names = "--record-to") Path recordTo;
        @Option(names = "--replay-from") Path replayFrom;

        @Override
        public Integer call() throws Exception {
            ToolkitConfig.Llm l = ToolkitConfig.load(config).llm();
            LlmClient llm = replayFrom != null ? new RecordedLlm(replayFrom, l.chatModel()) : LlmFactory.chat(l, System::getenv);
            if (recordTo != null) llm = RecordedLlm.recording(llm, recordTo);
            try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
                var m = ctx.getBean(com.modtoolkit.knowledge.LinkMatcher.class);
                List<String> only = programs == null ? List.of() : programs;
                System.out.println(m.matchLinks(llm, only));
                System.out.println(m.checkCoverage(llm, only));
            }
            return 0;
        }
    }

    @Command(name = "run", description = "Generate artifacts. Unchanged units cost nothing; a budget stop resumes on the next run.")
    static class Run implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;
        @Option(names = "--budget", description = "USD cap for this run; overrides llm.budget_usd_per_run") Double budget;
        @Option(names = "--budget-tokens", description = "Token cap for this run") Long budgetTokens;
        @Option(names = "--programs", split = ",", description = "Only units with these names: programs, copybooks, jobs, maps") List<String> programs;
        @Option(names = "--kinds", split = ",", description = "Only these artifact kinds") Set<String> kinds;
        @Option(names = "--record-to", description = "Save every model answer here, to replay in tests") Path recordTo;
        @Option(names = "--replay-from", description = "Answer from saved recordings; nothing is sent anywhere") Path replayFrom;

        @Override
        public Integer call() throws Exception {
            ToolkitConfig.Llm l = ToolkitConfig.load(config).llm();
            if (l == null) {
                System.err.println(config + " has no llm block");
                return 2;
            }
            LlmClient llm = replayFrom != null ? new RecordedLlm(replayFrom, l.chatModel()) : LlmFactory.chat(l, System::getenv);
            if (recordTo != null) llm = RecordedLlm.recording(llm, recordTo);
            KnowledgeOptions o = new KnowledgeOptions(budget != null ? budget : l.budgetUsdPerRun(), budgetTokens != null ? budgetTokens : l.budgetTokensPerRun(),
                    l.inputUsdPerMtok() == null ? 0 : l.inputUsdPerMtok(), l.outputUsdPerMtok() == null ? 0 : l.outputUsdPerMtok(),
                    l.concurrency() == null ? 2 : l.concurrency(), l.maxContextTokens() == null ? 60_000 : l.maxContextTokens(),
                    programs == null ? List.of() : programs, kinds == null ? Set.of() : kinds, Boolean.TRUE.equals(l.redactPii()), l.piiPatterns() == null ? List.of() : l.piiPatterns());
            try (ConfigurableApplicationContext ctx = ToolkitApp.batch(o.concurrency() + 2)) {
                KnowledgeRunner.Stats s = ctx.getBean(KnowledgeJobConfig.Launcher.class).run(llm, o);
                System.out.println(s);
                return s.failed() > 0 ? 3 : 0;
            }
        }
    }
}
