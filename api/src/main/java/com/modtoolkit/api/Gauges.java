package com.modtoolkit.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** The operations numbers the spec names, read from the database so every process agrees. */
@Configuration
@ConditionalOnWebApplication
public class Gauges {
    public Gauges(MeterRegistry r, JdbcTemplate j) {
        Gauge.builder("toolkit.members.parsed.per.minute", j, x -> value(x, "SELECT count(*) FROM member WHERE parse_status = 'parsed' AND updated_at > now() - interval '1 minute'")).register(r);
        Gauge.builder("toolkit.members.pending", j, x -> value(x, "SELECT count(*) FROM member WHERE parse_status = 'pending'")).description("queue depth").register(r);
        Gauge.builder("toolkit.review.queue", j, x -> value(x, "SELECT count(*) FROM knowledge_artifact WHERE status = 'draft'")).description("artifacts awaiting an SME").register(r);
        Gauge.builder("toolkit.llm.tokens", j, x -> value(x, "SELECT coalesce(sum(tokens_in + tokens_out), 0) FROM llm_run")).register(r);
        Gauge.builder("toolkit.llm.cost.usd", j, x -> value(x, "SELECT coalesce(sum(cost_usd), 0) FROM llm_run")).register(r);
    }

    private static double value(JdbcTemplate j, String sql) {
        Number n = j.queryForObject(sql, Number.class);
        return n == null ? 0 : n.doubleValue();
    }
}
