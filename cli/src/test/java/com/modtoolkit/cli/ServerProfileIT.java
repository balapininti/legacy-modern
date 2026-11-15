package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.core.testdb.TestPostgres;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The one HTTPS process an install runs: web UI, REST API and MCP on one port, from the real jar. */
class ServerProfileIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path JAR = REPO.resolve("cli/target/toolkit.jar");
    static final ObjectMapper OM = new ObjectMapper();
    static final String PASSWORD = "changeit-test";
    static final String ADMIN_PASSWORD = "first-run-secret";
    static final String ISSUER = "https://idp.example.com:9443/realms/toolkit";
    static final String INDEX = "<!doctype html><html><body>toolkit-ui-index</body></html>";

    static TestPostgres.Database pg;
    static Path tls, keystore, ui;
    static Process server;
    static int port;
    static String devToken;
    static SSLContext trust;
    static HttpClient h1, h2;

    static Map<String, String> dbEnv() {
        Map<String, String> e = new HashMap<>();
        e.put("TOOLKIT_DB_URL", pg.getJdbcUrl());
        e.put("TOOLKIT_DB_USER", pg.getUsername());
        e.put("TOOLKIT_DB_PASSWORD", pg.getPassword());
        return e;
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static String toolkit(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java(), "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD);
        b.environment().putAll(dbEnv());
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(String.join(" ", args)).isZero();
        return out.strip();
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** A self-signed certificate for localhost, made with the running JDK's keytool as `toolkit setup` will. */
    static void makeKeystore() throws Exception {
        Path keytool = Path.of(ProcessHandle.current().info().command().orElseThrow()).resolveSibling("keytool");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "toolkit", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("toolkit", ks.getCertificate("toolkit"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trusted);
        trust = SSLContext.getInstance("TLS");
        trust.init(null, tmf.getTrustManagers(), null);
    }

    static Process startServer(int p, Map<String, String> extra, String log) throws Exception {
        ProcessBuilder b = new ProcessBuilder(java(), "-jar", JAR.toString(), "serve").redirectErrorStream(true).redirectOutput(new File(REPO.resolve("cli/target/" + log).toString()));
        b.environment().putAll(dbEnv());
        b.environment().put("TOOLKIT_HTTPS_PORT", String.valueOf(p));
        b.environment().putAll(extra);
        Process proc = b.start();
        for (int i = 0; i < 240 && proc.isAlive(); i++) {
            try {
                if (h1.send(HttpRequest.newBuilder(URI.create("https://localhost:" + p + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200) return proc;
            } catch (java.io.IOException e) {
                Thread.sleep(500);
            }
        }
        throw new AssertionError("server did not start, see cli/target/" + log);
    }

    @BeforeAll
    static void up() throws Exception {
        assumeTrue(Files.exists(JAR), "package the jar first");
        pg = TestPostgres.newDatabase();
        // A space in the path, as under C:\Program Files.
        tls = Files.createTempDirectory("tls").resolve("tls dir");
        Files.createDirectories(tls);
        keystore = tls.resolve("toolkit.p12");
        makeKeystore();
        ui = Files.createTempDirectory("ui").resolve("ui dir");
        Files.createDirectories(ui.resolve("assets"));
        Files.writeString(ui.resolve("index.html"), INDEX);
        Files.writeString(ui.resolve("assets/x.js"), "console.log('asset');");
        h1 = HttpClient.newBuilder().sslContext(trust).version(HttpClient.Version.HTTP_1_1).build();
        h2 = HttpClient.newBuilder().sslContext(trust).version(HttpClient.Version.HTTP_2).build();
        devToken = last(toolkit("token", "--user", "dev1"));
        port = freePort();
        server = startServer(port, Map.of("TOOLKIT_TLS_KEYSTORE", keystore.toString(), "TOOLKIT_TLS_PASSWORD", PASSWORD,
                "TOOLKIT_UI_DIR", ui.toString(), "TOOLKIT_ADMIN_PASSWORD", ADMIN_PASSWORD, "TOOLKIT_OIDC_ISSUER", ISSUER), "server-it.log");
    }

    @AfterAll
    static void down() {
        if (server != null) server.destroy();
    }

    static String last(String s) {
        return s.substring(s.lastIndexOf('\n') + 1);
    }

    static String base() {
        return "https://localhost:" + port;
    }

    static HttpResponse<String> get(String path, String token, String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
        if (accept != null) b.header("Accept", accept);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return h1.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final String BROWSER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @Test
    void theUiAndItsRoutesNeedNoToken() throws Exception {
        for (String path : List.of("/", "/review", "/programs/CBACT04C")) {
            HttpResponse<String> r = get(path, null, BROWSER);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(r.body()).as(path).isEqualTo(INDEX);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        }
        assertThat(get("/review", null, null).body()).as("a route without an extension, any Accept").isEqualTo(INDEX);
        HttpResponse<String> asset = get("/assets/x.js", null, "*/*");
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.body()).isEqualTo("console.log('asset');");
        assertThat(asset.headers().firstValue("Content-Type").orElse("")).contains("javascript");
        assertThat(get("/assets/missing.js", null, "*/*").statusCode()).as("a missing asset is not the index").isEqualTo(404);
    }

    @Test
    void theApiMcpAndMetricsStillNeedAToken() throws Exception {
        HttpResponse<String> me = get("/api/auth/me", null, null);
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(OM.readTree(me.body()).get("error").get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(get("/api/auth/me", null, BROWSER).statusCode()).as("a browser asking for an API path gets no index").isEqualTo(401);
        assertThat(get("/api/no-such-thing", devToken, BROWSER).statusCode()).isEqualTo(404);
        assertThat(get("/api/no-such-thing", devToken, BROWSER).body()).doesNotContain("toolkit-ui-index");
        assertThat(get("/actuator/prometheus", null, null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus", devToken, null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null, null).statusCode()).isEqualTo(200);
        assertThat(get("/api/auth/config", null, null).statusCode()).isEqualTo(200);

        HttpResponse<String> mcp = h1.send(HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initialize())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mcp.statusCode()).isEqualTo(401);
        assertThat(get("/mcp", null, BROWSER).statusCode()).as("a browser on /mcp gets no index").isEqualTo(401);
    }

    static String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}";
    }

    static McpSyncClient mcp(HttpClient.Version v) {
        var t = HttpClientStreamableHttpTransport.builder(base()).endpoint("/mcp").clientBuilder(HttpClient.newBuilder().sslContext(trust).version(v))
                .customizeRequest(r -> r.header("Authorization", "Bearer " + devToken)).build();
        McpSyncClient c = McpClient.sync(t).requestTimeout(Duration.ofSeconds(60)).build();
        c.initialize();
        return c;
    }

    @Test
    void mcpListsEveryToolOverHttp2AndHttp11() throws Exception {
        for (HttpClient.Version v : List.of(HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_1_1)) {
            McpSyncClient c = mcp(v);
            try {
                List<McpSchema.Tool> tools = c.listTools().tools();
                assertThat(tools).as(v.toString()).hasSize(19);
                assertThat(tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet())).isEqualTo(ContractTools.EXPECTED);
            } finally {
                c.closeGracefully();
            }
        }
        HttpResponse<String> r = h2.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.version()).as("HTTP/2 is negotiated over TLS").isEqualTo(HttpClient.Version.HTTP_2);
    }

    @Test
    void headersMatchWhatTheProxySet() throws Exception {
        for (String path : List.of("/", "/assets/x.js", "/api/auth/me", "/actuator/health")) {
            HttpResponse<String> r = get(path, null, BROWSER);
            assertThat(r.headers().firstValue("Strict-Transport-Security")).as(path).hasValue("max-age=31536000");
            assertThat(r.headers().firstValue("X-Content-Type-Options")).as(path).hasValue("nosniff");
            assertThat(r.headers().firstValue("Content-Security-Policy")).as(path)
                    .hasValue("default-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data:; connect-src 'self' https://idp.example.com:9443");
        }
    }

    static HttpResponse<String> login(HttpRequest.BodyPublisher body) throws Exception {
        return h1.send(HttpRequest.newBuilder(URI.create(base() + "/api/auth/login")).header("Content-Type", "application/json").POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bodiesOverTwoMegabytesAreRejected() throws Exception {
        String big = "{\"username\":\"admin\",\"password\":\"" + "x".repeat(3 * 1024 * 1024) + "\"}";
        assertThat(login(HttpRequest.BodyPublishers.ofString(big)).statusCode()).as("with a length").isEqualTo(413);
        byte[] bytes = big.getBytes(StandardCharsets.UTF_8);
        assertThat(login(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))).statusCode()).as("chunked").isEqualTo(413);
        String fine = "{\"username\":\"admin\",\"password\":\"" + "x".repeat(1024 * 1024) + "\"}";
        assertThat(login(HttpRequest.BodyPublishers.ofString(fine)).statusCode()).as("one megabyte is read").isEqualTo(401);
        byte[] fineBytes = fine.getBytes(StandardCharsets.UTF_8);
        assertThat(login(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(fineBytes))).statusCode()).as("one megabyte chunked").isEqualTo(401);
    }

    @Test
    void theAdminIsBootstrappedOnce() throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM app_user WHERE username = 'admin'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        HttpResponse<String> r = login(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode me = OM.readTree(get("/api/auth/me", OM.readTree(r.body()).get("token").asText(), null).body());
        assertThat(me.get("roles").toString()).contains("admin");
    }

    @Test
    void aPemCertificateAndKeyWorkToo() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        Base64.Encoder b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Path cert = tls.resolve("tls.crt");
        Path key = tls.resolve("tls.key");
        Files.writeString(cert, "-----BEGIN CERTIFICATE-----\n" + b64.encodeToString(ks.getCertificate("toolkit").getEncoded()) + "\n-----END CERTIFICATE-----\n");
        PrivateKey pk = (PrivateKey) ks.getKey("toolkit", PASSWORD.toCharArray());
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + b64.encodeToString(pk.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        int p = freePort();
        Process pem = startServer(p, Map.of("TOOLKIT_TLS_CERT", cert.toString(), "TOOLKIT_TLS_KEY", key.toString()), "server-pem-it.log");
        try {
            assertThat(h1.send(HttpRequest.newBuilder(URI.create("https://localhost:" + p + "/api/auth/config")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(h1.send(HttpRequest.newBuilder(URI.create("https://localhost:" + p + "/")).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as("no UI folder: nothing to serve, but the server runs").isEqualTo(404);
        } finally {
            pem.destroy();
        }
    }

    @Test
    void withoutACertificateServeRefusesToStart() throws Exception {
        ProcessBuilder b = new ProcessBuilder(java(), "-jar", JAR.toString(), "serve").redirectErrorStream(true);
        b.environment().putAll(dbEnv());
        b.environment().put("TOOLKIT_HTTPS_PORT", String.valueOf(freePort()));
        b.environment().remove("TOOLKIT_TLS_KEYSTORE");
        b.environment().remove("TOOLKIT_TLS_CERT");
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).isNotZero();
        assertThat(out).contains("TOOLKIT_TLS_KEYSTORE");
    }
}
