package com.modtoolkit.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The M1 acceptance: real CardDemo, pinned commit. Fetch it with scripts/fetch-corpora.sh. */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.datasource.hikari.maximum-pool-size=24", "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '30s'"})
class CardDemoCorpusIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path CORPUS = REPO.resolve(".cache/corpora/carddemo");
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        pg.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired DiscoveryService discovery;
    @Autowired DiscoveryReports reports;
    @Autowired JdbcTemplate jdbc;
    static boolean ran;

    @BeforeAll
    static void present() {
        assumeTrue(Files.isDirectory(CORPUS.resolve("app/cbl")), "run scripts/fetch-corpora.sh first");
    }

    private void run() throws Exception {
        if (!ran) {
            discovery.run(DiscoverOptions.of(CORPUS).withThreads(8));
            ran = true;
        }
    }

    private long n(String sql, Object... a) {
        return jdbc.queryForObject(sql, Long.class, a);
    }

    @Test
    void everyCobolProgramAndCopybookParses() throws Exception {
        run();
        assertThat(reports.parseFailures()).isEmpty();
        assertThat(n("SELECT count(*) FROM member WHERE type IN ('cobol','copybook') AND parse_status <> 'parsed'")).isZero();
    }

    @Test
    void eachFileWithCobolExtensionIsClassifiedAsCobol() throws Exception {
        run();
        assertThat(n("SELECT count(*) FROM member WHERE lower(path) ~ '\\.(cbl|cob)$' AND type <> 'cobol'")).isZero();
        assertThat(jdbc.queryForList("SELECT path FROM member WHERE lower(path) ~ '\\.cpy$' AND type <> 'copybook' AND classify_reason <> 'empty'", String.class)).isEmpty();
    }

    @Test
    void staticCallResolutionMeetsTheTarget() throws Exception {
        run();
        assertThat((Double) reports.staticCallResolution().get("resolved_pct")).isGreaterThanOrEqualTo(95.0);
    }

    @Test
    void handCheckedEdgesMatch() throws Exception {
        run();
        JsonNode hand = new ObjectMapper().readTree(REPO.resolve("testdata/hand-checked/carddemo-edges.json").toFile());
        for (JsonNode c : hand.get("calls")) {
            assertThat(n("""
                    SELECT count(*) FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id JOIN member m ON m.id=e.member_id
                    WHERE e.type='CALLS' AND m.path=? AND e.line=? AND s.name=? AND d.name=? AND e.attrs->>'mode'=?""",
                    c.get("member").asText(), c.get("line").asInt(), c.get("source").asText(), c.get("target").asText(), c.get("mode").asText())).as(c.toString()).isEqualTo(1);
        }
        for (JsonNode s : hand.get("sql")) {
            // text_line is the physical line of the verb; the fact's range must contain it.
            assertThat(n("""
                    SELECT count(*) FROM sql_stmt q JOIN member m ON m.id=q.member_id
                    WHERE m.path=? AND q.stmt_type=? AND q.tables ?? ? AND q.line <= ? AND q.line_end >= ?""",
                    s.get("member").asText(), s.get("type").asText(), s.get("table").asText(), s.get("text_line").asInt(), s.get("text_line").asInt())).as(s.toString()).isEqualTo(1);
        }
        for (JsonNode c : hand.get("cics")) {
            assertThat(n("SELECT count(*) FROM cics_cmd q JOIN member m ON m.id=q.member_id WHERE m.path=? AND q.line=? AND q.command=?",
                    c.get("member").asText(), c.get("line").asInt(), c.get("command").asText())).as(c.toString()).isGreaterThanOrEqualTo(1);
            if (c.has("unresolved_has_candidate")) {
                assertThat(n("""
                        SELECT count(*) FROM unresolved_call u JOIN member m ON m.id=u.member_id
                        WHERE m.path=? AND u.line=? AND u.target_expr=? AND u.candidates ?? ?""",
                        c.get("member").asText(), c.get("line").asInt(), c.get("expr").asText(), c.get("unresolved_has_candidate").asText())).as(c.toString()).isEqualTo(1);
            }
        }
        for (JsonNode f : hand.get("features")) {
            assertThat(n("SELECT count(*) FROM dialect_feature d JOIN member m ON m.id=d.member_id WHERE m.path=? AND d.feature=?", f.get("member").asText(), f.get("feature").asText())).as(f.toString()).isEqualTo(1);
        }
    }

    @Test
    void jobsMapsTransactionsAndFieldFlowMatchHandChecks() throws Exception {
        run();
        JsonNode hand = new ObjectMapper().readTree(REPO.resolve("testdata/hand-checked/carddemo-edges.json").toFile());
        assertThat(n("SELECT count(*) FROM member WHERE parse_status IN ('pending','failed')")).isZero();
        for (JsonNode j : hand.get("jobs")) {
            assertThat(n("""
                    SELECT count(*) FROM jcl_step s JOIN jcl_job j ON j.id=s.job_id JOIN member m ON m.id=s.source_member_id
                    WHERE j.name=? AND s.name=? AND s.pgm=? AND s.line=? AND m.path=?""", j.get("job").asText(), j.get("step").asText(), j.get("pgm").asText(), j.get("line").asInt(), j.get("member").asText())).as(j.toString()).isEqualTo(1);
            if (j.has("dd")) {
                assertThat(n("""
                        SELECT count(*) FROM jcl_dd d JOIN jcl_step s ON s.id=d.step_id JOIN jcl_job j ON j.id=s.job_id
                        WHERE j.name=? AND d.dd_name=? AND d.dsn=? AND d.gdg_rel=?""", j.get("job").asText(), j.get("dd").asText(), j.get("dsn").asText(), j.get("gdg_rel").asInt())).as(j.toString()).isEqualTo(1);
            }
        }
        for (JsonNode m : hand.get("maps")) {
            assertThat(n("SELECT count(*) FROM bms_map b JOIN member x ON x.id=b.member_id WHERE b.name=? AND b.mapset=? AND x.path=? AND b.line=?", m.get("map").asText(), m.get("mapset").asText(), m.get("member").asText(), m.get("line").asInt())).as(m.toString()).isEqualTo(1);
            assertThat(n("""
                    SELECT count(*) FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id
                    WHERE e.type='SENDS_MAP' AND s.name=? AND d.name=? AND e.line=?""", m.get("program").asText(), m.get("map").asText(), m.get("send_line").asInt())).as(m.toString()).isEqualTo(1);
        }
        for (JsonNode t : hand.get("transactions")) {
            assertThat(n("""
                    SELECT count(*) FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id JOIN member m ON m.id=e.member_id
                    WHERE e.type='RUNS_IN' AND d.kind='transaction' AND d.name=? AND s.name=? AND m.path=? AND e.line=?""", t.get("transaction").asText(), t.get("program").asText(), t.get("member").asText(), t.get("line").asInt())).as(t.toString()).isEqualTo(1);
        }
        for (JsonNode f : hand.get("field_flow")) {
            assertThat(n("""
                    SELECT count(*) FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id JOIN member m ON m.id=e.member_id
                    WHERE e.type='MOVES_TO' AND s.scope=? AND s.name=? AND d.scope=? AND d.name=? AND m.path=? AND e.line=?""",
                    f.get("from_program").asText(), f.get("from_item").asText(), f.get("to_program").asText(), f.get("to_item").asText(), f.get("member").asText(), f.get("line").asInt())).as(f.toString()).isEqualTo(1);
        }
        assertThat(reports.deadCodeCandidates()).extracting(m -> m.get("program_id")).containsExactly("CBTRN01C");
        // Every CICS program in the estate that sends a map has a map with BMS source to match, in the base application.
        assertThat(n("SELECT count(*) FROM bms_map")).isEqualTo(21);
        // DSNTIAC is called dynamically from the copybook CSDB2RPY; this edge was lost once to a late re-parse.
        assertThat(n("""
                SELECT count(*) FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id JOIN member m ON m.id=e.member_id
                WHERE e.type='CALLS' AND d.name='DSNTIAC' AND m.name='CSDB2RPY'""")).isEqualTo(1);
    }

    /** Independent of the parser: the count of EXEC CICS in code, read straight from the files. */
    @Test
    void cicsStatementCountMatchesARawSourceScan() throws Exception {
        run();
        long raw = 0;
        for (String path : jdbc.queryForList("SELECT path FROM member WHERE type='cobol'", String.class)) {
            for (String l : Files.readAllLines(CORPUS.resolve(path), java.nio.charset.StandardCharsets.ISO_8859_1)) {
                String c = l.length() > 72 ? l.substring(0, 72) : l;
                if (c.length() > 7 && "*/".indexOf(c.charAt(6)) < 0 && c.substring(7).matches("(?i).*\\bEXEC\\s+CICS\\b.*")) raw++;
            }
        }
        assertThat(raw).isGreaterThan(200);
        assertThat(n("SELECT count(*) FROM (SELECT DISTINCT program_id, line, command FROM cics_cmd) x")).isEqualTo(raw);
    }

    @Test
    void snapshotOfTheGraphIsUnchanged() throws Exception {
        run();
        Path file = REPO.resolve("testdata/snapshots/carddemo-summary.json");
        Map<String, Object> s = new java.util.TreeMap<>(reports.summary());
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(SerializationFeature.INDENT_OUTPUT);
        String now = om.writeValueAsString(s);
        if (Boolean.getBoolean("snapshot.update") || !Files.exists(file)) {
            Files.writeString(file, now);
        }
        assertThat(now).isEqualTo(Files.readString(file));
    }
}
