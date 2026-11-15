package com.modtoolkit.core.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A PostgreSQL server the toolkit owns: a data directory, started and stopped with {@code pg_ctl} from a portable
 * installation ({@code pgHome/bin}). It runs as the current, unprivileged user, listens on 127.0.0.1 only, and accepts
 * scram-sha-256 password logins only. The same class runs the client's database on Windows and the tests' database.
 */
public final class LocalPostgres {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    /** Our settings live in their own file, included from postgresql.conf, so each start can rewrite them. */
    static final String TOOLKIT_CONF = "toolkit.conf";

    private final Path pgHome;
    private final Path dataDir;
    private final int port;
    private final Path logFile;
    private final Map<String, String> settings = new LinkedHashMap<>();
    private String superuser;
    private String password;

    /** The log goes to {@code <dataDir's parent>/logs/postgres.log}. */
    public LocalPostgres(Path pgHome, Path dataDir, int port) {
        this(pgHome, dataDir, port, dataDir.toAbsolutePath().normalize().resolveSibling("logs").resolve("postgres.log"));
    }

    public LocalPostgres(Path pgHome, Path dataDir, int port, Path logFile) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1-65535: " + port);
        }
        this.pgHome = pgHome.toAbsolutePath().normalize();
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.port = port;
        this.logFile = logFile.toAbsolutePath().normalize();
    }

    /** The superuser createRole and createDatabase log in as; init sets it, a later process sets it here. */
    public LocalPostgres superuser(String user, String password) {
        this.superuser = Objects.requireNonNull(user);
        this.password = Objects.requireNonNull(password);
        return this;
    }

    /** An extra postgresql.conf setting (for example max_connections) written on each start. Value is quoted. */
    public LocalPostgres setting(String name, String value) {
        if (!name.matches("[a-z_][a-z0-9_.]*")) {
            throw new IllegalArgumentException("bad setting name: " + name);
        }
        settings.put(name, value);
        return this;
    }

    public int port() {
        return port;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path logFile() {
        return logFile;
    }

    public Path binary(String name) {
        return pgHome.resolve("bin").resolve(WINDOWS ? name + ".exe" : name);
    }

    public String jdbcUrl(String db) {
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + java.net.URLEncoder.encode(db, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public boolean initialized() {
        return Files.isRegularFile(dataDir.resolve("PG_VERSION"));
    }

    /** Creates the cluster: UTF8, C locale (no OS locale needed on any platform), scram for every connection. */
    public void init(String superuser, String password) {
        if (initialized()) {
            throw new IllegalStateException("PostgreSQL data directory is already initialised: " + dataDir);
        }
        requireBinary("initdb");
        Path pwfile = null;
        try {
            Files.createDirectories(dataDir.getParent());
            pwfile = secretFile(password);
            Result r = run(Duration.ofMinutes(5), binary("initdb").toString(), "-D", dataDir.toString(), "-U", superuser,
                    "--auth=scram-sha-256", "--pwfile=" + pwfile, "-E", "UTF8", "--no-locale", "--no-instructions");
            if (r.exit != 0) {
                throw new IllegalStateException("initdb failed (exit " + r.exit + ") for " + dataDir + ":\n" + r.output);
            }
            Path conf = dataDir.resolve("postgresql.conf");
            Files.writeString(conf, "\n# Toolkit settings (rewritten on each start)\ninclude_if_exists = '" + TOOLKIT_CONF + "'\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            writeToolkitConf();
        } catch (IOException e) {
            throw new UncheckedIOException("could not initialise PostgreSQL in " + dataDir, e);
        } finally {
            if (pwfile != null) {
                try {
                    Files.deleteIfExists(pwfile);
                } catch (IOException ignored) {
                    // a temp file in our own temp dir; nothing more to do
                }
            }
        }
        superuser(superuser, password);
    }

    /** True when our postmaster is alive for this data directory. */
    public boolean running() {
        if (!initialized() || !Files.exists(dataDir.resolve("postmaster.pid")) || stalePidFile()) {
            return false;
        }
        return run(Duration.ofSeconds(30), binary("pg_ctl").toString(), "-D", dataDir.toString(), "status").exit == 0;
    }

    /** The postmaster's process id from postmaster.pid, when the file is there. */
    public Optional<Long> postmasterPid() {
        try {
            List<String> lines = Files.readAllLines(dataDir.resolve("postmaster.pid"), StandardCharsets.UTF_8);
            return lines.isEmpty() ? Optional.empty() : Optional.of(Long.parseLong(lines.get(0).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Starts the server and waits until it accepts connections. Returns at once when it already runs. A postmaster.pid
     * left by a killed server is removed first: pg_ctl and postgres cope with a dead process id themselves, but when the
     * id has been reused by another live process, pg_ctl status reports "running" and postgres refuses to start.
     */
    public void start(Duration timeout) {
        if (!initialized()) {
            throw new IllegalStateException("PostgreSQL data directory is not initialised: " + dataDir);
        }
        requireBinary("pg_ctl");
        if (running()) {
            return;
        }
        Path pidFile = dataDir.resolve("postmaster.pid");
        try {
            if (Files.exists(pidFile) && stalePidFile()) {
                Files.deleteIfExists(pidFile);
            }
            if (!portFree(port)) {
                throw new IllegalStateException("port " + port + " on 127.0.0.1 is already in use by another program;"
                        + " stop it or choose another port for the toolkit database");
            }
            writeToolkitConf();
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("could not prepare PostgreSQL start in " + dataDir, e);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long secs = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
            long logStart = logSize();
            Result r = run(Duration.ofSeconds(secs + 30), binary("pg_ctl").toString(), "-D", dataDir.toString(),
                    "-l", logFile.toString(), "-w", "-t", Long.toString(secs), "start");
            if (r.exit == 0) {
                return;
            }
            String log = logSince(logStart);
            // After a kill, the dead postmaster's children can hold its shared memory for a moment; they exit on their own.
            boolean transientShm = log.contains("pre-existing shared memory block");
            if (transientShm && System.nanoTime() < deadline) {
                sleep(500);
                if (Files.exists(pidFile) && stalePidFile()) {
                    try {
                        Files.deleteIfExists(pidFile);
                    } catch (IOException ignored) {
                        // retried next round
                    }
                }
                continue;
            }
            throw new IllegalStateException("PostgreSQL did not start on port " + port + " (pg_ctl exit " + r.exit + "):\n"
                    + r.output + "\nlog " + logFile + ":\n" + log);
        }
    }

    /** Fast shutdown (rolls back open transactions), waiting until the server is gone. No-op when it is not running. */
    public void stop() {
        if (!running()) {
            return;
        }
        Result r = run(Duration.ofMinutes(2), binary("pg_ctl").toString(), "-D", dataDir.toString(), "stop", "-m", "fast", "-w", "-t", "60");
        if (r.exit != 0 && running()) {
            throw new IllegalStateException("PostgreSQL did not stop (pg_ctl exit " + r.exit + "):\n" + r.output);
        }
    }

    public void createRole(String name, String rolePassword) {
        sql("CREATE ROLE " + ident(name) + " LOGIN PASSWORD " + literal(rolePassword));
    }

    public void createDatabase(String name) {
        sql("CREATE DATABASE " + ident(name) + " TEMPLATE template0 ENCODING 'UTF8'");
    }

    public void createDatabase(String name, String owner) {
        sql("CREATE DATABASE " + ident(name) + " OWNER " + ident(owner) + " TEMPLATE template0 ENCODING 'UTF8'");
    }

    private void sql(String statement) {
        if (superuser == null) {
            throw new IllegalStateException("superuser credentials are not set; call superuser(user, password)");
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl("postgres"), superuser, password); Statement s = c.createStatement()) {
            s.execute(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL statement failed on port " + port + ": " + e.getMessage(), e);
        }
    }

    static String ident(String s) {
        if (s.isEmpty() || s.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("bad identifier");
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** Assumes standard_conforming_strings (the default since 9.1), so only quotes need doubling. */
    static String literal(String s) {
        if (s.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("bad literal");
        }
        return "'" + s.replace("'", "''") + "'";
    }

    private void writeToolkitConf() throws IOException {
        StringBuilder b = new StringBuilder("# Written by the toolkit on each start; edits are lost.\n")
                .append("listen_addresses = '127.0.0.1'\n")
                .append("port = ").append(port).append('\n')
                // No Unix socket: TCP on loopback is the only way in, on every platform.
                .append("unix_socket_directories = ''\n");
        settings.forEach((k, v) -> b.append(k).append(" = ").append(literal(v)).append('\n'));
        Files.writeString(dataDir.resolve(TOOLKIT_CONF), b.toString(), StandardCharsets.UTF_8);
    }

    /** postmaster.pid names a process that is gone, or one that is not a postgres (its id was reused). */
    private boolean stalePidFile() {
        Optional<Long> pid = postmasterPid();
        if (pid.isEmpty()) {
            return Files.exists(dataDir.resolve("postmaster.pid"));
        }
        Optional<ProcessHandle> h = ProcessHandle.of(pid.get());
        if (h.isEmpty() || !h.get().isAlive()) {
            return true;
        }
        return h.get().info().command()
                .map(c -> !Path.of(c).getFileName().toString().toLowerCase(Locale.ROOT).startsWith("postgres"))
                .orElse(false);
    }

    static boolean portFree(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return false;
        } catch (IOException connectFailed) {
            // nothing answers; now make sure we could bind it ourselves
        }
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void requireBinary(String name) {
        if (!Files.isRegularFile(binary(name))) {
            throw new IllegalStateException("PostgreSQL program not found: " + binary(name) + " (PostgreSQL home " + pgHome + ")");
        }
    }

    private Path secretFile(String secret) throws IOException {
        Path f = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile("toolkit-pw", ".txt", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile("toolkit-pw", ".txt");
        Files.writeString(f, secret + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private long logSize() {
        try {
            return Files.exists(logFile) ? Files.size(logFile) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** The log written since {@code offset}, at most its last 30 lines. */
    private String logSince(long offset) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(logFile.toFile(), "r")) {
            long from = Math.min(offset, f.length());
            byte[] b = new byte[(int) Math.min(f.length() - from, 1 << 20)];
            f.seek(f.length() - b.length);
            f.readFully(b);
            List<String> lines = new String(b, Charset.defaultCharset()).lines().toList();
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
        } catch (IOException e) {
            return "(no log at " + logFile + ")";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    record Result(int exit, String output) {}

    /** Runs a program with an argument list (no shell), draining its merged output on a separate thread. */
    static Result run(Duration timeout, String... command) {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(List.of(command))).redirectErrorStream(true);
        // English tool messages; the server's own log language comes from lc_messages ('C' after initdb --no-locale).
        pb.environment().put("LC_MESSAGES", "C");
        pb.environment().remove("PGDATA");
        pb.environment().remove("PGPORT");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not run " + command[0], e);
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // stdin is unused
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // the process went away
            }
        }, "pg-output");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("timed out after " + timeout + ": " + String.join(" ", command));
            }
            // A server child may inherit the pipe on some platforms; do not wait for it to close.
            drain.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IllegalStateException("interrupted running " + command[0], e);
        }
        return new Result(p.exitValue(), out.toString(Charset.defaultCharset()).strip());
    }
}
