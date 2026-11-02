package com.modtoolkit.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Replays answers from files, for CI: keyed by unit and artifact kind, so nothing leaves the machine. */
public final class RecordedLlm implements LlmClient {
    public static final class Missing extends RuntimeException {
        public Missing(String k) {
            super("no recording for " + k);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path dir;
    private final String model;
    public final AtomicLong calls = new AtomicLong();

    public RecordedLlm(Path dir, String model) {
        this.dir = dir;
        this.model = model;
    }

    @Override
    public Response complete(String unitKey, String system, String user) {
        calls.incrementAndGet();
        Path f = dir.resolve(fileName(unitKey));
        if (!Files.exists(f)) throw new Missing(unitKey);
        try {
            JsonNode n = JSON.readTree(f.toFile());
            return new Response(n.get("text").asText(), n.get("tokens_in").asLong(), n.get("tokens_out").asLong());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String model() {
        return model;
    }

    static String fileName(String unitKey) {
        return unitKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    /** Wraps a real client and saves every answer, which is how fixtures get made. The prompt is never saved. */
    public static LlmClient recording(LlmClient real, Path dir) {
        return new LlmClient() {
            @Override
            public Response complete(String unitKey, String system, String user) {
                Response r = real.complete(unitKey, system, user);
                try {
                    Files.createDirectories(dir);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("model", real.model());
                    m.put("tokens_in", r.tokensIn());
                    m.put("tokens_out", r.tokensOut());
                    m.put("text", r.text());
                    JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(fileName(unitKey)).toFile(), m);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return r;
            }

            @Override
            public String model() {
                return real.model();
            }
        };
    }
}
