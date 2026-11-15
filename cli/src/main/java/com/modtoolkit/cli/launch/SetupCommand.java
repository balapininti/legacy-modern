package com.modtoolkit.cli.launch;

import com.modtoolkit.core.local.LocalPostgres;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "setup", description = "Prepare this install once: configuration, generated secrets, a TLS certificate and the database.")
public class SetupCommand implements Callable<Integer> {
    static final SecureRandom RANDOM = new SecureRandom();
    static final Pattern HOSTNAME = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    @Option(names = "--source", required = true, description = "Folder holding the mainframe source (a git checkout or a PDS export)")
    Path source;

    @Option(names = "--client", defaultValue = "client", description = "Client name, as reports show it (default: ${DEFAULT-VALUE})")
    String client;

    @Option(names = "--port", defaultValue = "8443", description = "HTTPS port of the web UI, REST API and MCP (default: ${DEFAULT-VALUE})")
    int port;

    @Option(names = "--pg-port", defaultValue = "55432", description = "Port of the toolkit's database on 127.0.0.1 (default: ${DEFAULT-VALUE})")
    int pgPort;

    @Option(names = "--target", description = "Local checkout of the modern (Java and Angular) repository, indexed alongside the source")
    Path target;

    @Option(names = "--force", description = "Set up again: stops the toolkit and DELETES its database and certificate, then writes new configuration and secrets")
    boolean force;

    @Override
    public Integer call() throws Exception {
        Home home = Home.detect().orElse(null);
        if (home == null) {
            System.err.println("toolkit setup: run it from the install folder (toolkit.cmd setup ...); TOOLKIT_HOME is not set");
            return 2;
        }
        List<Path> existing = Stream.of(home.envFile(), home.config(), home.pgData()).filter(Files::exists).toList();
        if (!existing.isEmpty() && !force) {
            System.err.println("toolkit setup: already set up (found " + existing.get(0) + ")."
                    + " To start over, pass --force: it deletes the toolkit's database and certificate.");
            return 2;
        }
        if (!Files.isDirectory(source)) {
            System.err.println("toolkit setup: --source " + source + " is not a folder");
            return 2;
        }
        if (target != null && !Files.isDirectory(target)) {
            System.err.println("toolkit setup: --target " + target + " is not a folder");
            return 2;
        }
        for (int p : new int[] {port, pgPort}) {
            if (p < 1 || p > 65535) {
                System.err.println("toolkit setup: " + p + " is not a port number (1-65535)");
                return 2;
            }
        }
        Map<String, String> real = System.getenv();
        if (force) {
            if (!StopCommand.stop(home, Launcher.environment(home, real))) return 1;
            deleteTree(home.pgData());
            deleteTree(home.keystore().getParent());
            Files.deleteIfExists(home.pidFile());
        }
        Path pgHome = home.pgHome(real);
        LocalPostgres pg = new LocalPostgres(pgHome, home.pgData(), pgPort, home.pgLog());
        if (!Files.isRegularFile(pg.binary("initdb")) || !Files.isRegularFile(pg.binary("pg_ctl"))) {
            System.err.println("toolkit setup: PostgreSQL not found in " + pgHome + " (expected bin/initdb and bin/pg_ctl)");
            return 1;
        }

        Map<String, String> env = new LinkedHashMap<>();
        env.put("TOOLKIT_HTTPS_PORT", Integer.toString(port));
        env.put("TOOLKIT_PG_PORT", Integer.toString(pgPort));
        env.put("TOOLKIT_DB_URL", pg.jdbcUrl(Launcher.DB));
        env.put("TOOLKIT_DB_USER", Launcher.DB);
        env.put("TOOLKIT_DB_PASSWORD", secret());
        env.put("TOOLKIT_ADMIN_PASSWORD", secret());
        env.put("TOOLKIT_TLS_PASSWORD", secret());
        env.put(Launcher.PG_SUPERUSER_PASSWORD, secret());

        String host = hostname(real);
        Files.createDirectories(home.config().getParent());
        Files.writeString(home.config(), yaml(client, source.toAbsolutePath().normalize().toString(),
                target == null ? null : target.toAbsolutePath().normalize().toString()), StandardCharsets.UTF_8);
        System.out.println("wrote " + home.config());

        keystore(home, host, env.get("TOOLKIT_TLS_PASSWORD"));
        System.out.println("made a self-signed certificate for " + host + ", localhost and 127.0.0.1: " + home.keystore());

        pg.init(Launcher.PG_SUPERUSER, env.get(Launcher.PG_SUPERUSER_PASSWORD));
        try {
            pg.start(Launcher.PG_START);
            pg.createRole(Launcher.DB, env.get("TOOLKIT_DB_PASSWORD"));
            pg.createDatabase(Launcher.DB, Launcher.DB);
            boolean vector = createVectorExtension(pg, env.get(Launcher.PG_SUPERUSER_PASSWORD));
            Flyway.configure().dataSource(env.get("TOOLKIT_DB_URL"), Launcher.DB, env.get("TOOLKIT_DB_PASSWORD")).load().migrate();
            System.out.println("made the database in " + home.pgData() + (vector ? "" : " (no pgvector: search is lexical only)"));
        } finally {
            pg.stop();
        }

        // Last, so an interrupted setup leaves no secrets behind that match no database.
        EnvFile.write(home.envFile(), env);
        System.out.println("wrote " + home.envFile() + " (readable by you only)");
        System.out.println();
        System.out.println("Web UI login: admin / " + env.get("TOOLKIT_ADMIN_PASSWORD"));
        System.out.println("This is shown once; it is also stored in " + home.envFile() + " as TOOLKIT_ADMIN_PASSWORD.");
        System.out.println();
        System.out.println("Next: start.cmd   (then open https://" + host + ":" + port + "/)");
        return 0;
    }

