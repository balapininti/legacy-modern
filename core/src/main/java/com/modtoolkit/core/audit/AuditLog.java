package com.modtoolkit.core.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only by database trigger. */
@Service
public class AuditLog {
    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String user, String action, String tool, String paramsHash, long latencyMs, Long tokens, String outcome) {
        jdbc.update("INSERT INTO audit_log (\"user\", action, tool, params_hash, latency_ms, tokens, outcome) VALUES (?,?,?,?,?,?,?)", user, action, tool, paramsHash, latencyMs, tokens, outcome);
    }
}
