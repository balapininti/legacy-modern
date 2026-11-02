package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * M7. The expected results are bytes the real COBOL wrote, running under GnuCOBOL (scripts/capture-gnucobol.sh). The
 * generated tests really compile and run with Maven, against a reference Java implementation written from the source.
 */
class VerificationIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path JAR = REPO.resolve("cli/target/toolkit.jar");
    static final Path CORPUS = REPO.resolve(".cache/corpora/carddemo");
    static final Path CAPTURE = REPO.resolve("testdata/captures/CBACT04C");
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static final ObjectMapper OM = new ObjectMapper();

    private static Map<String, String> env() {
        return Map.of("TOOLKIT_DB_URL", pg.getJdbcUrl(), "TOOLKIT_DB_USER", pg.getUsername(), "TOOLKIT_DB_PASSWORD", pg.getPassword());
    }

    private static String toolkit(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(REPO.resolve("cli/target/verify-it.log").toFile()));
        b.environment().putAll(env());
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(String.join(" ", args) + "\n" + out).isZero();
        return out.strip();
    }

    private record Mvn(int exit, String output) {}

    private static Mvn mvnTest(Path dir) throws Exception {
        ProcessBuilder b = new ProcessBuilder("mvn", "-B", "-q", "test").directory(dir.toFile()).redirectErrorStream(true);
        b.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        return new Mvn(p.waitFor(), out);
    }

    private long n(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private String one(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    private void sql(String stmt) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.executeUpdate(stmt);
        }
    }

    private static final String[] GENERATE = {"tests", "generate", "--program", "CBACT04C", "--package", "com.acme.card.characterization", "--adapter", "com.acme.card.batch.InterestCalculatorAdapter", "--out"};

    private static String[] generate(Path repo) {
        String[] a = java.util.Arrays.copyOf(GENERATE, GENERATE.length + 1);
        a[GENERATE.length] = repo.toString();
        return a;
    }

    @Test
    void capturedBehaviorDrivesTestsThatPassFailOnABrokenRuleAndHonourADeviation() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS.resolve("app/cbl")) && Files.isDirectory(CAPTURE.resolve("run1")) && Files.exists(JAR));
        pg.start();
        toolkit("discover", "--root", CORPUS.toString(), "--threads", "8");
        String imported = toolkit("capture", "import", "--dir", CAPTURE.toString(), "--encoding", "utf-8");
        assertThat(imported).contains("fixtures=3");
        // Each run: 50 categories, 50 cross references, 50 accounts, 51 groups in; 50 accounts, 50 transactions out.
        assertThat(n("SELECT jsonb_array_length(expected_json->'TRANSACT') FROM fixture WHERE name='run1'")).isEqualTo(50);
        assertThat(one("SELECT source FROM capture")).isEqualTo("gnucobol");
        assertThat(one("SELECT expected_json->'TRANSACT'->0->>'TRAN-ID' FROM fixture WHERE name='run1'")).isEqualTo("2022071800000001");
        assertThat(one("SELECT expected_json->'TRANSACT'->0->>'TRAN-SOURCE' FROM fixture WHERE name='run1'")).startsWith("System");

        Path repo = TargetRepoFixture.create(REPO, Files.createTempDirectory("verify"));
        // First run, nothing registered: the only differences are the timestamps the COBOL stamped when it ran.
        String gen = toolkit(generate(repo));
        assertThat(gen).contains("fixtures=3, deviations=0");
        Mvn first = mvnTest(repo);
        assertThat(first.exit()).as(first.output()).isNotZero();
        assertThat(first.output()).contains("TRAN-ORIG-TS");
        String report = toolkit("tests", "report", "--program", "CBACT04C", "--repo", repo.toString());
        assertThat(report).contains("passing=0").contains("tests=3");
        // Unregistered differences became proposals for a lead; nothing has been suppressed yet.
        assertThat(n("SELECT count(*) FROM deviation WHERE status='candidate' AND legacy_ref->>'field' IN ('TRAN-ORIG-TS','TRAN-PROC-TS')")).isEqualTo(2);
        assertThat(n("SELECT count(*) FROM deviation WHERE legacy_ref->>'field' NOT IN ('TRAN-ORIG-TS','TRAN-PROC-TS')")).as("the reference implementation must match on every other field").isZero();

        // A lead approves those two: run time is expected to differ. An approved deviation suppresses a known difference.
        sql("UPDATE deviation SET status='approved', approved_by='lead1'");
        assertThat(toolkit(generate(repo))).contains("deviations=2");
        delete(repo.resolve("target/characterization-diffs"));
        Mvn pass = mvnTest(repo);
        assertThat(pass.exit()).as(pass.output()).isZero();
        assertThat(toolkit("tests", "report", "--program", "CBACT04C", "--repo", repo.toString())).contains("passing=3").contains("tests=3");
        assertThat(one("SELECT s.stage FROM migration_status s JOIN program p ON p.id=s.program_id WHERE p.program_id='CBACT04C'")).isEqualTo("tests_passing");
        assertThat(n("SELECT tests_passing FROM migration_status s JOIN program p ON p.id=s.program_id WHERE p.program_id='CBACT04C'")).isEqualTo(3);

        // Break a rule: rounding instead of COBOL's truncation. The tests must catch it, by naming the field.
        Path calc = repo.resolve("src/main/java/com/acme/card/batch/InterestCalculator.java");
        String good = Files.readString(calc);
        assertThat(good).contains("RoundingMode.DOWN");
        Files.writeString(calc, good.replace("RoundingMode.DOWN", "RoundingMode.HALF_UP"));
        delete(repo.resolve("target/characterization-diffs"));
        Mvn broken = mvnTest(repo);
        assertThat(broken.exit()).isNotZero();
        assertThat(broken.output()).contains("TRAN-AMT");
        JsonNode diffs = OM.readTree(repo.resolve("target/characterization-diffs/CBACT04C-run3.json").toFile());
        assertThat(diffs.get("differences").findValuesAsText("field")).contains("TRAN-AMT");
        assertThat(diffs.get("differences").toString()).doesNotContain("TRAN-ORIG-TS");

        // A numeric tolerance deviation suppresses that known difference, and only within the tolerance.
        sql("""
                INSERT INTO deviation (legacy_ref, modern_ref, change, reason, status, approved_by, comparison_rule) VALUES
                ('{"program":"CBACT04C","field":"TRAN-AMT"}','{"program":"CBACT04C"}','rounds half up','client policy','approved','lead1','{"kind":"numeric_tolerance","field":"TRAN-AMT","tolerance":"0.01"}'),
                ('{"program":"CBACT04C","field":"ACCT-CURR-BAL"}','{"program":"CBACT04C"}','rounds half up','client policy','approved','lead1','{"kind":"numeric_tolerance","field":"ACCT-CURR-BAL","tolerance":"0.50"}')""");
        toolkit(generate(repo));
        delete(repo.resolve("target/characterization-diffs"));
        Mvn tolerated = mvnTest(repo);
        assertThat(tolerated.exit()).as(tolerated.output()).isZero();

        // Something bigger than the tolerance still fails.
        Files.writeString(calc, good.replace("new BigDecimal(1200)", "new BigDecimal(1000)"));
        delete(repo.resolve("target/characterization-diffs"));
        assertThat(mvnTest(repo).exit()).isNotZero();
    }

    private static void delete(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
