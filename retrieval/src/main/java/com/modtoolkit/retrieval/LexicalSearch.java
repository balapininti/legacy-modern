package com.modtoolkit.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Whole identifiers through tsvector, partial ones through pg_trgm. */
@Service
public class LexicalSearch {
    private final JdbcTemplate jdbc;

    public LexicalSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchHit> search(String query, List<String> kinds, int limit) {
        String kindFilter = kinds == null || kinds.isEmpty() ? "" : " AND c.kind = ANY(?::text[])";
        List<Object> args = new ArrayList<>();
        args.add(query);
        args.add(query);
        args.add(escapeLike(query));
        String arr = kinds == null || kinds.isEmpty() ? null : "{" + String.join(",", kinds) + "}";
        if (arr != null) args.add(arr);
        args.add(limit);
        String sql = """
                SELECT c.kind, coalesce(m.path, c.metadata->>'path'), c.line_start, c.line_end, c.text, c.artifact_id, a.status, c.metadata->>'unit' AS unit,
                       ts_rank(c.tsv, cobol_tsquery(?)) * (CASE WHEN a.status = 'reviewed' THEN 2 WHEN a.status = 'rejected' THEN 0.1 ELSE 1 END) AS score
                FROM chunk c LEFT JOIN member m ON m.id = c.member_id LEFT JOIN knowledge_artifact a ON a.id = c.artifact_id
                WHERE (c.tsv @@ cobol_tsquery(?) OR c.text ILIKE '%' || ? || '%') AND coalesce(a.status, '') <> 'rejected'""" + kindFilter + """
                 ORDER BY score DESC, m.path, c.line_start LIMIT ?""";
        String needle = firstToken(query);
        return jdbc.query(sql, (rs, i) -> {
            String text = rs.getString(5);
            Integer start = (Integer) rs.getObject(3);
            return new SearchHit(rs.getString(1), rs.getString(2), start, (Integer) rs.getObject(4), snippet(text, needle), rs.getDouble(9), (Long) rs.getObject(6), rs.getString(7), rs.getString(8));
        }, args.toArray());
    }

    private static String firstToken(String q) {
        String[] t = q.trim().split("\\s+");
        String best = t[0];
        for (String s : t) if (s.length() > best.length()) best = s;
        return best.toLowerCase(Locale.ROOT);
    }

    private static String escapeLike(String s) {
        return s.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The matching line with a line either side, or the start of the unit. */
    static String snippet(String text, String needle) {
        String[] lines = text.split("\n", -1);
        int at = 0;
        for (int i = 0; i < lines.length && !needle.isEmpty(); i++) {
            if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                at = i;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, at - 1); i <= Math.min(lines.length - 1, at + 1); i++) {
            sb.append(lines[i].stripTrailing()).append('\n');
        }
        String s = sb.toString().stripTrailing();
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
