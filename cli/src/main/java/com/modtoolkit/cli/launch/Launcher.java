package com.modtoolkit.cli.launch;

import com.modtoolkit.core.local.LocalPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * What the {@code .cmd} entry points run: owns the install's PostgreSQL and the server process, and runs every other
 * command in a child JVM with the install's environment ({@code config/toolkit.env} under real variables).
 */
public final class Launcher {
    /** Set in every child, so it runs its command itself instead of launching again. */
    public static final String CHILD_MARKER = "TOOLKIT_LAUNCHED";
    static final Set<String> OWN = Set.of("setup", "start", "stop");
    /** Never handed to a child: only setup needs the database superuser. */
    static final String PG_SUPERUSER_PASSWORD = "TOOLKIT_PG_SUPERUSER_PASSWORD";
    static final String PG_SUPERUSER = "postgres";
    static final String DB = "toolkit";
    static final int HTTPS_PORT = 8443;
    static final int PG_PORT = 55432;
    static final Duration PG_START = Duration.ofSeconds(120);
    static final long LOG_ROTATE_BYTES = 20L * 1024 * 1024;

    private Launcher() {}

    /** True for an ordinary command in an install that setup has finished, unless we are already the child. */
    public static boolean delegates(Optional<Home> home, String[] args, Map<String, String> env) {
        return home.isPresent() && !env.containsKey(CHILD_MARKER) && home.get().installed()
                && args.length > 0 && !args[0].startsWith("-") && !OWN.contains(args[0]);
    }

