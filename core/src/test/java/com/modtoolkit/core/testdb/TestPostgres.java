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
    private static volatile TestPostgres withoutVector;

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

    /**
     * A second server for the JVM, run from a mirror of the PostgreSQL home whose extension and library folders lack
     * pgvector, so {@code pg_available_extensions} has no {@code vector}: a client's own PostgreSQL without it.
     */
    public static TestPostgres withoutVector() {
        TestPostgres t = withoutVector;
        if (t == null) {
            synchronized (TestPostgres.class) {
                t = withoutVector;
                if (t == null) {
                    try {
                        Path mirror = Files.createTempDirectory(PREFIX + "home-");
                        // An owner lets sweepOrphans delete the mirror if this JVM is killed.
                        Files.writeString(mirror.resolve("owner.pid"), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(mirror), "test-postgres-home-delete"));
                        withoutVector = t = start(homeWithoutVector(pgHome(), mirror));
                    } catch (IOException e) {
                        throw new UncheckedIOException("could not mirror the PostgreSQL home without pgvector", e);
                    }
                }
            }
        }
        return t;
    }

    /** A new, uniquely named, empty database owned by the superuser (as the Testcontainers image gave us). */
    public static Database newDatabase() {
        return get().database();
    }

    /** {@link #newDatabase()} on the server without pgvector. */
    public static Database newDatabaseWithoutVector() {
        return withoutVector().database();
    }

    private Database database() {
        String name = "t" + ProcessHandle.current().pid() + "_" + SEQ.incrementAndGet();
        server.createDatabase(name);
        return new Database(server.jdbcUrl(name), SUPERUSER, password);
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
        return start(pgHome());
    }

    private static TestPostgres start(Path home) {
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

    /**
     * Mirrors {@code home} under {@code into} with every {@code vector*} file left out of the extension and library
     * folders. PostgreSQL finds its share and library folders relative to its own binary, by the same relative path as at
     * build time, so the binaries are copied (a symlink would resolve back to the original) and the folders as reported
     * by {@code pg_config} are recreated at the same place relative to them, their contents linked (or copied where links
     * are not allowed). Returns the mirror's home, the parent of its {@code bin}.
     */
    static Path homeWithoutVector(Path home, Path into) throws IOException {
        Path pgConfig = new LocalPostgres(home, home.resolve("unused"), 1).binary("pg_config");
        if (!Files.isRegularFile(pgConfig)) {
            throw new IllegalStateException(pgConfig + " not found; it is needed to start a PostgreSQL without pgvector. " + INSTALL_HINT);
        }
        List<Path> dirs = pgConfig(pgConfig, "--bindir", "--sharedir", "--pkglibdir");
        Path bin = dirs.get(0), share = dirs.get(1), pkglib = dirs.get(2);
        Path common = bin;
        while (!share.startsWith(common) || !pkglib.startsWith(common)) {
            common = common.getParent();
        }
        Path newBin = into.resolve(common.relativize(bin).toString());
        Files.createDirectories(newBin);
        try (Stream<Path> s = Files.list(bin)) {
            for (Path f : s.toList()) {
                if (Files.isRegularFile(f)) {
                    Files.copy(f, newBin.resolve(f.getFileName().toString()), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        for (Path dir : List.of(share, pkglib)) {
            Path target = into.resolve(common.relativize(dir).toString());
            Files.createDirectories(target);
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.toList()) {
                    String name = f.getFileName().toString();
                    if (name.startsWith("vector")) {
                        continue;
                    }
                    if (dir.equals(share) && name.equals("extension")) {
                        Path ext = Files.createDirectories(target.resolve(name));
                        try (Stream<Path> e = Files.list(f)) {
                            for (Path x : e.toList()) {
                                if (!x.getFileName().toString().startsWith("vector")) {
                                    linkOrCopy(x, ext.resolve(x.getFileName().toString()));
                                }
                            }
                        }
                    } else if (!Files.exists(target.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        linkOrCopy(f, target.resolve(name));
                    }
                }
            }
        }
        return newBin.getParent();
    }

    private static void linkOrCopy(Path from, Path to) throws IOException {
        try {
            Files.createSymbolicLink(to, from);
        } catch (UnsupportedOperationException | IOException e) {
            if (Files.isDirectory(from)) {
                try (Stream<Path> s = Files.walk(from)) {
                    for (Path p : s.toList()) {
                        Path dest = to.resolve(from.relativize(p).toString());
                        if (Files.isDirectory(p)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.copy(p, dest);
                        }
                    }
                }
            } else {
                Files.copy(from, to);
            }
        }
    }

    private static List<Path> pgConfig(Path pgConfig, String... flags) throws IOException {
        List<String> cmd = new java.util.ArrayList<>(List.of(pgConfig.toString()));
        cmd.addAll(List.of(flags));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (p.waitFor() != 0) {
                throw new IllegalStateException(pgConfig + " failed: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        List<Path> dirs = out.lines().map(String::strip).filter(l -> !l.isEmpty()).map(Path::of).toList();
        if (dirs.size() != flags.length) {
            throw new IllegalStateException(pgConfig + " printed " + out);
        }
        return dirs;
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
