package com.modtoolkit.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.knowledge.EmbedderFactory;
import com.modtoolkit.retrieval.Embedder;
import com.modtoolkit.retrieval.HybridSearch;
import com.modtoolkit.retrieval.SearchHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Scores retrieval against a question set: a hit counts when it names the expected member and its lines overlap. */
@Command(name = "eval", description = "Report recall@k and MRR for a retrieval question set.")
public class EvalCommand implements Callable<Integer> {
    @Option(names = "--questions", required = true) Path questions;
    @Option(names = {"-c", "--config"}, defaultValue = "toolkit.yaml") Path config;
    @Option(names = "--k", defaultValue = "5") int k;
    @Option(names = "--out", description = "Write per-question results here") Path out;
    @Option(names = "--lexical-only", description = "Skip embeddings, to measure what they add") boolean lexicalOnly;

    static boolean matches(SearchHit h, JsonNode e) {
        if (h.member() == null || !h.member().equals(e.get("member").asText()) || h.lineStart() == null) return false;
        return h.lineStart() <= e.get("line_end").asInt() && e.get("line_start").asInt() <= h.lineEnd();
    }

    @Override
    public Integer call() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode set = om.readTree(questions.toFile());
        Embedder e = lexicalOnly || !Files.exists(config) ? null : EmbedderFactory.create(ToolkitConfig.load(config).llm(), System::getenv);
        try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
            HybridSearch search = ctx.getBean(HybridSearch.class);
            int hits = 0, n = 0;
            double rr = 0;
            Map<String, int[]> byKind = new LinkedHashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            String mode = null;
            for (JsonNode q : set.get("questions")) {
                n++;
                var res = search.search(q.get("question").asText(), List.of(), Math.max(k, 10), e);
                mode = res.mode();
                int rank = 0;
                outer:
                for (int i = 0; i < res.hits().size(); i++) {
                    for (JsonNode x : q.get("expected")) {
                        if (matches(res.hits().get(i), x)) {
                            rank = i + 1;
                            break outer;
                        }
                    }
                }
                boolean hit = rank > 0 && rank <= k;
                if (hit) hits++;
                if (rank > 0) rr += 1.0 / rank;
                int[] kk = byKind.computeIfAbsent(q.get("kind").asText(), z -> new int[2]);
                kk[0] += hit ? 1 : 0;
                kk[1]++;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", q.get("id").asText());
                row.put("kind", q.get("kind").asText());
                row.put("rank", rank);
                row.put("question", q.get("question").asText());
                row.put("top", res.hits().stream().limit(3).map(h -> h.member() + ":" + h.lineStart() + "-" + h.lineEnd() + " [" + h.kind() + "]").toList());
                rows.add(row);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mode", mode);
            summary.put("questions", n);
            summary.put("recall_at_" + k, Math.round(hits * 1000.0 / n) / 1000.0);
            summary.put("mrr", Math.round(rr / n * 1000) / 1000.0);
            Map<String, String> kinds = new LinkedHashMap<>();
            byKind.forEach((a, b) -> kinds.put(a, b[0] + "/" + b[1]));
            summary.put("by_kind", kinds);
            System.out.println(om.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
            if (out != null) {
                Map<String, Object> full = new LinkedHashMap<>(summary);
                full.put("results", rows);
                om.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), full);
            }
        }
        return 0;
    }
}
