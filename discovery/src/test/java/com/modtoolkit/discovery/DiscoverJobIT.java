package com.modtoolkit.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.datasource.hikari.maximum-pool-size=24", "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '30s'"})
@Testcontainers
class DiscoverJobIT {
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
    @Autowired JdbcTemplate jdbc;
    @Autowired DiscoveryReports reports;
    @TempDir Path root;

    static final String CPY = """
       01 CUST-REC.
           05 CUST-ID PIC X(4).
           05 CUST-BAL PIC S9(7)V99 COMP-3.
""";

    static String caller() {
        return """
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CALLER.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-ARG PIC X(4).
           COPY CUSTREC.
       PROCEDURE DIVISION.
       P100.
           MOVE CUST-ID TO WS-ARG.
           CALL 'CALLEE' USING WS-ARG.
           EXEC SQL SELECT C1 INTO :WS-ARG FROM ACCOUNTS END-EXEC.
           GOBACK.
""";
    }

    static String callee() {
        return """
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CALLEE.
       DATA DIVISION.
       LINKAGE SECTION.
       01 LS-IN PIC X(4).
       PROCEDURE DIVISION USING LS-IN.
       P200.
           DISPLAY LS-IN.
           GOBACK.
""";
    }

    @BeforeEach
    void clean() throws Exception {
        jdbc.execute("TRUNCATE member, graph_node CASCADE");
        Files.writeString(root.resolve("CUSTREC"), CPY);
        Files.writeString(root.resolve("CALLER"), caller());
        Files.writeString(root.resolve("CALLEE.cbl"), callee());
    }

    private DiscoverOptions opts() {
        return DiscoverOptions.of(root).withThreads(4);
    }

    private long count(String sql, Object... a) {
        return jdbc.queryForObject(sql, Long.class, a);
    }

