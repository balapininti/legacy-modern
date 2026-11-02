package com.modtoolkit.retrieval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds code chunks by structure, never by token count: a paragraph, the data division, a copybook, a JCL step, a map.
 * A member is re-chunked only when its hash differs from the one its chunks recorded.
 */
@Service
public class ChunkIndexer {
    private static final int WHOLE_JOB_LINES = 150;
    private static final Logger log = LoggerFactory.getLogger(ChunkIndexer.class);
    private static final int MEMBER_LEVEL_LINE_CAP = 4000;

    public record Stats(int members, int chunks) {}

    private record Chunk(long memberId, String kind, int start, int end, String text, String unit, String hash) {}

    private final JdbcTemplate jdbc;

    public ChunkIndexer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Stats index() {
        jdbc.update("DELETE FROM chunk c WHERE c.member_id IS NOT NULL AND c.artifact_id IS NULL AND NOT EXISTS (SELECT 1 FROM member m WHERE m.id = c.member_id AND m.content_hash = c.metadata->>'hash')");
        List<Map<String, Object>> members = jdbc.queryForList("""
                SELECT m.id, m.path, m.type, m.content_hash FROM member m
                WHERE m.normalized_text IS NOT NULL AND m.classify_reason <> 'empty' AND m.type <> 'unknown'
                  AND NOT EXISTS (SELECT 1 FROM chunk c WHERE c.member_id = m.id AND c.artifact_id IS NULL)""");
        int chunks = 0;
        for (Map<String, Object> m : members) {
            long id = ((Number) m.get("id")).longValue();
            String text = jdbc.queryForObject("SELECT normalized_text FROM member WHERE id = ?", String.class, id);
            List<String> lines = Arrays.asList(text.split("\n", -1));
            List<Chunk> list = chunksFor(id, (String) m.get("type"), (String) m.get("content_hash"), lines);
            write(list);
            chunks += list.size();
        }
        log.info("index: {} members, {} chunks", members.size(), chunks);
        return new Stats(members.size(), chunks);
    }

    private List<Chunk> chunksFor(long id, String type, String hash, List<String> lines) {
        List<Chunk> out = new ArrayList<>();
        switch (type) {
            case "cobol" -> {
                List<Map<String, Object>> paras = jdbc.queryForList("""
                        SELECT q.name, q.line_start, q.line_end, p.program_id FROM paragraph q JOIN program p ON p.id = q.program_id
                        WHERE p.member_id = ? AND q.member_id = ? ORDER BY q.line_start""", id, id);
                int first = paras.isEmpty() ? lines.size() + 1 : ((Number) paras.get(0).get("line_start")).intValue();
                dataChunks(id, hash, lines, first, out);
                for (Map<String, Object> q : paras) {
                    out.add(chunk(id, "code", ((Number) q.get("line_start")).intValue(), ((Number) q.get("line_end")).intValue(), lines, q.get("program_id") + "." + q.get("name"), hash));
                }
            }
            case "jcl", "proc" -> {
                List<Map<String, Object>> steps = jdbc.queryForList("""
                        SELECT s.name, s.line, j.name AS job FROM jcl_step s JOIN jcl_job j ON j.id = s.job_id
                        WHERE j.member_id = ? AND s.line IS NOT NULL AND (s.source_member_id IS NULL OR s.source_member_id = ?) ORDER BY s.line""", id, id);
                // Most jobs read as a unit: what the job is for sits in comments above the steps it describes.
                if (steps.isEmpty() || lines.size() <= WHOLE_JOB_LINES) {
                    out.add(chunk(id, "job", 1, lines.size(), lines, steps.isEmpty() ? type : String.valueOf(steps.get(0).get("job")), hash));
                } else {
                    int[] starts = new int[steps.size()];
                    for (int i = 0; i < starts.length; i++) {
                        int s = ((Number) steps.get(i).get("line")).intValue();
                        // A step's comments belong to it.
                        while (s > 1 && (lines.get(s - 2).startsWith("//*") || lines.get(s - 2).isBlank())) s--;
                        starts[i] = s;
                    }
                    if (starts[0] > 1) out.add(chunk(id, "job", 1, starts[0] - 1, lines, "job card", hash));
                    for (int i = 0; i < starts.length; i++) {
                        int e = i + 1 < starts.length ? starts[i + 1] - 1 : lines.size();
                        if (e >= starts[i]) out.add(chunk(id, "job", starts[i], e, lines, steps.get(i).get("job") + "." + steps.get(i).get("name"), hash));
                    }
                }
            }
            case "bms" -> {
                List<Map<String, Object>> maps = jdbc.queryForList("SELECT name, line FROM bms_map WHERE member_id = ? AND line IS NOT NULL ORDER BY line", id);
                if (maps.isEmpty()) {
                    out.add(chunk(id, "map", 1, lines.size(), lines, "mapset", hash));
                } else {
                    for (int i = 0; i < maps.size(); i++) {
                        int s = ((Number) maps.get(i).get("line")).intValue();
                        int e = i + 1 < maps.size() ? ((Number) maps.get(i + 1).get("line")).intValue() - 1 : lines.size();
                        out.add(chunk(id, "map", s, e, lines, String.valueOf(maps.get(i).get("name")), hash));
                    }
                }
            }
            default -> {
                // Copybooks, DDL and member-level languages (PL/I, Assembler...) are one structural unit each.
                int end = Math.min(lines.size(), MEMBER_LEVEL_LINE_CAP);
                out.add(chunk(id, "code", 1, end, lines, type, hash));
            }
        }
        return out;
    }

    /**
     * The part before the first paragraph, one chunk per record. One chunk for a whole data division matches every
     * search and tells the agent nothing about where to look.
     */
    private void dataChunks(long id, String hash, List<String> lines, int first, List<Chunk> out) {
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT d.name, d.line FROM data_item d JOIN program p ON p.id = d.program_id
                WHERE p.member_id = ? AND d.owner_member_id = ? AND d.level IN (1, 77) AND d.line < ? ORDER BY d.line""", id, id, first);
        int start = 1;
        String unit = "header";
        for (Map<String, Object> r : records) {
            int at = ((Number) r.get("line")).intValue();
            if (at > start) out.add(chunk(id, "code", start, at - 1, lines, unit, hash));
            start = at;
            unit = String.valueOf(r.get("name"));
        }
        if (first > start) out.add(chunk(id, "code", start, first - 1, lines, unit, hash));
    }

    private static Chunk chunk(long id, String kind, int start, int end, List<String> lines, String unit, String hash) {
        int s = Math.max(1, start), e = Math.max(s, Math.min(end, lines.size()));
        return new Chunk(id, kind, s, e, String.join("\n", lines.subList(s - 1, e)).replace("\u0000", ""), unit, hash);
    }

    private void write(List<Chunk> list) {
        jdbc.batchUpdate("INSERT INTO chunk (member_id, kind, line_start, line_end, text, tsv, metadata) VALUES (?,?,?,?,?, cobol_tsvector(?), jsonb_build_object('unit', ?::text, 'hash', ?::text))", list, 200, (ps, c) -> {
            ps.setLong(1, c.memberId());
            ps.setString(2, c.kind());
            ps.setInt(3, c.start());
            ps.setInt(4, c.end());
            ps.setString(5, c.text());
            ps.setString(6, c.text());
            ps.setString(7, c.unit());
            ps.setString(8, c.hash());
        });
    }
}
