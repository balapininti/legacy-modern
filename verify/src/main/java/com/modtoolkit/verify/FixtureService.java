package com.modtoolkit.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns a capture directory into fixtures: one per run, each dataset decoded by its copybook's layout, raw bytes kept.
 * A manifest.json names each file's copybook, so a client's exported datasets work with no LPAR at all.
 */
@Service
public class FixtureService {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record ImportResult(long captureId, int fixtures, int records) {}

    private final JdbcTemplate jdbc;

    public FixtureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ImportResult importCapture(Path dir, String encoding) throws Exception {
        JsonNode m = JSON.readTree(dir.resolve("manifest.json").toFile());
        String program = m.path("program").asText();
        String source = m.path("source").asText("export");
        long capture = jdbc.queryForObject("INSERT INTO capture (subject_kind, subject_id, jcl, raw_path, status, source, inputs, outputs) VALUES ('program',?,?,?,?,?,?::jsonb,?::jsonb) RETURNING id", Long.class,
                program, m.path("jcl").asText(null), dir.toString(), "imported", source, m.path("inputs").toString(), m.path("outputs").toString());
        RecordCodec codec = new RecordCodec(encoding);
        int fixtures = 0, records = 0;
        List<Path> runs;
        try (Stream<Path> s = Files.list(dir)) {
            runs = s.filter(Files::isDirectory).sorted().toList();
        }
        for (Path run : runs) {
            Map<String, Object> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
            records += datasets(run, m, "inputs", ".input", codec, in);
            records += datasets(run, m, "outputs", ".output", codec, out);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("parm", m.path("parm").asText(null));
            input.put("datasets", in);
            jdbc.update("INSERT INTO fixture (capture_id, name, input_json, expected_json) VALUES (?,?,?::jsonb,?::jsonb)", capture, run.getFileName().toString(), JSON.writeValueAsString(input), JSON.writeValueAsString(out));
            fixtures++;
        }
        return new ImportResult(capture, fixtures, records);
    }

    private int datasets(Path run, JsonNode m, String listName, String suffix, RecordCodec codec, Map<String, Object> into) throws IOException {
        int n = 0;
        for (JsonNode dd : m.path(listName)) {
            String name = dd.asText();
            Path f = run.resolve(name + suffix);
            if (!Files.exists(f)) throw new IOException("capture is missing " + f);
            JsonNode rec = m.path("records").path(name);
            RecordLayout layout = RecordLayout.fromDatabase(jdbc, rec.path("copybook").asText(), rec.path("record").asText(null));
            int len = rec.path("length").asInt(layout.length());
            if (len != layout.length()) throw new IOException(name + ": the manifest says " + len + " bytes per record but the layout is " + layout.length());
            byte[] all = Files.readAllBytes(f);
            if (all.length % len != 0) throw new IOException(name + ": " + all.length + " bytes is not a whole number of " + len + " byte records");
            List<Object> rows = new ArrayList<>();
            for (int off = 0; off < all.length; off += len) rows.add(numbersAsStrings(codec.decode(Arrays.copyOfRange(all, off, off + len), layout)));
            into.put(name, rows);
            n += rows.size();
        }
        return n;
    }

    /** JSON numbers would lose scale and precision in somebody's parser, and scale is part of what is being tested. */
    @SuppressWarnings("unchecked")
    private static Object numbersAsStrings(Object v) {
        if (v instanceof java.math.BigDecimal d) return d.toPlainString();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, x) -> o.put((String) k, numbersAsStrings(x)));
            return o;
        }
        if (v instanceof List<?> l) return l.stream().map(FixtureService::numbersAsStrings).toList();
        return v;
    }
}
