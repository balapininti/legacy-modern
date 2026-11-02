package com.modtoolkit.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            s.put("chunks", j.queryForMap("SELECT count(*) AS total, count(embedding) AS embedded FROM chunk"));
            s.put("llm_runs", j.queryForList("SELECT id, model, status, units, tokens_in, tokens_out, cost_usd FROM llm_run ORDER BY id DESC LIMIT 5"));
            s.put("migration", j.queryForList("SELECT stage, count(*) AS n FROM migration_status GROUP BY 1 ORDER BY 1"));
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(s));
        }
        return 0;
    }
}