    /** pgvector is not a trusted extension, so the superuser makes it before the migrations run as the app role. */
    static boolean createVectorExtension(LocalPostgres pg, String superPassword) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.jdbcUrl(Launcher.DB), Launcher.PG_SUPERUSER, superPassword);
                Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT 1 FROM pg_available_extensions WHERE name = 'vector'")) {
                if (!rs.next()) return false;
            }
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            return true;
        }
    }

    static void keystore(Home home, String host, String password) throws Exception {
        Files.createDirectories(home.keystore().getParent());
        String san = "localhost".equals(host) ? "dns:localhost,ip:127.0.0.1" : "dns:" + host + ",dns:localhost,ip:127.0.0.1";
        List<String> cmd = new ArrayList<>(List.of(home.keytool().toString(), "-genkeypair", "-alias", "toolkit", "-keyalg", "RSA", "-keysize", "3072",
                "-validity", "825", "-dname", "CN=" + host, "-ext", "SAN=" + san, "-storetype", "PKCS12", "-keystore", home.keystore().toString(),
                // The password travels in the environment, not on a command line other users can list.
                "-storepass:env", "TOOLKIT_TLS_PASSWORD", "-keypass:env", "TOOLKIT_TLS_PASSWORD", "-noprompt"));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("TOOLKIT_TLS_PASSWORD", password);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("keytool failed (exit " + p.exitValue() + "): " + out.strip());
        EnvFile.restrictToOwner(home.keystore());
    }

    /** The toolkit.yaml setup writes: single-quoted YAML scalars (no escapes), paths with {@code /}, which Java accepts on Windows. */
    static String yaml(String client, String sourceRoot, String targetPath) {
        StringBuilder y = new StringBuilder("""
                # Written by toolkit setup. Edit freely: secrets live in toolkit.env, never here.
                # toolkit.yaml.example next to this file shows every block.
                """);
        y.append("client: ").append(quote(client)).append('\n');
        y.append("source:\n  root: ").append(quote(slashes(sourceRoot))).append('\n');
        y.append("  encoding: auto\n  format: auto\n  dialect: ibm-enterprise\n");
        y.append("zos:\n  enabled: false\n  method: none\n");
        if (targetPath != null) {
            y.append("target:\n  local_path: ").append(quote(slashes(targetPath))).append("\n  index_existing: true\n");
        }
        return y.toString();
    }

    static String slashes(String path) {
        return path.replace('\\', '/');
    }

    static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** 24 random bytes, base64url: 32 characters that need no quoting in toolkit.env, a JDBC URL or a shell. */
    static String secret() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The name clients reach this machine by: COMPUTERNAME on Windows, else the host's name, else localhost. */
    static String hostname(Map<String, String> env) {
        String h = env.get("COMPUTERNAME");
        if (h == null || !HOSTNAME.matcher(h.toLowerCase(Locale.ROOT)).matches()) {
            try {
                h = InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                h = null;
            }
        }
        if (h == null) return "localhost";
        h = h.toLowerCase(Locale.ROOT);
        return HOSTNAME.matcher(h).matches() ? h : "localhost";
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
