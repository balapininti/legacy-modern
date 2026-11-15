package com.modtoolkit.core.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modtoolkit.core.testdb.TestPostgres;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPostgresIT {
    static final Duration T = Duration.ofSeconds(60);
    static final String SU = "toolkit_admin";
    static final String PW = "s3cret 'quoted\" pw";

    @TempDir Path tmp;
    final List<LocalPostgres> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        for (LocalPostgres pg : started) {
            try {
                pg.stop();
            } catch (RuntimeException ignored) {
                // the test already failed
            }
        }
    }

    private LocalPostgres server(Path dataDir) throws IOException {
        LocalPostgres pg = new LocalPostgres(TestPostgres.pgHome(), dataDir, TestPostgres.freePort());
        started.add(pg);
        return pg;
    }

    private static String one(String url, String user, String pw, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, pw); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    private static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void initStartUseAndStopInAPathWithASpace() throws Exception {
        LocalPostgres pg = server(tmp.resolve("dir with space").resolve("pg data"));
        assertThat(pg.initialized()).isFalse();
        assertThat(pg.running()).isFalse();

        pg.init(SU, PW);
        assertThat(pg.initialized()).isTrue();
        assertThat(Files.list(tmp.resolve("dir with space")).map(p -> p.getFileName().toString())).containsExactly("pg data");

        pg.start(T);
        assertThat(pg.running()).isTrue();
        assertThat(pg.port()).isPositive();
        assertThat(Files.exists(pg.logFile())).isTrue();

        String url = pg.jdbcUrl("postgres");
        assertThat(url).isEqualTo("jdbc:postgresql://127.0.0.1:" + pg.port() + "/postgres");
        assertThat(one(url, SU, PW, "SHOW listen_addresses")).isEqualTo("127.0.0.1");
        assertThat(one(url, SU, PW, "SHOW password_encryption")).isEqualTo("scram-sha-256");
        assertThat(one(url, SU, PW, "SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = 'postgres'")).isEqualTo("UTF8");
        assertThatThrownBy(() -> one(url, SU, "wrong", "SELECT 1")).isInstanceOf(SQLException.class);

        pg.createRole("app \"user\"", "app'pw");
        pg.createDatabase("app db", "app \"user\"");
        String appUrl = pg.jdbcUrl("app db");
        assertThat(one(appUrl, "app \"user\"", "app'pw", "SELECT current_database()")).isEqualTo("app db");
        try (Connection c = DriverManager.getConnection(appUrl, SU, PW); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            try (ResultSet r = s.executeQuery("SELECT '[1,2]'::vector <-> '[1,3]'::vector, similarity('PERFORM', 'PERFORMS')")) {
                r.next();
                assertThat(r.getDouble(1)).isEqualTo(1.0);
                assertThat(r.getDouble(2)).isGreaterThan(0.5);
            }
        }

        long pid = pg.postmasterPid().orElseThrow();
        pg.stop();
        assertThat(pg.running()).isFalse();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(listening(pg.port())).isFalse();
        pg.stop(); // stopping a stopped server is a no-op
    }

    @Test
    void startWhenRunningIsANoOpAndDataSurvivesARestart() throws Exception {
        LocalPostgres pg = server(tmp.resolve("data"));
        pg.init(SU, PW);
        pg.start(T);
        long pid = pg.postmasterPid().orElseThrow();
        pg.start(T);
        assertThat(pg.postmasterPid()).hasValue(pid);

        pg.createDatabase("kept");
        pg.stop();
        LocalPostgres again = server(tmp.resolve("data")).superuser(SU, PW);
        again.start(T);
        assertThat(one(again.jdbcUrl("kept"), SU, PW, "SELECT current_database()")).isEqualTo("kept");
    }

    @Test
    void recoversFromAStalePostmasterPidAfterAKill() throws Exception {
        LocalPostgres pg = server(tmp.resolve("data"));
        pg.init(SU, PW);
        pg.start(T);
        long pid = pg.postmasterPid().orElseThrow();
        ProcessHandle postmaster = ProcessHandle.of(pid).orElseThrow();
        assertThat(postmaster.destroyForcibly()).isTrue(); // SIGKILL, like kill -9
        postmaster.onExit().get();
        assertThat(Files.exists(tmp.resolve("data/postmaster.pid"))).isTrue();
        assertThat(pg.running()).isFalse();

        pg.start(T);
        assertThat(pg.running()).isTrue();
        assertThat(pg.postmasterPid().orElseThrow()).isNotEqualTo(pid);
        assertThat(one(pg.jdbcUrl("postgres"), SU, PW, "SELECT 1")).isEqualTo("1");
    }

    @Test
    void recoversWhenTheStalePidBelongsToAnotherLiveProcess() throws Exception {
        LocalPostgres pg = server(tmp.resolve("data"));
        pg.init(SU, PW);
        pg.start(T);
        ProcessHandle postmaster = ProcessHandle.of(pg.postmasterPid().orElseThrow()).orElseThrow();
        postmaster.destroyForcibly();
        postmaster.onExit().get();
        // The process id was reused, here by this JVM: alive, but not a postgres. Plain pg_ctl would refuse to start.
        Path pidFile = tmp.resolve("data/postmaster.pid");
        List<String> lines = new ArrayList<>(Files.readAllLines(pidFile));
        lines.set(0, Long.toString(ProcessHandle.current().pid()));
        Files.write(pidFile, lines);
        assertThat(pg.running()).isFalse();

        pg.start(T);
        assertThat(pg.running()).isTrue();
        assertThat(one(pg.jdbcUrl("postgres"), SU, PW, "SELECT 1")).isEqualTo("1");
    }

    @Test
    void aTakenPortFailsNamingThePort() throws Exception {
        LocalPostgres pg = server(tmp.resolve("data"));
        pg.init(SU, PW);
        try (ServerSocket squatter = new ServerSocket()) {
            squatter.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), pg.port()));
            assertThatThrownBy(() -> pg.start(T))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(pg.port()));
            assertThat(pg.running()).isFalse();
        }
        pg.start(T);
        assertThat(pg.running()).isTrue();
    }

    @Test
    void initRefusesAnExistingCluster() throws Exception {
        LocalPostgres pg = server(tmp.resolve("data"));
        pg.init(SU, PW);
        assertThatThrownBy(() -> pg.init(SU, PW)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
    }

    @Test
    void binariesAreResolvedUnderPgHome() {
        Path home = Path.of("pg home").toAbsolutePath();
        LocalPostgres pg = new LocalPostgres(home, Path.of("d"), 5);
        String exe = System.getProperty("os.name").toLowerCase().startsWith("windows") ? ".exe" : "";
        assertThat(pg.binary("pg_ctl")).isEqualTo(home.resolve("bin").resolve("pg_ctl" + exe));
    }
}