    /** Ensures PostgreSQL runs, then runs {@code args} in a child JVM attached to this console; returns its exit code. */
    public static int delegate(Home home, String[] args) {
        Map<String, String> env = environment(home, System.getenv());
        try {
            postgres(home, env).start(PG_START);
        } catch (RuntimeException e) {
            System.err.println("toolkit: the database did not start: " + e.getMessage());
            return 1;
        }
        ProcessBuilder pb = new ProcessBuilder(javaCommand(home, env, home.jar(), args)).inheritIO();
        replaceEnvironment(pb, childEnvironment(env));
        try {
            Process p = pb.start();
            return p.waitFor();
        } catch (IOException e) {
            System.err.println("toolkit: cannot run " + home.java() + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /**
     * Defaults from the layout, under {@code config/toolkit.env}, under the real environment. Defaults are filled in
     * last, from the merged values, so a changed TOOLKIT_PG_PORT also moves the database URL.
     */
    public static Map<String, String> environment(Home home, Map<String, String> real) {
        Map<String, String> env = EnvFile.merge(EnvFile.read(home.envFile()), real);
        env.put(Home.VAR, home.root().toString());
        env.putIfAbsent("TOOLKIT_HTTPS_PORT", Integer.toString(HTTPS_PORT));
        env.putIfAbsent("TOOLKIT_PG_PORT", Integer.toString(PG_PORT));
        env.putIfAbsent("TOOLKIT_DB_URL", "jdbc:postgresql://127.0.0.1:" + env.get("TOOLKIT_PG_PORT").strip() + "/" + DB);
        env.putIfAbsent("TOOLKIT_DB_USER", DB);
        env.putIfAbsent("TOOLKIT_CONFIG", home.config().toString());
        env.putIfAbsent("TOOLKIT_TLS_KEYSTORE", home.keystore().toString());
        home.node().ifPresent(n -> env.putIfAbsent("TOOLKIT_NODE", n.toString()));
        home.ngExtract().ifPresent(n -> env.putIfAbsent("TOOLKIT_NG_EXTRACT", n.toString()));
        return env;
    }

    public static Map<String, String> childEnvironment(Map<String, String> env) {
        Map<String, String> c = new LinkedHashMap<>(env);
        c.remove(PG_SUPERUSER_PASSWORD);
        c.put(CHILD_MARKER, "1");
        return c;
    }

    static void replaceEnvironment(ProcessBuilder pb, Map<String, String> env) {
        pb.environment().clear();
        pb.environment().putAll(env);
    }

    /** {@code java [TOOLKIT_JAVA_OPTS] -jar <jar> args...}, each argument its own element (no shell). */
    public static List<String> javaCommand(Home home, Map<String, String> env, Path jar, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(home.java().toString());
        String opts = env.getOrDefault("TOOLKIT_JAVA_OPTS", "").strip();
        if (!opts.isEmpty()) cmd.addAll(Arrays.asList(opts.split("\\s+")));
        cmd.add("-jar");
        cmd.add(jar.toString());
        Collections.addAll(cmd, args);
        return cmd;
    }

    public static LocalPostgres postgres(Home home, Map<String, String> env) {
        return new LocalPostgres(home.pgHome(env), home.pgData(), port(env, "TOOLKIT_PG_PORT"), home.pgLog());
    }

    static int port(Map<String, String> env, String name) {
        String v = env.get(name);
        try {
            int p = Integer.parseInt(v.strip());
            if (p >= 1 && p <= 65535) return p;
        } catch (RuntimeException ignored) {
            // reported below
        }
        throw new IllegalArgumentException(name + " must be a port number (1-65535), not '" + v + "'");
    }

    /**
     * Nothing answers on {@code port} and we could listen on it on every interface, as the server will. The JDK's
     * default address reuse is kept, as Tomcat keeps it: on Unix it lets a port still in TIME_WAIT from the last run be
     * bound again; on Windows it stays exclusive.
     */
    static boolean portFree(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return false;
        } catch (IOException nothingAnswers) {
            // now try to bind it
        }
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- the server process ----

    /** The pid file names a live process that is the one we started (same start time), not a reused id. */
    static Optional<ProcessHandle> server(Home home) {
        List<String> lines;
        try {
            lines = Files.exists(home.pidFile()) ? Files.readAllLines(home.pidFile(), StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return Optional.empty();
        }
        if (lines.isEmpty()) return Optional.empty();
        long pid;
        try {
            pid = Long.parseLong(lines.get(0).strip());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        Optional<ProcessHandle> h = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        if (h.isEmpty()) return h;
        Optional<Instant> started = h.get().info().startInstant();
        if (lines.size() > 1 && started.isPresent()) {
            try {
                long recorded = Long.parseLong(lines.get(1).strip());
                if (Math.abs(started.get().toEpochMilli() - recorded) > 2000) return Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return h;
    }

    static void writePid(Home home, ProcessHandle h) throws IOException {
        String start = h.info().startInstant().map(i -> Long.toString(i.toEpochMilli())).orElse("");
        Files.writeString(home.pidFile(), h.pid() + "\n" + start + "\n", StandardCharsets.UTF_8);
    }

    /** Graceful first (SIGTERM; on Windows the JDK can only terminate), then forcibly. */
    static void stopProcess(ProcessHandle h, Duration grace) {
        if (!h.isAlive()) return;
        h.destroy();
        try {
            h.onExit().get(grace.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception timedOut) {
            h.destroyForcibly();
            try {
                h.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // reported by the caller's check
            }
        }
    }

    /** Keeps one previous log: toolkit.log becomes toolkit.log.1 when it has grown past 20 MB. */
    static void rotate(Path log) throws IOException {
        Files.createDirectories(log.getParent());
        if (Files.exists(log) && Files.size(log) > LOG_ROTATE_BYTES) {
            Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String tail(Path log, int lines) {
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join(System.lineSeparator(), all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException | UncheckedIOException e) {
            return "(no log at " + log + ")";
        }
    }

    /** {@code /actuator/health} answers 200 over TLS on 127.0.0.1, trusting only the server's own certificate. */
    static boolean healthy(int port, SSLContext trust) {
        try {
            HttpsURLConnection c = (HttpsURLConnection) URI.create("https://127.0.0.1:" + port + "/actuator/health").toURL().openConnection();
            c.setSSLSocketFactory(trust.getSocketFactory());
            // We trust exactly the configured certificate, which may not name 127.0.0.1 (a CA-issued PEM).
            c.setHostnameVerifier((host, session) -> true);
            c.setConnectTimeout(2000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /** Trusts the certificate the server presents: the PEM certificate when set, else the keystore's. */
    static SSLContext trust(Map<String, String> env) throws Exception {
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        String cert = blankToNull(env.get("TOOLKIT_TLS_CERT"));
        if (cert != null) {
            try (InputStream in = Files.newInputStream(Path.of(cert))) {
                int i = 0;
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) trusted.setCertificateEntry("c" + i++, c);
            }
        } else {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            String pw = env.getOrDefault("TOOLKIT_TLS_PASSWORD", "");
            try (InputStream in = Files.newInputStream(Path.of(env.get("TOOLKIT_TLS_KEYSTORE")))) {
                ks.load(in, pw.toCharArray());
            }
            for (String alias : Collections.list(ks.aliases())) {
                Certificate c = ks.getCertificate(alias);
                if (c != null) trusted.setCertificateEntry(alias, c);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trusted);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
