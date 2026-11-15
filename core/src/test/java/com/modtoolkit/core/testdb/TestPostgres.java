package com.modtoolkit.core.testdb;

import com.modtoolkit.core.local.LocalPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * One throwaway native PostgreSQL per test JVM, run through the production {@link LocalPostgres}. Each
 * {@link #newDatabase()} is a fresh, empty database on it. PostgreSQL 17 with pgvector comes from
 * {@code TOOLKIT_TEST_PG_HOME}, else from {@code pg_config} on the PATH, else Homebrew's postgresql@17.
 */
public final class TestPostgres {
    static final String PREFIX = "toolkit-testpg-";
    static final String SUPERUSER = "test";
    static final String INSTALL_HINT = "Install PostgreSQL 17 with pgvector and point TOOLKIT_TEST_PG_HOME at it"
            + " (the folder holding bin/pg_ctl). macOS: brew install postgresql@17 pgvector."
            + " Debian/Ubuntu (PGDG): apt install postgresql-17 postgresql-17-pgvector, TOOLKIT_TEST_PG_HOME=/usr/lib/postgresql/17."
            + " Windows: unzip the EDB PostgreSQL 17 binaries with a pgvector build.";

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static volatile TestPostgres instance;

    private final LocalPostgres server;
    private final String password;

    private TestPostgres(LocalPostgres server, String password) {
        this.server = server;
        this.password = password;
    }

    /** The JVM's server, started on first use and stopped (its files deleted) when the JVM exits. */
    public static TestPostgres get() {
        TestPostgres t = instance;
        if (t == null) {
            synchronized (TestPostgres.class) {
                t = instance;
                if (t == null) {
                    instance = t = start();
                }
            }
        }
        return t;
    }

    /** A new, uniquely named, empty database owned by the superuser (as the Testcontainers image gave us). */
    public static Database newDatabase() {
        TestPostgres t = get();
        String name = "t" + ProcessHandle.current().pid() + "_" + SEQ.incrementAndGet();
        t.server.createDatabase(name);
        return new Database(t.server.jdbcUrl(name), SUPERUSER, t.password);
    }

    public LocalPostgres server() {
        return server;
    }

    public record Database(String jdbcUrl, String username, String password) {
        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }

    private static TestPostgres start() {
        Path home = pgHome();
        sweepOrphans(home);
        try {
            Path root = Files.createTempDirectory(PREFIX);
            Files.writeString(root.resolve("owner.pid"), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            String password = HexFormat.of().formatHex(b);
            LocalPostgres pg = new LocalPostgres(home, root.resolve("data"), freePort(), root.resolve("logs").resolve("postgres.log"))
                    // Each Spring context in the JVM keeps its own pool open against the one server.
                    .setting("max_connections", "300")
                    .setting("fsync", "off")
                    .setting("synchronous_commit", "off")
                    .setting("full_page_writes", "off");
            pg.init(SUPERUSER, password);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.stop();
                } finally {
                    deleteTree(root);
                }
            }, "test-postgres-stop"));
            pg.start(Duration.ofSeconds(60));
            return new TestPostgres(pg, password);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create the test PostgreSQL directory", e);
        }
    }

    /** Stops servers left by test JVMs that were killed before their shutdown hook ran, and deletes their files. */
    static void sweepOrphans(Path home) {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> dirs = Files.list(tmp)) {
            for (Path d : dirs.filter(p -> p.getFileName().toString().startsWith(PREFIX)).toList()) {
                Path owner = d.resolve("owner.pid");
                long pid;
                try {
                    pid = Long.parseLong(Files.readString(owner).trim());
                } catch (IOException | NumberFormatException e) {
                    continue; // not ours, or still being created
                }
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
                try {
                    new LocalPostgres(home, d.resolve("data"), 1).stop();
                } catch (RuntimeException ignored) {
                    // best effort
                }
                deleteTree(d);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** The PostgreSQL installation tests use; fails with install instructions when there is none. */
    public static Path pgHome() {
        String env = System.getenv("TOOLKIT_TEST_PG_HOME");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            if (!usable(p)) {
                throw new IllegalStateException("TOOLKIT_TEST_PG_HOME=" + env + " has no bin/pg_ctl and bin/initdb. " + INSTALL_HINT);
            }
            return p;
        }
        Path fromPgConfig = pgConfigHome();
        if (fromPgConfig != null && usable(fromPgConfig)) {
            return fromPgConfig;
        }
        Path brew = Path.of("/opt/homebrew/opt/postgresql@17");
        if (usable(brew)) {
            return brew;
        }
        throw new IllegalStateException("No PostgreSQL found for the tests. " + INSTALL_HINT);
    }

    private static boolean usable(Path home) {
        LocalPostgres probe = new LocalPostgres(home, home.resolve("unused"), 1);
        return Files.isRegularFile(probe.binary("pg_ctl")) && Files.isRegularFile(probe.binary("initdb"));
    }

    private static Path pgConfigHome() {
        try {
            Process p = new ProcessBuilder(List.of("pg_config", "--bindir")).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() != 0 || out.isEmpty()) {
                return null;
            }
            Path bin = Path.of(out.lines().reduce((a, b) -> b).orElse(out));
            return bin.getParent();
        } catch (IOException e) {
            return null; // not on the PATH
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** A port nobody listens on right now, on 127.0.0.1. */
    public static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return s.getLocalPort();
        }
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // temp files; the OS cleans them eventually
        }
    }
}
