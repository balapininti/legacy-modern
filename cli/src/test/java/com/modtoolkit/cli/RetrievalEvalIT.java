package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A floor for what CI can measure with no model at all: lexical search over parsed code alone. The hybrid figures need
 * an embedding model and are in docs/evidence/M5.md; this catches ranking changes that break the lexical half.
 */
class RetrievalEvalIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static String toolkit(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", REPO.resolve("cli/target/toolkit.jar").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).directory(Files.createTempDirectory("eval").toFile());
        b.environment().putAll(Map.of("TOOLKIT_DB_URL", pg.getJdbcUrl(), "TOOLKIT_DB_USER", pg.getUsername(), "TOOLKIT_DB_PASSWORD", pg.getPassword()));
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
        return out;
    }

    @Test
    void lexicalSearchHoldsItsFloorOnBothQuestionSets() throws Exception {
        Path corpus = REPO.resolve(".cache/corpora/carddemo");
        assumeTrue(Files.isDirectory(corpus.resolve("app/cbl")));
        pg.start();
        toolkit("discover", "--root", corpus.toString(), "--threads", "8");
        toolkit("index");
        ObjectMapper om = new ObjectMapper();
        Map<String, Double> floors = Map.of("carddemo-questions.json", 0.5, "carddemo-holdout.json", 0.6);
        for (var f : floors.entrySet()) {
            Path out = Files.createTempFile("eval", ".json");
            JsonNode r = om.readTree(toolkit("eval", "--questions", REPO.resolve("testdata/eval").resolve(f.getKey()).toString(), "--lexical-only", "--out", out.toString()));
            System.out.println(f.getKey() + " " + r);
            assertThat(r.get("recall_at_5").asDouble()).as(f.getKey()).isGreaterThanOrEqualTo(f.getValue());
        }
    }
}
