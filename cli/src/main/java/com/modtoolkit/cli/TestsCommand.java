package com.modtoolkit.cli;

import com.modtoolkit.verify.TestGenerator;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "tests", description = "Generate characterization tests, and read their results back.", subcommands = {TestsCommand.Generate.class, TestsCommand.Report.class})
public class TestsCommand {
    @Command(name = "generate", description = "Write JUnit 5 tests, fixtures and approved deviations into the target repository.")
    static class Generate implements Callable<Integer> {
        @Option(names = "--program", required = true) String program;
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--package", required = true) String pkg;
        @Option(names = "--adapter", required = true, description = "Class with JsonNode run(JsonNode), binding fixtures to the code under test") String adapter;

        @Override
        public Integer call() throws Exception {
            try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
                System.out.println(ctx.getBean(TestGenerator.class).generate(program, out, pkg, adapter));
            }
            return 0;
        }
    }

    @Command(name = "report", description = "Record results in migration_status; differences become candidate deviations.")
    static class Report implements Callable<Integer> {
        @Option(names = "--program", required = true) String program;
        @Option(names = "--repo", required = true) Path repo;

        @Override
        public Integer call() throws Exception {
            try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
                System.out.println(ctx.getBean(TestGenerator.class).report(program, repo));
            }
            return 0;
        }
    }
}
