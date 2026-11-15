package com.modtoolkit.cli.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * An install folder: the Windows bundle's layout ({@code toolkit.cmd}, {@code lib/toolkit.jar}, {@code ui/},
 * {@code config/}, {@code runtime/{jre,pgsql,node}/}, {@code tools/ng-extract/}, {@code data/{pg,tls,logs}/}).
 * The {@code .cmd} files name it with {@code -DTOOLKIT_HOME}; without that property or variable there is no Home and
 * every command runs as a developer runs it.
 */
public record Home(Path root) {
    public static final String VAR = "TOOLKIT_HOME";
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    public Home {
        root = root.toAbsolutePath().normalize();
    }

    /** The system property wins over the environment variable; blank counts as unset. */
    public static Optional<Home> detect(Map<String, String> env, String property) {
        for (String v : new String[] {property, env.get(VAR)}) {
            if (v != null && !v.isBlank()) return Optional.of(new Home(Path.of(v.strip())));
        }
        return Optional.empty();
    }

    public static Optional<Home> detect() {
        return detect(System.getenv(), System.getProperty(VAR));
    }

    public Path config() {
        return root.resolve("config").resolve("toolkit.yaml");
    }

    public Path envFile() {
        return root.resolve("config").resolve("toolkit.env");
    }

    public Path data() {
        return root.resolve("data");
    }

    public Path pgData() {
        return data().resolve("pg");
    }

    public Path keystore() {
        return data().resolve("tls").resolve("toolkit.p12");
    }

    public Path logs() {
        return data().resolve("logs");
    }

    public Path log() {
        return logs().resolve("toolkit.log");
    }

    public Path pgLog() {
        return logs().resolve("postgres.log");
    }

    public Path pidFile() {
        return data().resolve("toolkit.pid");
    }

    public Path ui() {
        return root.resolve("ui");
    }

    /** True once setup has made the database cluster. */
    public boolean installed() {
        return Files.isRegularFile(pgData().resolve("PG_VERSION"));
    }

    /** {@code runtime/pgsql} unless TOOLKIT_PG_HOME names another installation (a developer's Homebrew one). */
    public Path pgHome(Map<String, String> env) {
        String v = env.get("TOOLKIT_PG_HOME");
        return v != null && !v.isBlank() ? Path.of(v.strip()).toAbsolutePath().normalize() : root.resolve("runtime").resolve("pgsql");
    }

    /** The bundled {@code runtime/jre}, else the Java running now. */
    public Path java() {
        Path bundled = root.resolve("runtime").resolve("jre").resolve("bin").resolve(exe("java"));
        return Files.isRegularFile(bundled) ? bundled : currentJava();
    }

    public Path keytool() {
        return java().resolveSibling(exe("keytool"));
    }

    /** The bundled toolkit jar, else the jar this JVM was started from (a developer's build). */
    public Path jar() {
        Path bundled = root.resolve("lib").resolve("toolkit.jar");
        if (Files.isRegularFile(bundled)) return bundled;
        String cp = System.getProperty("java.class.path", "");
        if (cp.endsWith(".jar") && !cp.contains(java.io.File.pathSeparator)) return Path.of(cp).toAbsolutePath();
        throw new IllegalStateException("no toolkit jar: " + bundled + " is missing");
    }

    public Optional<Path> node() {
        Path p = root.resolve("runtime").resolve("node").resolve(exe("node"));
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    public Optional<Path> ngExtract() {
        Path p = root.resolve("tools").resolve("ng-extract").resolve("extract.js");
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    static Path currentJava() {
        return ProcessHandle.current().info().command().map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", exe("java")));
    }

    static String exe(String name) {
        return WINDOWS ? name + ".exe" : name;
    }
}
