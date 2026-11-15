package com.modtoolkit.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.VectorSupport;
import com.modtoolkit.discovery.DiscoveryReports;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import picocli.CommandLine.Command;

@Command(name = "status", description = "Where this estate stands: inventory, knowledge, index and migration.")
public class StatusCommand implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        try (ConfigurableApplicationContext ctx = ToolkitApp.batch()) {
            JdbcTemplate j = ctx.getBean(JdbcTemplate.class);
            Map<String, Object> s = new LinkedHashMap<>(ctx.getBean(DiscoveryReports.class).summary());
            s.put("artifacts", j.queryForList("SELECT kind, status, count(*) AS n FROM knowledge_artifact GROUP BY 1, 2 ORDER BY 1, 2"));
            s.put("chunks", chunks(j));
            s.put("llm_runs", j.queryForList("SELECT id, model, status, units, tokens_in, tokens_out, cost_usd FROM llm_run ORDER BY id DESC LIMIT 5"));
            s.put("migration", j.queryForList("SELECT stage, count(*) AS n FROM migration_status GROUP BY 1 ORDER BY 1"));
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(s));
        }
        return 0;
    }

    /** Without pgvector there is no embedding column to count: say so instead. */
    static Map<String, Object> chunks(JdbcTemplate j) {
        if (VectorSupport.available(j)) {
            Map<String, Object> m = new LinkedHashMap<>(j.queryForMap("SELECT count(*) AS total, count(embedding) AS embedded FROM chunk"));
            m.put("embeddings", "available");
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>(j.queryForMap("SELECT count(*) AS total FROM chunk"));
        m.put("embeddings", "unavailable: pgvector is not installed on this PostgreSQL, search is lexical-only; to turn it on, " + VectorSupport.HOW_TO_ENABLE);
        return m;
    }
}
