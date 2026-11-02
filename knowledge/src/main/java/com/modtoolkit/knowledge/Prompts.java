package com.modtoolkit.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Versioned templates from prompts/. The version is part of the cache key, so editing one means bumping it. */
public final class Prompts {
    public static final Map<String, String> VERSIONS = Map.of("summary", "summary.v1", "rule", "rule.v1", "migration_note", "migration_note.v1", "dictionary", "dictionary.v1",
            "job_narrative", "job_narrative.v1", "screen_flow", "screen_flow.v1", "link_match", "link_match.v1", "rule_coverage", "rule_coverage.v1");

    private static final Map<String, String> TEXT = new ConcurrentHashMap<>();

    private Prompts() {}

    public static String system() {
        return load("system");
    }

    public static String render(String kind, String unit, String context, String source) {
        return load(VERSIONS.get(kind)).replace("${schema}", ArtifactValidator.schemaText(kind)).replace("${unit}", unit).replace("${context}", context).replace("${source}", source);
    }

    private static String load(String name) {
        return TEXT.computeIfAbsent(name, n -> {
            try (InputStream in = Prompts.class.getResourceAsStream("/prompts/" + n + ".st")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException | NullPointerException e) {
                throw new IllegalStateException("missing prompt " + n, e);
            }
        });
    }
}
