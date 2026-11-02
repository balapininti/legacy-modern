package com.modtoolkit.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DoctorAndMigrationIT {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TempDir Path dir;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
    }

    private Connection con() throws Exception {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private Doctor doctor() {
        Map<String, String> e = Map.of(
                "TOOLKIT_DB_URL", pg.getJdbcUrl(), "TOOLKIT_DB_USER", pg.getUsername(), "TOOLKIT_DB_PASSWORD", pg.getPassword());
        return new Doctor(e::get);
    }

    @Test
    void everySpecTableExists() throws Exception {
        List<String> spec = List.of("member", "program", "copybook", "copy_use", "paragraph", "data_item", "condition_value",
                "graph_node", "graph_edge", "sql_stmt", "cics_cmd", "jcl_job", "jcl_step", "jcl_dd", "bms_map", "bms_field",
                "db2_table", "db2_column", "knowledge_artifact", "chunk", "migration_status", "modern_unit", "migration_link",
                "rule_coverage", "deviation", "capture", "fixture", "llm_run", "audit_log", "app_user", "role", "api_token",
                "batch_job_instance");
        try (Connection c = con(); Statement s = c.createStatement()) {
            for (String t : spec) {
                try (ResultSet r = s.executeQuery("SELECT to_regclass('public." + t + "')")) {
                    r.next();
                    assertThat(r.getString(1)).as(t).isNotNull();
                }
            }
        }
    }

    @Test
    void cobolTextSearchKeepsHyphenatedIdentifiersWhole() throws Exception {
        try (Connection c = con(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT cobol_tsvector('MOVE WS-CUST-REC TO 1000-MAIN-PARA.')::text")) {
            r.next();
            assertThat(r.getString(1)).contains("'ws-cust-rec'").contains("'cust'");
            // Numeric-leading paragraph names are the common COBOL form; they must be findable whole too.
            assertThat(r.getString(1)).contains("'1000-main-para'");
        }
    }

    @Test
    void cobolQueryMatchesWholeIdentifierAndHandlesQuoteCharacters() throws Exception {
        try (Connection c = con(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT cobol_tsvector('PERFORM 1000-MAIN-PARA') @@ cobol_tsquery('1000-main-para'),"
                        + " cobol_tsvector('x') @@ cobol_tsquery('it''s (a) & ! :*'), cobol_tsvector('') = ''::tsvector")) {
            r.next();
            assertThat(r.getBoolean(1)).isTrue();
            assertThat(r.getBoolean(2)).isFalse();
            assertThat(r.getBoolean(3)).isTrue();
        }
    }

    @Test
    void auditLogIsAppendOnly() throws Exception {
        try (Connection c = con(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO audit_log (action) VALUES ('test')");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM audit_log")).hasMessageContaining("append-only");
        }
    }

    @Test
    void doctorPassesAValidConfigAndFailsAnInvalidOne() throws Exception {
        Path src = Files.createDirectory(dir.resolve("src"));
        Path good = dir.resolve("good.yaml");
        Files.writeString(good, "client: c\nsource:\n  root: " + src + "\n");
        assertThat(Doctor.ok(doctor().runChecked(good))).isTrue();

        Path bad = dir.resolve("bad.yaml");
        Files.writeString(bad, "client: c\nsource:\n  root: /nope/never\n  encoding: klingon\n");
        List<Doctor.CheckResult> r = doctor().runChecked(bad);
        assertThat(Doctor.ok(r)).isFalse();
        assertThat(r).extracting(Doctor.CheckResult::check).contains("config source.encoding", "source");
    }

    @Test
    void doctorFailsWithoutDatabaseUrlAndMissingKey() throws Exception {
        Path src = Files.createDirectory(dir.resolve("s2"));
        Path cfg = dir.resolve("c.yaml");
        Files.writeString(cfg, "client: c\nsource:\n  root: " + src + "\nllm:\n  provider: openai\n  api_key_env: NOT_SET_KEY\n");
        List<Doctor.CheckResult> r = new Doctor(k -> null).runChecked(cfg);
        assertThat(r).filteredOn(x -> x.status() == Doctor.Status.FAIL).extracting(Doctor.CheckResult::check).containsExactly("database", "llm");
    }
}
