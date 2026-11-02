package com.modtoolkit.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.discovery.DiscoverOptions;
import com.modtoolkit.discovery.DiscoveryReports;
import com.modtoolkit.discovery.DiscoveryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "discover", description = "Parse the source export into inventory, graph and metrics. No LLM involved.")
public class DiscoverCommand implements Callable<Integer> {
    @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;
    @Option(names = "--root", description = "Overrides source.root") Path root;
    @Option(names = "--threads", description = "Parallel members; defaults to the CPU count") Integer threads;
    @Option(names = "--member-timeout-seconds", defaultValue = "300") int timeout;
    @Option(names = "--report", description = "Write the full reports as JSON to this file") Path report;

    @Override
    public Integer call() throws Exception {
        ToolkitConfig cfg = Files.exists(config) ? ToolkitConfig.load(config) : null;
        Path r = root != null ? root : cfg != null && cfg.source() != null ? Path.of(cfg.source().root()) : null;
        if (r == null) {
            System.err.println("no source root: pass --root or set source.root in " + config);
            return 2;
        }
        DiscoverOptions o = DiscoverOptions.of(r).withTimeout(Duration.ofSeconds(timeout));
        if (threads != null) o = o.withThreads(threads);
        if (cfg != null && cfg.source() != null) {
            var s = cfg.source();
            o = new DiscoverOptions(r, s.encoding() == null ? "auto" : s.encoding(), s.format() == null ? "auto" : s.format(),
                    s.include() == null ? o.include() : s.include(), s.exclude() == null ? o.exclude() : concat(s.exclude(), o.exclude()), o.threads(), o.memberTimeout());
        }
        long t0 = System.nanoTime();
        try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
            ctx.getBean(DiscoveryService.class).run(o);
            DiscoveryReports reports = ctx.getBean(DiscoveryReports.class);
            ObjectMapper json = new ObjectMapper();
            Map<String, Object> summary = new LinkedHashMap<>(reports.summary());
            summary.put("elapsed_seconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
            System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
            if (report != null) {
                Map<String, Object> full = new LinkedHashMap<>(summary);
                full.put("parse_failures", reports.parseFailures());
                full.put("unknown_members", reports.unknownMembers());
                full.put("unresolved_calls", reports.unresolvedCalls());
                full.put("calls_to_missing_programs", reports.callsToMissingPrograms());
                full.put("missing_copybooks", reports.missingCopybooks());
                full.put("dead_code_candidates", reports.deadCodeCandidates());
                json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), full);
            }
        }
        return 0;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
