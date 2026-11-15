package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.testdb.TestPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The packaged toolkit on a PostgreSQL without pgvector: the migrations (V5 is a Java migration, so this also proves it
 * is found inside the boot jar) succeed, indexing skips embeddings without calling the endpoint, search is exactly the
 * lexical ranking even with an embedding model configured, status and doctor say vector search is off, and reindex
 * refuses clearly. The embedding endpoint is a closed port: any attempt to use it would show.
 */
class NoVectorIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    record Run(int exit, String out, String err) {}

    private static Run toolkit(TestPostgres.Database pg, Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", REPO.resolve("cli/target/toolkit.jar").toString()));
        cmd.addAll(List.of(args));
        Path err = Files.createTempFile("novector", ".err");
        ProcessBuilder b = new ProcessBuilder(cmd).redirectError(err.toFile()).directory(cwd.toFile());
        b.environment().putAll(Map.of("TOOLKIT_DB_URL", pg.getJdbcUrl(), "TOOLKIT_DB_USER", pg.getUsername(), "TOOLKIT_DB_PASSWORD", pg.getPassword()));
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        return new Run(exit, out, Files.readString(err));
    }

    private static String ok(Run r) {
        assertThat(r.exit()).as(r.out() + r.err()).isZero();
        return r.out();
    }

    @Test
    void theToolkitWorksLexicalOnlyWithoutPgvector() throws Exception {
        Path corpus = REPO.resolve(".cache/corpora/carddemo");
        assumeTrue(Files.isDirectory(corpus.resolve("app/cbl")));
        TestPostgres.Database pg = TestPostgres.newDatabaseWithoutVector();
        Path cwd = Files.createTempDirectory("novector");
        Files.writeString(cwd.resolve("toolkit.yaml"), "client: c\nsource:\n  root: " + corpus + "\nllm:\n  provider: openai-compatible\n"
                + "  endpoint: http://127.0.0.1:1/v1\n  embedding_model: never-called\n  embedding_dim: 8\n");
        Path plain = cwd.resolve("plain.yaml");
        Files.writeString(plain, "client: c\nsource:\n  root: " + corpus + "\n");

        ok(toolkit(pg, cwd, "discover", "--root", corpus.toString(), "--threads", "8"));
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                ResultSet r = c.createStatement().executeQuery("SELECT type, success FROM flyway_schema_history WHERE version = '5'")) {
            assertThat(r.next()).as("V5 found inside the boot jar").isTrue();
            assertThat(r.getString(1)).isEqualTo("JDBC");
            assertThat(r.getBoolean(2)).isTrue();
        }
        String index = ok(toolkit(pg, cwd, "index"));
        assertThat(index).contains("embedded=0");

        ObjectMapper om = new ObjectMapper();
        JsonNode status = om.readTree(ok(toolkit(pg, cwd, "status")));
        assertThat(status.at("/chunks/total").asLong()).isPositive();
        assertThat(status.at("/chunks/embeddings").asText()).startsWith("unavailable");

        // With an embedding model configured, the ranking is still exactly the lexical one.
        Path questions = REPO.resolve("testdata/eval/carddemo-questions.json");
        Path lexOut = cwd.resolve("lex.json");
        Path cfgOut = cwd.resolve("cfg.json");
        JsonNode lex = om.readTree(ok(toolkit(pg, cwd, "eval", "--questions", questions.toString(), "--lexical-only", "--out", lexOut.toString())));
        JsonNode cfg = om.readTree(ok(toolkit(pg, cwd, "eval", "--questions", questions.toString(), "--out", cfgOut.toString())));
        assertThat(lex.get("recall_at_5").asDouble()).isGreaterThanOrEqualTo(0.5);
        assertThat(cfg.get("mode").asText()).startsWith("lexical").contains("pgvector");
        assertThat(om.readTree(cfgOut.toFile()).get("results")).isEqualTo(om.readTree(lexOut.toFile()).get("results"));
        JsonNode q01 = om.readTree(lexOut.toFile()).get("results").get(0);
        assertThat(q01.get("id").asText()).isEqualTo("Q01");
        assertThat(q01.get("rank").asInt()).as("interest calculation is found in CBACT04C").isBetween(1, 5);

        Run reindex = toolkit(pg, cwd, "reindex");
        assertThat(reindex.exit()).isNotZero();
        assertThat(reindex.err()).contains("pgvector").contains("not available");

        Run doctor = toolkit(pg, cwd, "doctor", "-c", plain.toString());
        assertThat(doctor.exit()).as(doctor.out()).isZero();
        assertThat(doctor.out()).containsPattern("(?m)^WARN\\s+vector\\s+.*lexical");
    }
}
