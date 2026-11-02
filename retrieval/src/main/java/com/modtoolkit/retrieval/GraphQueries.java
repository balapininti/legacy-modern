package com.modtoolkit.retrieval;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Parameterized recursive CTEs over graph_edge. Cycles stop at the first repeat of a node on a path. */
@Service
public class GraphQueries {
    private static final String CALL_TYPES = "ARRAY['CALLS','LINKS','XCTLS']";

    private final JdbcTemplate jdbc;

    public GraphQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> callers(String program, int depth) {
        return walk(program, depth, "dst_id", "src_id", CALL_TYPES, "program");
    }

    public List<Map<String, Object>> callees(String program, int depth) {
        return walk(program, depth, "src_id", "dst_id", CALL_TYPES, "program");
    }

    private List<Map<String, Object>> walk(String name, int depth, String from, String to, String types, String kind) {
        String sql = """
                WITH RECURSIVE w(edge_id, node_id, depth, path) AS (
                  SELECT e.id, e.%2$s, 1, ARRAY[start.id, e.%2$s]
                  FROM graph_node start JOIN graph_edge e ON e.%1$s = start.id
                  WHERE start.kind = ? AND upper(start.name) = upper(?) AND e.type = ANY(%3$s)
                  UNION ALL
                  SELECT e.id, e.%2$s, w.depth + 1, w.path || e.%2$s
                  FROM w JOIN graph_edge e ON e.%1$s = w.node_id
                  WHERE w.depth < ? AND e.type = ANY(%3$s) AND NOT e.%2$s = ANY(w.path)
                )
                SELECT DISTINCT s.name AS from_program, d.name AS to_program, e.type AS edge_type,
                       e.attrs->>'mode' AS call_mode, e.attrs->>'via' AS via, w.depth, m.path AS member, e.line
                FROM w JOIN graph_edge e ON e.id = w.edge_id JOIN graph_node s ON s.id = e.src_id JOIN graph_node d ON d.id = e.dst_id
                LEFT JOIN member m ON m.id = e.member_id
                ORDER BY w.depth, s.name, d.name, m.path, e.line
                """.formatted(from, to, types);
        return jdbc.queryForList(sql, kind, name, depth);
    }
}
