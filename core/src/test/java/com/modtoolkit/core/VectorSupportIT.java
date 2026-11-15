package com.modtoolkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.core.config.Doctor;
import com.modtoolkit.core.testdb.TestPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** pgvector is used when the server has it and is optional when it does not: a client's own PostgreSQL may lack it. */
class VectorSupportIT {
    @TempDir Path dir;

    private static JdbcTemplate migrated(TestPostgres.Database pg) {
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    private static Doctor.CheckResult vectorCheck(List<Doctor.CheckResult> out) {
        return out.stream().filter(r -> r.check().equals("vector")).findFirst().orElseThrow();
    }

    private List<Doctor.CheckResult> doctor(TestPostgres.Database pg) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path cfg = dir.resolve("toolkit.yaml");
        Files.writeString(cfg, "client: c\nsource:\n  root: " + src + "\n");
        Map<String, String> env = Map.of("TOOLKIT_DB_URL", pg.getJdbcUrl(), "TOOLKIT_DB_USER", pg.getUsername(), "TOOLKIT_DB_PASSWORD", pg.getPassword());
        return new Doctor(env::get).runChecked(cfg);
    }

    @Test
    void withoutPgvectorMigrationsSucceedAndDoctorWarns() throws Exception {
        TestPostgres.Database pg = TestPostgres.newDatabaseWithoutVector();
        JdbcTemplate j = migrated(pg);
        assertThat(j.queryForObject("SELECT count(*) FROM pg_available_extensions WHERE name = 'vector'", Integer.class)).isZero();
        assertThat(j.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success", Integer.class)).isOne();
        assertThat(j.queryForObject("SELECT to_regclass('public.chunk') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(VectorSupport.available(j)).isFalse();
        assertThat(VectorSupport.enable(j)).isFalse();
        assertThat(VectorSupport.available(j)).isFalse();

        List<Doctor.CheckResult> r = doctor(pg);
        assertThat(r).filteredOn(x -> x.check().equals("database")).extracting(Doctor.CheckResult::status).containsExactly(Doctor.Status.PASS);
        Doctor.CheckResult v = vectorCheck(r);
        assertThat(v.status()).isEqualTo(Doctor.Status.WARN);
        assertThat(v.detail()).contains("lexical").contains("pgvector").contains("toolkit reindex");
        assertThat(Doctor.ok(r)).isTrue();
    }

    @Test
    void withPgvectorTheColumnExistsAndDoctorPasses() throws Exception {
        TestPostgres.Database pg = TestPostgres.newDatabase();
        JdbcTemplate j = migrated(pg);
        assertThat(VectorSupport.available(j)).isTrue();
        assertThat(j.queryForObject("SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'chunk'::regclass AND attname = 'embedding'", String.class))
                .isEqualTo("vector");
        assertThat(VectorSupport.enable(j)).isTrue();
        assertThat(vectorCheck(doctor(pg)).status()).isEqualTo(Doctor.Status.PASS);
    }

    @Test
    void enableAddsTheColumnOnceTheExtensionIsInstallable() throws Exception {
        // A database set up before pgvector was installed: the server has it now, the column is not there yet.
        TestPostgres.Database pg = TestPostgres.newDatabase();
        JdbcTemplate j = migrated(pg);
        j.execute("ALTER TABLE chunk DROP COLUMN embedding");
        assertThat(VectorSupport.available(j)).isFalse();
        Doctor.CheckResult before = vectorCheck(doctor(pg));
        assertThat(before.status()).isEqualTo(Doctor.Status.WARN);
        assertThat(before.detail()).contains("toolkit reindex");
        assertThat(VectorSupport.enable(j)).isTrue();
        assertThat(VectorSupport.available(j)).isTrue();
        assertThat(vectorCheck(doctor(pg)).status()).isEqualTo(Doctor.Status.PASS);
    }
}
