package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The REST API from outside the real jar: roles per the Module 7 table, OIDC with really signed tokens, and data. */
class ApiIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path JAR = REPO.resolve("cli/target/toolkit.jar");
    static final Path CORPUS = REPO.resolve(".cache/corpora/carddemo");
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static final ObjectMapper OM = new ObjectMapper();
    static Process api;
    static HttpServer idp;
    static int port;
    static String issuer, admin, dev, sme;
    static RSAKey key;
    static Path config;

    static Map<String, String> env() {
        return Map.of("TOOLKIT_DB_URL", pg.getJdbcUrl(), "TOOLKIT_DB_USER", pg.getUsername(), "TOOLKIT_DB_PASSWORD", pg.getPassword());
    }

    static String toolkit(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java(), "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD);
        b.environment().putAll(env());
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).isZero();
        return out.strip();
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @BeforeAll
    static void up() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS.resolve("app/cbl")) && Files.exists(JAR));
        pg.start();
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        idp = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuer = "http://localhost:" + idp.getAddress().getPort() + "/realm";
        String meta = OM.writeValueAsString(Map.of("issuer", issuer, "jwks_uri", issuer + "/jwks", "authorization_endpoint", issuer + "/auth", "token_endpoint", issuer + "/token", "subject_types_supported", List.of("public")));
        idp.createContext("/realm/.well-known/openid-configuration", x -> { x.getResponseHeaders().add("Content-Type", "application/json"); byte[] b = meta.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        idp.createContext("/realm/jwks", x -> { x.getResponseHeaders().add("Content-Type", "application/json"); byte[] b = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        idp.start();
        toolkit("discover", "--root", CORPUS.toString(), "--threads", "8");
        toolkit("index");
        dev = last(toolkit("token", "--user", "dev1"));
        sme = last(toolkit("token", "--user", "sme1", "--role", "sme-reviewer"));
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        config = Files.createTempDirectory("cfg").resolve("toolkit.yaml");
        Files.writeString(config, "client: t\nsource:\n  root: " + CORPUS + "\n");
        ProcessBuilder b = new ProcessBuilder(java(), "-jar", JAR.toString(), "serve").redirectErrorStream(true).redirectOutput(new File(REPO.resolve("cli/target/api2-it.log").toString()));
        b.environment().putAll(env());
        b.environment().put("TOOLKIT_API_PORT", String.valueOf(port));
        b.environment().put("TOOLKIT_ADMIN_PASSWORD", "first-run-secret");
        b.environment().put("TOOLKIT_OIDC_ISSUER", issuer);
        b.environment().put("TOOLKIT_CONFIG", config.toString());
        api = b.start();
        for (int i = 0; i < 120; i++) {
            try {
                if (send("GET", "/actuator/health", null, null).statusCode() == 200) break;
            } catch (java.io.IOException e) {
                Thread.sleep(500);
            }
        }
        JsonNode login = OM.readTree(send("POST", "/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"first-run-secret\"}").body());
        admin = login.get("token").asText();
    }

    static String last(String s) {
        return s.substring(s.lastIndexOf('\n') + 1);
    }

    @AfterAll
    static void down() {
        if (api != null) api.destroy();
        if (idp != null) idp.stop(0);
    }

    static HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode get(String path, String token) throws Exception {
        HttpResponse<String> r = send("GET", path, token, null);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
        return OM.readTree(r.body());
    }

    @Test
    void firstRunAdminLoginIsSafe() throws Exception {
        assertThat(send("POST", "/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"wrong\"}").statusCode()).isEqualTo(401);
        HttpResponse<String> unknown = send("POST", "/api/auth/login", null, "{\"username\":\"nobody\",\"password\":\"wrong\"}");
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(unknown.body()).isEqualTo(send("POST", "/api/auth/login", null, "{\"username\":\"admin\",\"password\":\"wrong\"}").body());
        assertThat(get("/api/auth/me", admin).get("roles").toString()).contains("admin");
    }

    private String jwt(String issuerUsed, RSAKey signer, List<String> roles, long ttlMs) throws Exception {
        SignedJWT j = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), new JWTClaimsSet.Builder().issuer(issuerUsed).subject("u1").claim("preferred_username", "oidc.user").claim("roles", roles).expirationTime(new Date(System.currentTimeMillis() + ttlMs)).build());
        j.sign(new RSASSASigner(signer));
        return j.serialize();
    }

    @Test
    void oidcTokensFromTheConfiguredIssuerWorkAndOthersDoNot() throws Exception {
        String ok = jwt(issuer, key, List.of("developer", "not-a-role"), 60_000);
        JsonNode me = get("/api/auth/me", ok);
        assertThat(me.get("username").asText()).isEqualTo("oidc.user");
        assertThat(me.get("roles").toString()).contains("developer").doesNotContain("not-a-role");
        assertThat(send("GET", "/api/admin/users", ok, null).statusCode()).as("a developer role grants no admin").isEqualTo(403);
        assertThat(send("GET", "/api/auth/me", jwt(issuer, key, List.of(), -60_000 * 5), null).statusCode()).as("expired").isEqualTo(401);
        assertThat(send("GET", "/api/auth/me", jwt("http://evil.example/realm", key, List.of("admin"), 60_000), null).statusCode()).as("wrong issuer").isEqualTo(401);
        RSAKey other = new RSAKeyGenerator(2048).keyID("k1").generate();
        assertThat(send("GET", "/api/auth/me", jwt(issuer, other, List.of("admin"), 60_000), null).statusCode()).as("wrong signature").isEqualTo(401);
        assertThat(get("/api/auth/config", null).get("oidc_issuer").asText()).isEqualTo(issuer);
    }

    /** The role column of the Module 7 table. */
    @Test
    void rolesMatchTheSpecTable() throws Exception {
        for (String path : List.of("/api/inventory", "/api/programs", "/api/tracker", "/api/graph/calls?program=CBSTM03B", "/api/artifacts", "/api/links", "/api/deviations", "/api/gap-report", "/api/captures")) {
            assertThat(send("GET", path, null, null).statusCode()).as("anonymous " + path).isEqualTo(401);
            assertThat(send("GET", path, sme, null).statusCode()).as("sme " + path).isEqualTo(200);
            assertThat(send("GET", path, dev, null).statusCode()).as("dev " + path).isEqualTo(200);
        }
        for (String path : List.of("/api/admin/users", "/api/admin/audit", "/api/admin/llm-runs", "/api/admin/tokens", "/api/admin/config", "/api/admin/jobs")) {
            assertThat(send("GET", path, dev, null).statusCode()).as("dev " + path).isEqualTo(403);
            assertThat(send("GET", path, sme, null).statusCode()).as("sme " + path).isEqualTo(403);
            assertThat(send("GET", path, admin, null).statusCode()).as("admin " + path).isEqualTo(200);
        }
        assertThat(send("POST", "/api/artifacts/1/status", dev, "{\"status\":\"reviewed\"}").statusCode()).as("review is for SMEs").isEqualTo(403);
        assertThat(send("POST", "/api/artifacts/999999/status", sme, "{\"status\":\"reviewed\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/artifacts/999999/status", sme, "{\"status\":\"bogus\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/tracker/CBSTM03A/stage", dev, "{\"stage\":\"sme_signed_off\"}").statusCode()).as("sign-off is the SME's").isEqualTo(403);
        assertThat(send("POST", "/api/tracker/CBSTM03A/stage", sme, "{\"stage\":\"sme_signed_off\"}").statusCode()).isEqualTo(200);
    }

    @Test
    void pagesGetRealData() throws Exception {
        JsonNode inv = get("/api/inventory", dev);
        assertThat(inv.get("programs").asInt()).isEqualTo(44);
        assertThat(inv.get("dead_code").get(0).get("program_id").asText()).isEqualTo("CBTRN01C");
        JsonNode p = get("/api/programs/cbstm03a", dev);
        assertThat(p.get("source").get(350).asText()).contains("CALL 'CBSTM03B'");
        assertThat(p.get("callees").toString()).contains("CBSTM03B");
        assertThat(p.get("paragraphs").size()).isPositive();
        assertThat(send("GET", "/api/programs/NOSUCH", dev, null).statusCode()).isEqualTo(404);
        JsonNode g = get("/api/graph/calls?program=CBSTM03B&depth=2", dev);
        assertThat(g.get("nodes").toString()).contains("CBSTM03A");
        assertThat(g.get("edges").get(0).get("line").asInt()).isPositive();
        JsonNode j = get("/api/graph/job?job=POSTTRAN", dev);
        assertThat(j.get("nodes").toString()).contains("CBTRN02C").contains("DALYTRAN");
        JsonNode l = get("/api/graph/lineage?item=WS-M03B-AREA&program=CBSTM03A", dev);
        assertThat(l.get("edges").toString()).contains("LK-M03B-AREA");
        assertThat(get("/api/search?q=CDEMO-MENU-OPT-PGMNAME", dev).get("hits").size()).isPositive();
        assertThat(get("/api/tracker", dev).get("programs").size()).isEqualTo(44);
        assertThat(get("/api/gap-report?program=CBSTM03A", dev).get(0).get("status").asText()).isEqualTo("missing");
        JsonNode docs = get("/v3/api-docs", null);
        assertThat(docs.get("paths").has("/api/inventory")).isTrue();
        assertThat(docs.get("paths").has("/api/links/{id}/confirm")).isTrue();
    }

    @Test
    void anSmeApprovedRuleRanksAboveTheSameRuleUnreviewed() throws Exception {
        long a, b;
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO knowledge_artifact (unit_kind, unit_id, kind, content, model, prompt_version, content_hash, member_id, line_start, line_end) SELECT 'program', p.id, 'rule', '{\"rules\":[]}', 'm', 'v', 'h1', p.member_id, 10, 12 FROM program p WHERE p.program_id='CBSTM03A'");
            s.executeUpdate("INSERT INTO knowledge_artifact (unit_kind, unit_id, kind, content, model, prompt_version, content_hash, member_id, line_start, line_end) SELECT 'program', p.id, 'rule', '{\"rules\":[]}', 'm', 'v', 'h2', p.member_id, 20, 22 FROM program p WHERE p.program_id='CBSTM03A'");
            var r = s.executeQuery("SELECT id FROM knowledge_artifact WHERE content_hash IN ('h1','h2') ORDER BY id");
            r.next();
            a = r.getLong(1);
            r.next();
            b = r.getLong(1);
            String text = "zebrawidget surcharge rule";
            s.executeUpdate("INSERT INTO chunk (member_id, artifact_id, kind, line_start, line_end, text, tsv, metadata) SELECT member_id, " + a + ", 'rule', 10, 12, '" + text + "', cobol_tsvector('" + text + "'), '{\"unit\":\"R-A\"}' FROM knowledge_artifact WHERE id=" + a);
            s.executeUpdate("INSERT INTO chunk (member_id, artifact_id, kind, line_start, line_end, text, tsv, metadata) SELECT member_id, " + b + ", 'rule', 20, 22, '" + text + "', cobol_tsvector('" + text + "'), '{\"unit\":\"R-B\"}' FROM knowledge_artifact WHERE id=" + b);
        }
        JsonNode before = get("/api/search?q=zebrawidget&kinds=rule", dev).get("hits");
        assertThat(before.get(0).get("artifactId").asLong()).as("ties break by line").isEqualTo(a);
        assertThat(send("POST", "/api/artifacts/" + b + "/status", sme, "{\"status\":\"reviewed\"}").statusCode()).isEqualTo(200);
        JsonNode after = get("/api/search?q=zebrawidget&kinds=rule", dev).get("hits");
        assertThat(after.get(0).get("artifactId").asLong()).isEqualTo(b);
        assertThat(after.get(0).get("status").asText()).isEqualTo("reviewed");
        // A rejected artifact leaves retrieval altogether.
        assertThat(send("POST", "/api/artifacts/" + a + "/status", sme, "{\"status\":\"rejected\"}").statusCode()).isEqualTo(200);
        assertThat(get("/api/search?q=zebrawidget&kinds=rule", dev).get("hits").size()).isEqualTo(1);
    }

    @Test
    void adminManagesTokensUsersConfigAndJobs() throws Exception {
        assertThat(send("POST", "/api/admin/users", admin, "{\"username\":\"newdev\",\"roles\":[\"developer\"]}").statusCode()).isEqualTo(200);
        assertThat(get("/api/admin/users", admin).toString()).contains("newdev");
        String t = OM.readTree(send("POST", "/api/admin/tokens", admin, "{\"username\":\"newdev\",\"name\":\"laptop\"}").body()).get("token").asText();
        assertThat(get("/api/auth/me", t).get("username").asText()).isEqualTo("newdev");
        long id = get("/api/admin/tokens", admin).get(0).get("id").asLong();
        assertThat(send("DELETE", "/api/admin/tokens/" + id, admin, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/auth/me", t, null).statusCode()).as("revoked at once").isEqualTo(401);
        assertThat(get("/api/admin/tokens", admin).toString()).doesNotContain(t);

        JsonNode doc = OM.readTree(send("POST", "/api/admin/config/doctor", admin, OM.writeValueAsString(Map.of("yaml", "client: c\nsource:\n  root: /nope\n"))).body());
        assertThat(doc.get("ok").asBoolean()).isFalse();
        assertThat(doc.get("checks").toString()).contains("not a directory");
        assertThat(send("PUT", "/api/admin/config", admin, OM.writeValueAsString(Map.of("yaml", "client: c\nsource:\n  encoding: klingon\n"))).statusCode()).isEqualTo(400);
        assertThat(Files.readString(config)).as("a bad config is not saved").contains("client: t");
        assertThat(send("PUT", "/api/admin/config", admin, OM.writeValueAsString(Map.of("yaml", "client: saved\nsource:\n  root: " + CORPUS + "\n"))).statusCode()).isEqualTo(200);
        assertThat(get("/api/admin/config", admin).get("yaml").asText()).contains("client: saved");

        assertThat(send("POST", "/api/admin/jobs/nonsense", admin, null).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/admin/jobs/index", admin, null).statusCode()).isEqualTo(202);
        for (int i = 0; i < 60 && !get("/api/admin/jobs", admin).get("running").isNull(); i++) Thread.sleep(500);
        assertThat(get("/api/admin/jobs", admin).get("running").isNull()).isTrue();
    }
}
