package com.modtoolkit.discovery;

import com.modtoolkit.parsers.classify.Classification;
import com.modtoolkit.parsers.classify.Classifier;
import com.modtoolkit.parsers.classify.MemberType;
import com.modtoolkit.parsers.normalize.NormalizedMember;
import com.modtoolkit.parsers.normalize.Normalizer;
import com.modtoolkit.parsers.normalize.SourceOptions;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/** Walks the source root; a member is pending only when its bytes changed, or a copybook it uses changed. */
public final class InventoryScanner {
    public record Counts(int total, int changed, int removed) {}

    private final JdbcTemplate jdbc;

    public InventoryScanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Counts scan(DiscoverOptions o) throws IOException {
        Map<String, String> known = new HashMap<>();
        jdbc.query("SELECT path, content_hash FROM member", rs -> { known.put(rs.getString(1), rs.getString(2)); });
        List<PathMatcher> inc = matchers(o.include()), exc = matchers(o.exclude());
        SourceOptions so = new SourceOptions(o.encoding(), o.format());
        Set<String> seen = new HashSet<>();
        List<String> changedCopybooks = new ArrayList<>();
        int total = 0, changed = 0;
        List<Path> files;
        try (Stream<Path> s = Files.walk(o.root())) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path f : files) {
            Path rel = o.root().relativize(f);
            if (exc.stream().anyMatch(m -> m.matches(rel)) || (!inc.isEmpty() && inc.stream().noneMatch(m -> m.matches(rel) || rootLevel(m, rel)))) continue;
            String path = rel.toString();
            seen.add(path);
            total++;
            byte[] bytes = Files.readAllBytes(f);
            String hash = Normalizer.sha256(bytes);
            if (hash.equals(known.get(path))) continue;
            changed++;
            NormalizedMember n = Normalizer.normalize(bytes, so);
            Classification c = Classifier.classify(n);
            String name = memberName(f);
            String text = n.binary() ? null : String.join("\n", n.lines());
            if (text != null) text = text.replace("\u0000", "");
            jdbc.update("""
                    INSERT INTO member (path, name, type, dialect, encoding, format, content_hash, loc, parse_status, parse_error, classify_reason, normalized_text, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,NULL,?,?,now())
                    ON CONFLICT (path) DO UPDATE SET name=EXCLUDED.name, type=EXCLUDED.type, dialect=EXCLUDED.dialect, encoding=EXCLUDED.encoding, format=EXCLUDED.format,
                      content_hash=EXCLUDED.content_hash, loc=EXCLUDED.loc, parse_status=EXCLUDED.parse_status, parse_error=NULL, classify_reason=EXCLUDED.classify_reason,
                      normalized_text=EXCLUDED.normalized_text, updated_at=now()
                    """, path, name, c.type().db(), c.dialect(), n.encoding(), n.format().name().toLowerCase(), hash, n.lines().size(),
                    c.type().parsed() ? "pending" : "inventoried", c.reason(), text);
            if (c.type() == MemberType.COPYBOOK || known.containsKey(path)) changedCopybooks.add(name.toUpperCase());
        }
        int removed = 0;
        for (String p : known.keySet()) {
            if (!seen.contains(p)) {
                String name = jdbc.queryForObject("SELECT upper(name) FROM member WHERE path = ?", String.class, p);
                changedCopybooks.add(name);
                jdbc.update("DELETE FROM member WHERE path = ?", p);
                removed++;
            }
        }
        // Everything that COPYs a changed copybook must be parsed again, directly or through a nested COPY.
        if (!changedCopybooks.isEmpty()) {
            for (String cb : changedCopybooks) {
                jdbc.update("UPDATE member SET parse_status='pending' WHERE parse_status <> 'pending' AND id IN (SELECT member_id FROM copy_use WHERE upper(copybook_name) = ?)", cb);
                // Likewise a job that expanded a changed PROC.
                jdbc.update("UPDATE member SET parse_status='pending' WHERE parse_status <> 'pending' AND id IN (SELECT j.member_id FROM jcl_job j JOIN jcl_step s ON s.job_id = j.id WHERE upper(s.proc) = ?)", cb);
            }
        }
        return new Counts(total, changed, removed);
    }

    private static boolean rootLevel(PathMatcher m, Path rel) {
        return rel.getNameCount() == 1 && m.matches(Path.of("x").resolve(rel));
    }

    private static List<PathMatcher> matchers(List<String> globs) {
        List<PathMatcher> out = new ArrayList<>();
        if (globs != null) for (String g : globs) out.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        return out;
    }

    /** The name COPY and PGM= will use: the file name without an extension. */
    static String memberName(Path f) {
        String n = f.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