    @Test
    void endToEndGraphWithProvenance() throws Exception {
        discovery.run(opts());
        assertThat(jdbc.queryForList("SELECT parse_status FROM member", String.class)).containsOnly("parsed");
        assertThat(jdbc.queryForObject("SELECT type FROM member WHERE name='CUSTREC'", String.class)).isEqualTo("copybook");
        Map<String, Object> call = jdbc.queryForMap("""
                SELECT e.line, m.name FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id JOIN member m ON m.id=e.member_id
                WHERE e.type='CALLS' AND d.name='CALLEE'""");
        assertThat(call).containsEntry("line", 10).containsEntry("name", "CALLER");
        assertThat(count("SELECT count(*) FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id WHERE e.type='SELECTS' AND d.kind='table' AND d.name='ACCOUNTS'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id WHERE e.type='COPIES' AND d.name='CUSTREC'")).isEqualTo(1);
        // A data item from COPY belongs to the copybook member with the copybook's own line.
        Map<String, Object> di = jdbc.queryForMap("SELECT m.name, d.line, d.length FROM data_item d JOIN member m ON m.id=d.owner_member_id WHERE d.name='CUST-BAL' AND d.program_id IS NOT NULL");
        assertThat(di).containsEntry("name", "CUSTREC").containsEntry("line", 3).containsEntry("length", 5);
        // Field flow crosses CALL USING from the argument to the linkage parameter.
        assertThat(count("""
                SELECT count(*) FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id
                WHERE e.type='MOVES_TO' AND s.name='WS-ARG' AND s.scope='CALLER' AND d.name='LS-IN' AND d.scope='CALLEE' AND e.attrs->>'op'='CALL USING'""")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metrics->>'fan_in' FROM program WHERE program_id='CALLEE'", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT metrics->>'fan_out' FROM program WHERE program_id='CALLER'", String.class)).isEqualTo("1");
        assertThat(reports.staticCallResolution()).containsEntry("resolved_pct", 100.0);
    }

    @Test
    void secondRunTouchesNothingAndChangesOnlyWhatChanged() throws Exception {
        discovery.run(opts());
        Map<String, Object> before = stamps();
        discovery.run(opts());
        assertThat(stamps()).isEqualTo(before);

        // A changed copybook re-parses the copybook and its user, not the unrelated program.
        Thread.sleep(5);
        Files.writeString(root.resolve("CUSTREC"), CPY + "           05 CUST-NEW PIC X.\n");
        discovery.run(opts());
        Map<String, Object> after = stamps();
        assertThat(after.get("CALLEE")).isEqualTo(before.get("CALLEE"));
        assertThat(after.get("CUSTREC")).isNotEqualTo(before.get("CUSTREC"));
        assertThat(after.get("CALLER")).isNotEqualTo(before.get("CALLER"));
        assertThat(count("SELECT count(*) FROM data_item WHERE name='CUST-NEW' AND program_id IS NOT NULL")).isEqualTo(1);
        // Facts are replaced, not added to.
        assertThat(count("SELECT count(*) FROM graph_edge WHERE type='CALLS'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM program WHERE program_id='CALLER'")).isEqualTo(1);

        // A deleted member disappears with its facts.
        Files.delete(root.resolve("CALLEE.cbl"));
        discovery.run(opts());
        assertThat(count("SELECT count(*) FROM program WHERE program_id='CALLEE'")).isZero();
        assertThat(reports.callsToMissingPrograms()).extracting(m -> m.get("target")).contains("CALLEE");
    }

    private Map<String, Object> stamps() {
        Map<String, Object> m = new java.util.HashMap<>();
        jdbc.query("SELECT name, updated_at FROM member", rs -> { m.put(rs.getString(1), rs.getTimestamp(2)); });
        return m;
    }

    @Test
    void stoppedRunResumesWithoutRedoingWork() throws Exception {
        for (int i = 0; i < 12; i++) Files.writeString(root.resolve("PGM" + i), callee().replace("CALLEE", "PGM" + i));
        discovery.run(DiscoverOptions.of(root).withThreads(1), 5);
        long parsed = count("SELECT count(*) FROM member WHERE parse_status='parsed'");
        assertThat(parsed).isBetween(5L, 6L);
        Map<String, Object> firstRun = stamps();
        discovery.run(opts());
        assertThat(count("SELECT count(*) FROM member WHERE parse_status <> 'parsed'")).isZero();
        Map<String, Object> second = stamps();
        long unchanged = firstRun.keySet().stream().filter(k -> firstRun.get(k).equals(second.get(k))).count();
        assertThat(unchanged).as("members already done are left alone").isGreaterThanOrEqualTo(parsed);
    }

    @Test
    void badMembersGoToTheReportAndNeverStopTheJob() throws Exception {
        Files.writeString(root.resolve("BROKEN"), callee().replace("DISPLAY LS-IN.", "MOVE TO TO TO."));
        Files.write(root.resolve("BIN"), new byte[] {0, 1, 2, 3, 0, 0, 7, 8, 0});
        Files.writeString(root.resolve("EMPTY"), "");
        Files.writeString(root.resolve("README"), "just some words\n");
        discovery.run(opts());
        List<Map<String, Object>> f = reports.parseFailures();
        assertThat(f).hasSize(1);
        assertThat(f.get(0)).containsEntry("path", "BROKEN");
        assertThat((String) f.get(0).get("parse_error")).startsWith("line 8:");
        assertThat(reports.unknownMembers()).extracting(m -> m.get("classify_reason")).contains("binary content", "empty", "no known content signature");
        assertThat(count("SELECT count(*) FROM program WHERE program_id='CALLER'")).isEqualTo(1);
    }

    @Test
    void ebcdicPdsDumpWithoutExtensionsParses() throws Exception {
        Files.write(root.resolve("EBCPGM"), callee().replace("CALLEE", "EBCPGM").replace("\n", "\u0085").getBytes(Charset.forName("IBM037")));
        discovery.run(opts());
        assertThat(jdbc.queryForMap("SELECT encoding, parse_status FROM member WHERE name='EBCPGM'")).containsEntry("encoding", "cp037").containsEntry("parse_status", "parsed");
        assertThat(count("SELECT count(*) FROM program WHERE program_id='EBCPGM'")).isEqualTo(1);
    }

    /** Regression: parsing a late-discovered copybook must not delete the facts of the programs that expanded it. */
    @Test
    void factsFromAFragmentCopybookSurviveItsLateClassification() throws Exception {
        // Statements only, so its content signature says nothing; only the COPY reveals what it is.
        Files.writeString(root.resolve("FRAG"), "       9000-FRAG.\n           CALL 'FROMFRAG'.\n           EXEC SQL DELETE FROM FRAGTAB END-EXEC.\n");
        Files.writeString(root.resolve("USEFRAG"), """
       IDENTIFICATION DIVISION.
       PROGRAM-ID. USEFRAG.
       PROCEDURE DIVISION.
           GOBACK.
           COPY FRAG.
""");
        discovery.run(opts());
        assertThat(jdbc.queryForMap("SELECT type, classify_reason FROM member WHERE name='FRAG'")).containsEntry("type", "copybook").containsEntry("classify_reason", "referenced by COPY");
        Map<String, Object> call = jdbc.queryForMap("""
                SELECT m.name, e.line FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id JOIN member m ON m.id=e.member_id WHERE e.type='CALLS' AND d.name='FROMFRAG'""");
        assertThat(call).containsEntry("name", "FRAG").containsEntry("line", 2);
        assertThat(count("SELECT count(*) FROM graph_edge e JOIN graph_node d ON d.id=e.dst_id WHERE e.type='DELETES' AND d.name='FRAGTAB'")).isEqualTo(1);
    }

    @Test
    void jobsMapsTablesAndTransactionsLinkToPrograms() throws Exception {
        Files.writeString(root.resolve("NIGHTLY"), """
//NIGHTLY JOB (A),'X',CLASS=A
//RUN1 EXEC PGM=CALLER
//IN  DD DSN=PROD.CUST.DATA,DISP=SHR
//OUT DD DSN=PROD.CUST.OUT(+1),DISP=(NEW,CATLG)
""");
        Files.writeString(root.resolve("ONLINE"), """
       IDENTIFICATION DIVISION.
       PROGRAM-ID. ONLINE.
       PROCEDURE DIVISION.
           EXEC CICS SEND MAP('MAP1') MAPSET('SET1') END-EXEC.
           EXEC CICS RETURN END-EXEC.
""");
        Files.writeString(root.resolve("SET1"), "SET1     DFHMSD TYPE=&SYSPARM,LANG=COBOL\nMAP1     DFHMDI SIZE=(24,80)\nFLD1     DFHMDF POS=(1,1),LENGTH=5\n         DFHMSD TYPE=FINAL\n");
        Files.writeString(root.resolve("CSD1"), "DEFINE TRANSACTION(TX01) GROUP(G) PROGRAM(ONLINE)\n");
        Files.writeString(root.resolve("DDL1"), "CREATE TABLE S.ACCOUNTS (ID INT NOT NULL, PRIMARY KEY (ID));\n");
        discovery.run(opts());
        assertThat(jdbc.queryForList("SELECT parse_status FROM member", String.class)).containsOnly("parsed");
        assertThat(jdbc.queryForMap("SELECT s.pgm, s.seq, s.line FROM jcl_step s JOIN jcl_job j ON j.id=s.job_id WHERE j.name='NIGHTLY'")).containsEntry("pgm", "CALLER").containsEntry("line", 2);
        assertThat(jdbc.queryForObject("SELECT gdg_rel FROM jcl_dd WHERE dd_name='OUT'", Integer.class)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id WHERE s.name='CALLER' AND e.type='RUNS_IN' AND d.kind='step'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM graph_edge e JOIN graph_node s ON s.id=e.src_id JOIN graph_node d ON d.id=e.dst_id WHERE s.name='ONLINE' AND e.type='RUNS_IN' AND d.kind='transaction' AND d.name='TX01'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT m.name FROM graph_node n JOIN member m ON m.id=n.member_id WHERE n.kind='map' AND n.name='MAP1'", String.class)).isEqualTo("SET1");
        assertThat(jdbc.queryForObject("SELECT m.name FROM graph_node n JOIN member m ON m.id=n.member_id WHERE n.kind='table' AND n.name='ACCOUNTS'", String.class)).isEqualTo("DDL1");
        assertThat(count("SELECT count(*) FROM bms_field WHERE name='FLD1' AND \"row\"=1 AND length=5")).isEqualTo(1);
        // Run by JCL or a transaction means alive; the callee of CALLER was already alive.
        assertThat(reports.deadCodeCandidates()).extracting(m -> m.get("program_id")).doesNotContain("CALLER", "ONLINE", "CALLEE");
    }

    @Test
    void aChangedProcRefreshesTheJobsThatExpandIt() throws Exception {
        Files.writeString(root.resolve("MYPROC"), "//MYPROC PROC\n//S EXEC PGM=OLDPGM\n");
        Files.writeString(root.resolve("JOB1"), "//JOB1 JOB\n//R EXEC MYPROC\n");
        discovery.run(opts());
        assertThat(jdbc.queryForList("SELECT pgm FROM jcl_step", String.class)).containsExactly("OLDPGM");
        Files.writeString(root.resolve("MYPROC"), "//MYPROC PROC\n//S EXEC PGM=NEWPGM\n");
        discovery.run(opts());
        assertThat(jdbc.queryForList("SELECT pgm FROM jcl_step", String.class)).containsExactly("NEWPGM");
    }

    @Test
    void missingCopybookIsFlaggedAndUnreferencedProgramIsADeadCodeCandidate() throws Exception {
        Files.writeString(root.resolve("ORPHAN"), caller().replace("PROGRAM-ID. CALLER", "PROGRAM-ID. ORPHAN").replace("COPY CUSTREC.", "COPY NOSUCH.\n           COPY DFHAID.").replace("MOVE CUST-ID TO WS-ARG.", "CONTINUE.").replace("CALL 'CALLEE' USING WS-ARG.", "CONTINUE."));
        discovery.run(opts());
        assertThat(reports.missingCopybooks()).extracting(m -> m.get("name")).containsExactly("NOSUCH");
        assertThat(reports.deadCodeCandidates()).extracting(m -> m.get("program_id")).contains("ORPHAN", "CALLER").doesNotContain("CALLEE");
    }
}
