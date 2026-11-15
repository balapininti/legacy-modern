package com.modtoolkit.cli.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.core.testdb.TestPostgres;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The install flow a Windows client runs through the .cmd files, on this machine: the real jar under an install
 * folder whose path has a space, with a native PostgreSQL 17 (TOOLKIT_PG_HOME) standing in for runtime/pgsql.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LauncherIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path JAR = REPO.resolve("cli/target/toolkit.jar");
    static final Path LOG = REPO.resolve("cli/target/launcher-it.log");

    static Path home, source;
    static int https, pgPort;
    static String adminPassword, token;
    static Path pgHome;

    record Run(int exit, String out) {
        String last() {
            String s = out.strip();
            return s.substring(s.lastIndexOf('\n') + 1);
        }
    }

    /** As toolkit.cmd runs it: -DTOOLKIT_HOME, -jar lib/toolkit.jar, with no TOOLKIT_* variable from the developer's shell. */
    static ProcessBuilder command(String... args) {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-DTOOLKIT_HOME=" + home + "/.", "-jar", home.resolve("lib/toolkit.jar").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(cmd);
        b.environment().keySet().removeIf(k -> k.startsWith("TOOLKIT_"));
        b.environment().put("TOOLKIT_PG_HOME", pgHome.toString());
        return b;
    }

    static Run toolkit(String... args) throws Exception {
        Process p = command(args).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(5, TimeUnit.MINUTES)).as(String.join(" ", args)).isTrue();
        Files.writeString(LOG, "$ toolkit " + String.join(" ", args) + " -> " + p.exitValue() + "\n" + out + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        return new Run(p.exitValue(), out);
    }

    static Run ok(String... args) throws Exception {
        Run r = toolkit(args);
        assertThat(r.exit()).as("toolkit " + String.join(" ", args) + ":\n" + r.out()).isZero();
        return r;
    }

    @BeforeAll
    static void install() throws Exception {
        assumeTrue(Files.exists(JAR), "package the jar first");
        pgHome = TestPostgres.pgHome();
        home = Files.createTempDirectory("launcher").resolve("Tool Kit");
        Files.createDirectories(home.resolve("lib"));
        Files.createSymbolicLink(home.resolve("lib/toolkit.jar"), JAR);
        source = Files.createTempDirectory("launcher").resolve("main frame src");
        Files.createDirectories(source.resolve("app/cbl"));
        Files.writeString(source.resolve("app/cbl/HELLO.cbl"), "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. HELLO.\n");
        https = TestPostgres.freePort();
        pgPort = TestPostgres.freePort();
        Files.deleteIfExists(LOG);
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (home == null) return;
        try {
            toolkit("stop");
        } finally {
            ourProcesses().forEach(ProcessHandle::destroyForcibly);
            SetupCommand.deleteTree(home.getParent());
            SetupCommand.deleteTree(source.getParent());
        }
    }

    @Test
    @Order(1)
    void setupWritesConfigSecretsCertificateAndDatabase() throws Exception {
        Run r = ok("setup", "--source", source.toString(), "--client", "acme", "--port", Integer.toString(https), "--pg-port", Integer.toString(pgPort));
        assertThat(r.out()).contains("admin / ").contains("start.cmd");
        adminPassword = r.out().lines().filter(l -> l.contains("admin / ")).findFirst().orElseThrow().replaceAll(".*admin / ", "").strip();

        Map<String, String> env = EnvFile.read(home.resolve("config/toolkit.env"));
        assertThat(env).containsEntry("TOOLKIT_ADMIN_PASSWORD", adminPassword).containsEntry("TOOLKIT_HTTPS_PORT", Integer.toString(https))
                .containsEntry("TOOLKIT_PG_PORT", Integer.toString(pgPort)).containsEntry("TOOLKIT_DB_USER", "toolkit")
                .containsKeys("TOOLKIT_DB_PASSWORD", "TOOLKIT_TLS_PASSWORD", "TOOLKIT_PG_SUPERUSER_PASSWORD", "TOOLKIT_DB_URL");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("config/toolkit.env")))).isEqualTo("rw-------");
        assertThat(ToolkitConfig.load(home.resolve("config/toolkit.yaml")).source().root()).isEqualTo(source.toString());
        assertThat(home.resolve("data/tls/toolkit.p12")).isRegularFile();
        assertThat(home.resolve("data/pg/PG_VERSION")).isRegularFile();
        assertThat(portFree(pgPort)).as("setup leaves the database stopped").isTrue();

        Run again = toolkit("setup", "--source", source.toString());
        assertThat(again.exit()).isEqualTo(2);
        assertThat(again.out()).contains("already set up").contains("--force");
    }

    @Test
    @Order(2)
    void startServesHealthOverTlsAndSaysWhereAndHowToStop() throws Exception {
        Run r = ok("start");
        assertThat(r.out()).contains("toolkit is running: https://").contains(":" + https + "/").contains("toolkit stop");
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(home.resolve("data/toolkit.pid")).isRegularFile();
        assertThat(home.resolve("data/logs/toolkit.log")).isRegularFile();

        Run twice = ok("start");
        assertThat(twice.out()).contains("already running");
    }

    @Test
    @Order(3)
    void theAdminFromSetupCanLogIn() throws Exception {
        HttpResponse<String> r = client().send(HttpRequest.newBuilder(URI.create(base() + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"" + adminPassword + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    @Test
    @Order(4)
    void doctorThroughTheLauncherUsesTheInstallsDatabaseAndConfig() throws Exception {
        Run r = ok("doctor");
        assertThat(r.out()).containsPattern("PASS\\s+database").containsPattern("PASS\\s+source\\s+.*main frame src").containsPattern("PASS\\s+config");
    }

    @Test
    @Order(5)
    void aTokenFromTheLauncherOpensMcpOverTls() throws Exception {
        token = ok("token", "--user", "dev1").last();
        assertThat(token).isNotBlank().doesNotContain(" ");
        assertThat(mcpTools(token)).isEqualTo(19);
    }

    @Test
    @Order(6)
    void stopLeavesNothingRunningAndIsIdempotent() throws Exception {
        long serverPid = Long.parseLong(Files.readAllLines(home.resolve("data/toolkit.pid")).get(0).strip());
        Run r = ok("stop");
        assertThat(r.out()).contains("stopping the server").contains("stopping the database");
        assertThat(ProcessHandle.of(serverPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertNothingRuns();
        assertThat(ok("stop").out()).contains("nothing was running");
    }

    @Test
    @Order(7)
    void startAgainKeepsTheData() throws Exception {
        ok("start");
        assertThat(mcpTools(token)).as("the token issued before the restart").isEqualTo(19);
    }

    @Test
    @Order(8)
    void startRecoversAfterTheServerAndDatabaseWereKilled() throws Exception {
        long serverPid = Long.parseLong(Files.readAllLines(home.resolve("data/toolkit.pid")).get(0).strip());
        long postmaster = Long.parseLong(Files.readAllLines(home.resolve("data/pg/postmaster.pid")).get(0).strip());
        for (long pid : new long[] {serverPid, postmaster}) {
            ProcessHandle h = ProcessHandle.of(pid).orElseThrow();
            h.destroyForcibly();
            h.onExit().get(30, TimeUnit.SECONDS);
        }
        assertThat(home.resolve("data/toolkit.pid")).as("a stale pid file").isRegularFile();
        assertThat(home.resolve("data/pg/postmaster.pid")).as("a stale postmaster.pid").isRegularFile();
        ok("start");
        assertThat(mcpTools(token)).isEqualTo(19);
        ok("stop");
        assertNothingRuns();
    }

    @Test
    @Order(9)
    void startFailsNamingTheHttpsPortWhenItIsTaken() throws Exception {
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(https));
            Run r = toolkit("start");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.out()).contains("port " + https).contains("TOOLKIT_HTTPS_PORT");
        }
        assertNothingRuns();
    }

    @Test
    @Order(10)
    void inTheForegroundEndingTheLauncherStopsTheServerAndTheDatabase() throws Exception {
        Path log = REPO.resolve("cli/target/launcher-it-foreground.log");
        Files.deleteIfExists(log);
        Process launcher = command("start", "--foreground").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            for (int i = 0; i < 240 && launcher.isAlive() && !Files.readString(log).contains("toolkit is running"); i++) Thread.sleep(500);
            assertThat(Files.readString(log)).contains("toolkit is running");
            assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
            assertThat(home.resolve("data/toolkit.pid")).as("so toolkit stop can end a service").isRegularFile();
            // What a service manager or Ctrl+C does: end the launcher, whose shutdown hook stops the rest.
            launcher.toHandle().destroy();
            assertThat(launcher.waitFor(2, TimeUnit.MINUTES)).isTrue();
        } finally {
            launcher.destroyForcibly();
        }
        assertNothingRuns();
        assertThat(home.resolve("data/toolkit.pid")).doesNotExist();
    }

    @Test
    @Order(11)
    void setupForceStartsOverWithNewSecrets() throws Exception {
        Path target = Files.createDirectories(source.resolveSibling("modern app"));
        String before = EnvFile.read(home.resolve("config/toolkit.env")).get("TOOLKIT_DB_PASSWORD");
        ok("setup", "--source", source.toString(), "--target", target.toString(), "--force", "--port", Integer.toString(https), "--pg-port", Integer.toString(pgPort));
        assertThat(EnvFile.read(home.resolve("config/toolkit.env")).get("TOOLKIT_DB_PASSWORD")).isNotEqualTo(before);
        assertThat(ToolkitConfig.load(home.resolve("config/toolkit.yaml")).target().localPath()).isEqualTo(target.toString());
        assertNothingRuns();
    }

    // ---- helpers ----

    static void assertNothingRuns() throws Exception {
        for (int i = 0; i < 40 && !ourProcesses().isEmpty(); i++) Thread.sleep(250);
        assertThat(ourProcesses().stream().map(h -> h.pid() + " " + h.info().commandLine().orElse("?")).toList()).as("processes of the install").isEmpty();
        assertThat(portFree(https)).as("HTTPS port " + https).isTrue();
        assertThat(portFree(pgPort)).as("database port " + pgPort).isTrue();
    }

    /** Every process whose command line names the install folder: the server JVM, postgres and its workers. */
    static List<ProcessHandle> ourProcesses() {
        String root = home.toString();
        return ProcessHandle.allProcesses().filter(h -> h.pid() != ProcessHandle.current().pid())
                .filter(h -> h.info().commandLine().map(c -> c.contains(root)).orElse(false)).toList();
    }

    static boolean portFree(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    static String base() {
        return "https://localhost:" + https;
    }

    static SSLContext trust() throws Exception {
        String pw = EnvFile.read(home.resolve("config/toolkit.env")).get("TOOLKIT_TLS_PASSWORD");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(home.resolve("data/tls/toolkit.p12"))) {
            ks.load(in, pw.toCharArray());
        }
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("toolkit", ks.getCertificate("toolkit"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trusted);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    static HttpClient client() throws Exception {
        return HttpClient.newBuilder().sslContext(trust()).build();
    }

    static HttpResponse<String> get(String path) throws Exception {
        return client().send(HttpRequest.newBuilder(URI.create(base() + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static int mcpTools(String bearer) throws Exception {
        var t = HttpClientStreamableHttpTransport.builder(base()).endpoint("/mcp").clientBuilder(HttpClient.newBuilder().sslContext(trust()))
                .customizeRequest(r -> r.header("Authorization", "Bearer " + bearer)).build();
        McpSyncClient c = McpClient.sync(t).requestTimeout(Duration.ofSeconds(60)).build();
        try {
            c.initialize();
            return c.listTools().tools().size();
        } finally {
            c.closeGracefully();
        }
    }
}
