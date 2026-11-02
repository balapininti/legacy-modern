package com.modtoolkit.discovery;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Get-or-create for graph nodes, shared across threads. Upserts commit on their own and in sorted order so
 * parallel member transactions never deadlock on a popular node such as a shared copybook or table.
 */
public final class NodeStore {
    public record Key(String kind, String name, String scope) implements Comparable<Key> {
        public Key {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(name);
            scope = scope == null ? "" : scope;
        }

        @Override
        public int compareTo(Key o) {
            int c = kind.compareTo(o.kind);
            if (c == 0) c = name.compareTo(o.name);
            return c != 0 ? c : scope.compareTo(o.scope);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;
    private final ConcurrentHashMap<Key, Long> cache = new ConcurrentHashMap<>();

    public NodeStore(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(tm);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<Key, Long> ensure(Collection<Key> keys) {
        Map<Key, Long> out = new HashMap<>();
        List<Key> missing = new ArrayList<>();
        for (Key k : new TreeSet<>(keys)) {
            Long id = cache.get(k);
            if (id != null) out.put(k, id); else missing.add(k);
        }
        if (!missing.isEmpty()) {
            own.executeWithoutResult(s -> jdbc.execute((java.sql.Connection con) -> {
                String[] kinds = missing.stream().map(Key::kind).toArray(String[]::new);
                String[] names = missing.stream().map(Key::name).toArray(String[]::new);
                String[] scopes = missing.stream().map(Key::scope).toArray(String[]::new);
                Array ka = con.createArrayOf("text", kinds), na = con.createArrayOf("text", names), sa = con.createArrayOf("text", scopes);
                // DO NOTHING, never DO UPDATE: this thread's member transaction may hold locks on these rows, and
                // waiting for them from a second connection is a deadlock Postgres cannot see.
                try (var st = con.prepareStatement(
                        "INSERT INTO graph_node (kind, name, scope) SELECT * FROM unnest(?::text[], ?::text[], ?::text[]) ON CONFLICT (kind, name, scope) DO NOTHING")) {
                    st.setArray(1, ka);
                    st.setArray(2, na);
                    st.setArray(3, sa);
                    st.executeUpdate();
                }
                try (var st = con.prepareStatement(
                        "SELECT n.id, n.kind, n.name, n.scope FROM graph_node n JOIN unnest(?::text[], ?::text[], ?::text[]) AS k(kind, name, scope) ON n.kind = k.kind AND n.name = k.name AND n.scope = k.scope")) {
                    st.setArray(1, ka);
                    st.setArray(2, na);
                    st.setArray(3, sa);
                    try (var rs = st.executeQuery()) {
                        while (rs.next()) {
                            Key k = new Key(rs.getString(2), rs.getString(3), rs.getString(4));
                            cache.put(k, rs.getLong(1));
                            out.put(k, rs.getLong(1));
                        }
                    }
                }
                return null;
            }));
        }
        return out;
    }

    /** Commits on its own, for the same reason as {@link #ensure}: no member transaction ever holds a node row lock. */
    public void setMember(long nodeId, long memberId) {
        own.executeWithoutResult(s -> jdbc.update("UPDATE graph_node SET member_id = ? WHERE id = ?", memberId, nodeId));
    }

    public void clearMember(long memberId) {
        own.executeWithoutResult(s -> jdbc.update("UPDATE graph_node SET member_id = NULL WHERE member_id = ?", memberId));
    }

    public void clearCache() {
        cache.clear();
    }
}
