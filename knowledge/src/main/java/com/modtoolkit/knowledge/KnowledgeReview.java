package com.modtoolkit.knowledge;

import com.modtoolkit.core.migration.MigrationService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** SME review. Status changes feed retrieval through the chunk rows that share the artifact's id. */
@Service
public class KnowledgeReview {
    private final JdbcTemplate jdbc;

    public KnowledgeReview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> queue(String status, String kind, int limit, int offset) {
        return jdbc.queryForList("""
                SELECT a.id, a.unit_kind, a.kind, a.status, a.reviewer, a.model, a.prompt_version, a.content::text AS content, m.path AS member, a.line_start, a.line_end, a.created_at::text AS created_at
                FROM knowledge_artifact a LEFT JOIN member m ON m.id = a.member_id
                WHERE (?::text IS NULL OR a.status = ?) AND (?::text IS NULL OR a.kind = ?) ORDER BY a.id LIMIT ? OFFSET ?""", status, status, kind, kind, limit, offset);
    }

    public void setStatus(long id, String status, String reviewer) {
        if (!List.of("draft", "reviewed", "rejected").contains(status)) throw new IllegalArgumentException("status must be draft, reviewed or rejected");
        if (jdbc.update("UPDATE knowledge_artifact SET status = ?, reviewer = ? WHERE id = ?", status, reviewer, id) == 0) throw new MigrationService.NotFound("no artifact " + id);
    }

    /** An SME's correction replaces the content and is reviewed by definition. */
    public void edit(long id, String contentJson, String reviewer) {
        if (jdbc.update("UPDATE knowledge_artifact SET content = ?::jsonb, status = 'reviewed', reviewer = ? WHERE id = ?", contentJson, reviewer, id) == 0) throw new MigrationService.NotFound("no artifact " + id);
        // Its search documents are rebuilt by the next index run, from the corrected words.
        jdbc.update("DELETE FROM chunk WHERE artifact_id = ?", id);
    }
}
