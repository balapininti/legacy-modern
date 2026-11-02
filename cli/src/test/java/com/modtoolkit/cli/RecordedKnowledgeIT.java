package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Answers a real model gave on real CardDemo programs, replayed: no network, per the spec's "no live LLM in CI". Each
 * rule's citation is checked against the actual source file here, independently of the code under test.
 */
class RecordedKnowledgeIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path JAR = REPO.resolve("cli/target/toolkit.jar");
    static final Path CORPUS = REPO.resolve(".cache/corpora/carddemo");
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private record Run(int exit, String out) {}

    private static Run toolkit(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD);
        b.environment().putAll(Map.of("TOOLKIT_DB_URL", pg.getJdbcUrl(), "TOOLKIT_DB_USER", pg.getUsername(), "TOOLKIT_DB_PASSWORD", pg.getPassword()));
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        return new Run(p.waitFor(), out.strip());
    }

    @Test
    void realModelAnswersValidateAndEveryRuleCitesRealCode() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS.resolve("app/cbl")) && Files.exists(JAR));
        pg.start();
        assertThat(toolkit("discover", "--root", CORPUS.toString(), "--threads", "8").exit()).isZero();
        Path cfg = Files.createTempFile("k", ".yaml");
        Files.writeString(cfg, "client: x\nsource:\n  root: " + CORPUS + "\nllm:\n  provider: openai-compatible\n  chat_model: gpt-oss-32k:latest\n");
        String rec = REPO.resolve("testdata/llm-recordings").toString();
        String[] run = {"knowledge", "run", "-c", cfg.toString(), "--replay-from", rec, "--programs", "CBACT04C,COSGN00C,CBTRN02C,CVACT01Y,POSTTRAN,COSGN0A"};
        Run first = toolkit(run);
        assertThat(first.out()).contains("status=completed").contains("tokensIn=");
        ObjectMapper om = new ObjectMapper();
        int rules = 0;
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT a.content::text, m.path, p.line_start, p.line_end FROM knowledge_artifact a JOIN program p ON p.id=a.unit_id JOIN member m ON m.id=a.member_id WHERE a.kind='rule'")) {
            while (r.next()) {
                JsonNode doc = om.readTree(r.getString(1));
                List<String> lines = Files.readAllLines(CORPUS.resolve(r.getString(2)), java.nio.charset.StandardCharsets.ISO_8859_1);
                for (JsonNode rule : doc.get("rules")) {
                    int a = rule.get("line_start").asInt(), b = rule.get("line_end").asInt();
                    assertThat(a).as(rule.toString()).isBetween(r.getInt(3), r.getInt(4));
                    assertThat(b).as(rule.toString()).isBetween(a, lines.size());
                    rules++;
                }
            }
            try (ResultSet k = s.executeQuery("SELECT count(*) FROM knowledge_artifact")) {
                k.next();
                assertThat(k.getInt(1)).isGreaterThan(5);
            }
        }
        assertThat(rules).isGreaterThan(5);
        // Unchanged source: everything accepted comes from the cache. The two units the validator rejected stay
        // unanswered, so they are asked again, twice each, and nothing else is.
        assertThat(first.out()).contains("units=10").contains("failed=2");
        Run second = toolkit(run);
        assertThat(second.out()).contains("units=10, fromCache=10, llmCalls=4, failed=2");
        // With no rejected unit in the run, a rerun asks nothing at all. Each run is a new JVM on purpose: a context
        // built from Map.of hashed differently in each one, and this kept missing the cache about half the time.
        String[] clean = {"knowledge", "run", "-c", cfg.toString(), "--replay-from", rec, "--programs", "COSGN00C,POSTTRAN,COSGN0A"};
        for (int i = 0; i < 4; i++) {
            assertThat(toolkit(clean).out()).contains("units=5, fromCache=5, llmCalls=0, failed=0, tokensIn=0, tokensOut=0");
        }
    }
}
