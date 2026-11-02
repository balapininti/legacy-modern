package com.modtoolkit.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Embeds what has no vector from the current model: summaries, rules and dictionary entries first since they pay most,
 * code second, and copybooks never line by line (their fields are searched through the dictionary). Changing the
 * model re-embeds everything, which is what the reindex job is.
 */
@Service
public class EmbeddingIndexer {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);
    private static final int BATCH = 32;
    /** Models fail or truncate past their window; a very long paragraph is represented by its start. */
    private static final int MAX_CHARS = 6000;

    public record Stats(int embedded, int skipped) {}

    private final JdbcTemplate jdbc;

    public EmbeddingIndexer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Stats embedPending(Embedder e) {
        int done = 0;
        for (String priority : List.of("c.kind IN ('summary','rule','dictionary','job','map')", "c.kind IN ('code','modern') AND coalesce(c.metadata->>'unit','') <> 'copybook'")) {
            while (true) {
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.id, c.text FROM chunk c WHERE (c.embedding IS NULL OR c.embedding_model IS DISTINCT FROM ?) AND " + priority + " ORDER BY c.id LIMIT ?", e.model(), BATCH);
                if (rows.isEmpty()) break;
                List<String> texts = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    String t = (String) r.get("text");
                    texts.add(t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t);
                }
                List<float[]> vectors = e.embed(texts);
                if (vectors.size() != rows.size()) throw new IllegalStateException("the embedding endpoint returned " + vectors.size() + " vectors for " + rows.size() + " texts");
                for (int i = 0; i < rows.size(); i++) {
                    float[] v = vectors.get(i);
                    if (v.length != e.dimension()) throw new IllegalStateException("model " + e.model() + " returned dimension " + v.length + ", but llm.embedding_dim is " + e.dimension());
                    jdbc.update("UPDATE chunk SET embedding = ?::vector, embedding_model = ? WHERE id = ?", literal(v), e.model(), rows.get(i).get("id"));
                }
                done += rows.size();
                if (done % 320 == 0) log.info("embedded {} chunks", done);
            }
        }
        ensureIndex(e.dimension());
        log.info("embedded {} chunks with {}", done, e.model());
        return new Stats(done, 0);
    }

    /** A plain vector HNSW stops at 2000 dimensions, so the index is on a halfvec cast, which goes to 4000. */
    public void ensureIndex(int dim) {
        if (dim < 1 || dim > 4000) throw new IllegalArgumentException("embedding dimension must be 1 to 4000");
        // Rows from other dimensions would fail the cast, which only happens mid-reindex; they are filtered in the index.
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunk_embedding_hnsw_" + dim + " ON chunk USING hnsw ((embedding::halfvec(" + dim + ")) halfvec_cosine_ops) WHERE vector_dims(embedding) = " + dim);
    }

    /** Forces every vector to be rebuilt. */
    public void clear() {
        jdbc.update("UPDATE chunk SET embedding = NULL, embedding_model = NULL");
    }

    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
