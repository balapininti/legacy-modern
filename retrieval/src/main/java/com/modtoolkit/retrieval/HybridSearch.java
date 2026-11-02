package com.modtoolkit.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reciprocal rank fusion of lexical and semantic hits, a boost for reviewed artifacts, rejected ones out, and one hit
 * per place in the source. Without an embedding endpoint it is exactly lexical search, and says so.
 */
@Service
public class HybridSearch {
    private static final Logger log = LoggerFactory.getLogger(HybridSearch.class);
    private static final int RRF_K = 60;
    private static final int POOL = 50;
    private static final double REVIEWED_BOOST = 1.5;

    public record Result(List<SearchHit> hits, String mode) {}

    private final JdbcTemplate jdbc;
    private final LexicalSearch lexical;

    public HybridSearch(JdbcTemplate jdbc, LexicalSearch lexical) {
        this.jdbc = jdbc;
        this.lexical = lexical;
    }

    public Result search(String query, List<String> kinds, int limit, Embedder embedder) {
        List<SearchHit> lex = lexical.search(query, kinds, POOL);
        List<SearchHit> sem = List.of();
        String mode = "lexical";
        if (embedder != null && hasVectors(embedder.model())) {
            try {
                sem = semantic(embedder.embed(List.of(embedder.asQuery(query))).get(0), embedder, kinds);
                mode = "hybrid";
            } catch (RuntimeException e) {
                // The search box must still work while the embedding endpoint is down.
                log.warn("semantic search unavailable, falling back to lexical: {}", e.getMessage());
                mode = "lexical (embedding endpoint unavailable)";
            }
        }
        return new Result(fuse(lex, sem, limit, query), mode);
    }

    private boolean hasVectors(String model) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM chunk WHERE embedding_model = ?)", Boolean.class, model));
    }

    private List<SearchHit> semantic(float[] q, Embedder e, List<String> kinds) {
        int dim = e.dimension();
        String filter = kinds == null || kinds.isEmpty() ? "" : " AND c.kind = ANY(?::text[])";
        String v = EmbeddingIndexer.literal(q);
        List<Object> args = new ArrayList<>();
        args.add(v);
        args.add(e.model());
        if (!filter.isEmpty()) args.add("{" + String.join(",", kinds) + "}");
        args.add(v);
        args.add(POOL);
        String sql = """
                SELECT c.kind, coalesce(m.path, c.metadata->>'path'), c.line_start, c.line_end, c.text, c.artifact_id, a.status, c.metadata->>'unit',
                       1 - (c.embedding::halfvec(%1$d) <=> ?::halfvec(%1$d)) AS score
                FROM chunk c LEFT JOIN member m ON m.id = c.member_id LEFT JOIN knowledge_artifact a ON a.id = c.artifact_id
                WHERE vector_dims(c.embedding) = %1$d AND c.embedding_model = ? AND coalesce(a.status, '') <> 'rejected'%2$s
                ORDER BY c.embedding::halfvec(%1$d) <=> ?::halfvec(%1$d) LIMIT ?""".formatted(dim, filter);
        return jdbc.query(sql, (rs, i) -> new SearchHit(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3), (Integer) rs.getObject(4),
                LexicalSearch.snippet(rs.getString(5), ""), rs.getDouble(9), (Long) rs.getObject(6), rs.getString(7), rs.getString(8)), args.toArray());
    }

    static List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> semantic, int limit) {
        return fuse(lexical, semantic, limit, "");
    }

    /** What a question says it wants: a job, a screen, a table definition. Each word points at a kind of source. */
    private static final Pattern ASKS_JOB = Pattern.compile("(?i)\\b(jobs?|jcl|dataset|gdg|proc|step|vsam|idcams)\\b");
    private static final Pattern ASKS_SCREEN = Pattern.compile("(?i)\\b(screens?|maps?|mapset|bms)\\b");
    private static final Pattern ASKS_DEFINITION = Pattern.compile("(?i)\\b(ddl|tables?|schema|foreign key|dbd|psb|hierarchy|layout|copybooks?)\\b");
    private static final Pattern NAMES_IDENTIFIER = Pattern.compile("[A-Z0-9]+-[A-Z0-9-]+");

    static double intentBoost(SearchHit h, String query) {
        String member = h.member() == null ? "" : h.member().toLowerCase(java.util.Locale.ROOT);
        boolean jcl = "job".equals(h.kind()) || member.matches(".*\\.(jcl|prc|proc)$");
        boolean map = "map".equals(h.kind()) || member.endsWith(".bms");
        boolean def = member.matches(".*\\.(ddl|dbd|psb|cpy|dcl)$") || "dictionary".equals(h.kind());
        double b = 1;
        if (jcl && ASKS_JOB.matcher(query).find()) b *= 1.8;
        if (map && ASKS_SCREEN.matcher(query).find()) b *= 1.8;
        if (def && ASKS_DEFINITION.matcher(query).find()) b *= 1.8;
        return b;
    }

    static List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> semantic, int limit, String query) {
        Map<String, Double> score = new HashMap<>();
        Map<String, SearchHit> best = new LinkedHashMap<>();
        // Someone naming an exact identifier wants that identifier, which only the lexical side can promise.
        double semanticWeight = NAMES_IDENTIFIER.matcher(query).find() ? 0.5 : 1.0;
        for (List<SearchHit> list : List.of(lexical, semantic)) {
            double weight = list == semantic ? semanticWeight : 1.0;
            for (int rank = 0; rank < list.size(); rank++) {
                SearchHit h = list.get(rank);
                if ("rejected".equals(h.status())) continue;
                String key = key(h);
                score.merge(key, weight * intentBoost(h, query) / (RRF_K + rank + 1), Double::sum);
                // A reviewed artifact stands for its place over an unreviewed hit on the same lines.
                SearchHit prior = best.get(key);
                if (prior == null || ("reviewed".equals(h.status()) && !"reviewed".equals(prior.status()))) best.put(key, h);
            }
        }
        List<SearchHit> out = new ArrayList<>();
        for (Map.Entry<String, SearchHit> e : best.entrySet()) {
            SearchHit h = e.getValue();
            double s = score.get(e.getKey()) * ("reviewed".equals(h.status()) ? REVIEWED_BOOST : 1.0);
            out.add(new SearchHit(h.kind(), h.member(), h.lineStart(), h.lineEnd(), h.snippet(), Math.round(s * 1e6) / 1e6, h.artifactId(), h.status(), h.unit()));
        }
        out.sort(Comparator.comparingDouble(SearchHit::score).reversed().thenComparing(h -> String.valueOf(h.member())).thenComparing(h -> h.lineStart() == null ? 0 : h.lineStart()));
        return dedupe(out, limit);
    }

    /** One hit per place: lines that overlap in one member are the same answer, and the better-ranked one stays. */
    private static List<SearchHit> dedupe(List<SearchHit> ranked, int limit) {
        List<SearchHit> out = new ArrayList<>();
        for (SearchHit h : ranked) {
            boolean dup = false;
            for (SearchHit k : out) {
                if (h.member() != null && h.member().equals(k.member()) && h.lineStart() != null && k.lineStart() != null
                        && h.lineStart() <= k.lineEnd() && k.lineStart() <= h.lineEnd() && h.artifactId() == null) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(h);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static String key(SearchHit h) {
        return h.kind() + "|" + h.member() + "|" + h.lineStart() + "|" + h.lineEnd() + "|" + h.artifactId() + "|" + h.unit();
    }
}
