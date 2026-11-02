package com.modtoolkit.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.discovery.NodeStore.Key;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Facts that need every member in place: parameter flow across CALL USING, copybook ids, fan-in and fan-out. */
final class GraphLinker {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final NodeStore nodes;

    GraphLinker(JdbcTemplate jdbc, NodeStore nodes) {
        this.jdbc = jdbc;
        this.nodes = nodes;
    }

    void link() throws Exception {
        jdbc.update("UPDATE copy_use u SET copybook_id = c.id FROM copybook c WHERE upper(c.name) = upper(u.copybook_name) AND u.copybook_id IS NULL");
        linkCallUsing();
        // A table holds titles and codes as well as program names; only a name in this estate is a real candidate.
        jdbc.update("""
                UPDATE unresolved_call u SET candidates = coalesce((SELECT jsonb_agg(v) FROM jsonb_array_elements_text(u.candidates) v
                  WHERE EXISTS (SELECT 1 FROM program p WHERE upper(p.program_id) = upper(v))), '[]'::jsonb)
                WHERE jsonb_array_length(u.candidates) > 0""");
        jdbc.update("""
                UPDATE program p SET metrics = p.metrics || jsonb_build_object(
                  'fan_out', (SELECT count(DISTINCT e.dst_id) FROM graph_edge e JOIN graph_node n ON n.id = e.src_id
                              WHERE n.kind = 'program' AND n.name = p.program_id AND e.type IN ('CALLS','LINKS','XCTLS')),
                  'fan_in',  (SELECT count(DISTINCT e.src_id) FROM graph_edge e JOIN graph_node n ON n.id = e.dst_id
                              WHERE n.kind = 'program' AND n.name = p.program_id AND e.type IN ('CALLS','LINKS','XCTLS')))
                """);
    }

    /** Positional: the Nth CALL argument flows into the Nth name on the callee's PROCEDURE DIVISION USING. */
    private void linkCallUsing() throws Exception {
        jdbc.update("DELETE FROM graph_edge WHERE type = 'MOVES_TO' AND attrs->>'op' = 'CALL USING'");
        Map<String, List<String>> usingOf = new HashMap<>();
        jdbc.query("SELECT program_id, metrics->'using' FROM program WHERE metrics ?? 'using'", rs -> {
            try {
                usingOf.put(rs.getString(1), JSON.readValue(rs.getString(2), new TypeReference<List<String>>() {}));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        record Crossing(String caller, String arg, String callee, String param, long programId, long memberId, int line, String target) {}
        List<Crossing> crossings = new ArrayList<>();
        jdbc.query("""
                SELECT s.name, d.name, e.program_id, e.member_id, e.line, e.attrs->'using'
                FROM graph_edge e JOIN graph_node s ON s.id = e.src_id JOIN graph_node d ON d.id = e.dst_id
                WHERE e.type = 'CALLS' AND jsonb_array_length(e.attrs->'using') > 0
                """, rs -> {
            String callee = rs.getString(2);
            List<String> params = usingOf.get(callee);
            if (params == null || params.isEmpty()) return;
            List<String> args;
            try {
                args = JSON.readValue(rs.getString(6), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            for (int i = 0; i < Math.min(args.size(), params.size()); i++) {
                crossings.add(new Crossing(rs.getString(1), args.get(i), callee, params.get(i), rs.getLong(3), rs.getLong(4), rs.getInt(5), callee));
            }
        });
        Set<Key> keys = new HashSet<>();
        for (Crossing c : crossings) {
            keys.add(new Key("data_item", c.arg(), c.caller()));
            keys.add(new Key("data_item", c.param(), c.callee()));
        }
        Map<Key, Long> ids = nodes.ensure(keys);
        jdbc.batchUpdate("INSERT INTO graph_edge (src_id, dst_id, type, program_id, member_id, line, attrs) VALUES (?,?,'MOVES_TO',?,?,?,?::jsonb)", crossings, 500, (ps, c) -> {
            ps.setLong(1, ids.get(new Key("data_item", c.arg(), c.caller())));
            ps.setLong(2, ids.get(new Key("data_item", c.param(), c.callee())));
            ps.setLong(3, c.programId());
            ps.setLong(4, c.memberId());
            ps.setInt(5, c.line());
            try {
                ps.setString(6, JSON.writeValueAsString(Map.of("op", "CALL USING", "callee", c.target())));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
