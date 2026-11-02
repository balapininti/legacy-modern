package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Drives the real jar over both transports with the MCP Java client, on the real CardDemo facts. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class McpContractIT {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path JAR = REPO.resolve("cli/target/toolkit.jar");
    static final Path CORPUS = REPO.resolve(".cache/corpora/carddemo");
    static final ObjectMapper OM = new ObjectMapper();
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static Process http;
    static int port;
    static String devToken, smeToken, adminToken;
    static Process api;
    static int apiPort;
    static Path targetRepo;
    static McpSyncClient httpClient, stdioClient;

    static Map<String, String> env() {
        Map<String, String> e = new HashMap<>();
        e.put("TOOLKIT_DB_URL", pg.getJdbcUrl());
        e.put("TOOLKIT_DB_USER", pg.getUsername());
        e.put("TOOLKIT_DB_PASSWORD", pg.getPassword());
        return e;
    }

    static String run(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java(), "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.appendTo(REPO.resolve("cli/target/it-commands.log").toFile()));
        b.environment().putAll(env());
        Process p = b.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(String.join(" ", args)).isZero();
        return out.strip();
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS.resolve("app/cbl")), "run scripts/fetch-corpora.sh first");
        assumeTrue(Files.exists(JAR), "package the jar first");
        pg.start();
        targetRepo = TargetRepoFixture.create(REPO, Files.createTempDirectory("target"));
        run("discover", "--root", CORPUS.toString(), "--threads", "8", "--target", targetRepo.toString());
        run("index");
        adminToken = lastLine(run("token", "--user", "lead1", "--role", "admin"));
        devToken = lastLine(run("token", "--user", "dev1"));
        smeToken = lastLine(run("token", "--user", "sme1", "--role", "sme-reviewer"));
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ProcessBuilder b = new ProcessBuilder(java(), "-jar", JAR.toString(), "mcp", "http").redirectErrorStream(true).redirectOutput(new File(REPO.resolve("cli/target/mcp-it.log").toString()));
        b.environment().putAll(env());
        b.environment().put("TOOLKIT_MCP_PORT", String.valueOf(port));
        http = b.start();
        HttpClient c = HttpClient.newHttpClient();
        for (int i = 0; i < 120; i++) {
            try {
                if (c.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200) break;
            } catch (java.io.IOException e) {
                Thread.sleep(500);
            }
        }
        httpClient = httpClient(devToken);
        try (ServerSocket s = new ServerSocket(0)) {
            apiPort = s.getLocalPort();
        }
        ProcessBuilder ab = new ProcessBuilder(java(), "-jar", JAR.toString(), "serve").redirectErrorStream(true).redirectOutput(new File(REPO.resolve("cli/target/api-it.log").toString()));
        ab.environment().putAll(env());
        ab.environment().put("TOOLKIT_API_PORT", String.valueOf(apiPort));
        api = ab.start();
        for (int i = 0; i < 120; i++) {
            try {
                if (c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200) break;
            } catch (java.io.IOException e) {
                Thread.sleep(500);
            }
        }
        ServerParameters sp = ServerParameters.builder(java()).args("-jar", JAR.toString(), "mcp", "stdio").env(env()).build();
        stdioClient = McpClient.sync(new StdioClientTransport(sp, new JacksonMcpJsonMapper(OM))).requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(120)).build();
        stdioClient.initialize();
    }

    static McpSyncClient httpClient(String token) {
        var t = HttpClientStreamableHttpTransport.builder(base()).endpoint("/mcp").customizeRequest(r -> r.header("Authorization", "Bearer " + token)).build();
        McpSyncClient c = McpClient.sync(t).requestTimeout(Duration.ofSeconds(60)).build();
        c.initialize();
        return c;
    }

    static String base() {
        return "http://localhost:" + port;
    }

    static String lastLine(String s) {
        return s.substring(s.lastIndexOf('\n') + 1);
    }

    @AfterAll
    static void stop() {
        if (httpClient != null) httpClient.closeGracefully();
        if (stdioClient != null) stdioClient.closeGracefully();
        if (http != null) http.destroy();
        if (api != null) api.destroy();
    }

    static McpSyncClient client(String transport) {
        return transport.equals("http") ? httpClient : stdioClient;
    }

    static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    static JsonNode ok(McpSyncClient c, String tool, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(r.isError()).as(tool + " -> " + text(r)).isFalse();
        return OM.readTree(text(r));
    }

    static JsonNode error(McpSyncClient c, String tool, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(r.isError()).as(tool + " should fail: " + text(r)).isTrue();
        JsonNode e = OM.readTree(text(r)).get("error");
        assertThat(e.fieldNames()).toIterable().containsExactlyInAnyOrder("code", "message", "hint");
        assertThat(text(r)).doesNotContain("Exception").doesNotContain("\tat ").doesNotContain("SELECT ");
        return e;
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "stdio"})
    void a_listsEveryToolWithASchema(String t) {
        List<McpSchema.Tool> tools = client(t).listTools().tools();
        Set<String> names = tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
        assertThat(names).containsAll(ContractTools.EXPECTED);
        for (McpSchema.Tool x : tools) {
            assertThat(x.description()).isNotBlank();
            assertThat(x.inputSchema().type()).isEqualTo("object");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "stdio"})
    void b_everyM2ToolAnswersWithProvenance(String t) throws Exception {
        McpSyncClient c = client(t);
        JsonNode inv = ok(c, "inventory_summary", Map.of()).get("items").get(0);
        assertThat(inv.get("programs").asInt()).isEqualTo(44);

        JsonNode who = ok(c, "who_calls", Map.of("program", "CBSTM03B", "depth", 1));
        assertThat(who.get("total_items").asInt()).isEqualTo(13);
        assertThat(who.get("items").get(0).get("member").asText()).isEqualTo("app/cbl/CBSTM03A.CBL");
        assertThat(who.get("items").get(0).get("line").asInt()).isEqualTo(351);
        assertThat(who.get("items").get(0).get("edge_type").asText()).isEqualTo("CALLS");

        JsonNode from = ok(c, "calls_from", Map.of("program", "CBSTM03A"));
        assertThat(from.get("items").findValues("to_program").stream().map(JsonNode::asText).toList()).contains("CBSTM03B");

        JsonNode src = ok(c, "get_source", Map.of("member", "app/cbl/CBSTM03A.CBL", "line_start", 351, "line_end", 351));
        assertThat(src.get("items").get(0).get("text").asText()).contains("CALL 'CBSTM03B'");
        assertThat(src.get("items").get(0).get("original_line").asInt()).isEqualTo(351);

        JsonNode tu = ok(c, "table_usage", Map.of("table", "transaction_type"));
        assertThat(tu.get("items").findValues("stmt_type").stream().map(JsonNode::asText).toList()).contains("SELECT", "UPDATE", "INSERT", "DELETE");
        assertThat(tu.get("items").get(0).has("line_start")).isTrue();

        JsonNode s = ok(c, "search", Map.of("query", "CDEMO-MENU-OPT-PGMNAME", "limit", 5));
        JsonNode hit = s.get("items").get(0);
        assertThat(hit.get("member").asText()).isNotBlank();
        assertThat(hit.get("line_start").asInt()).isPositive();
        assertThat(hit.has("status")).isTrue();
        assertThat(hit.get("snippet").asText()).containsIgnoringCase("CDEMO-MENU-OPT");

        JsonNode partial = ok(c, "search", Map.of("query", "MENU-OPT-PGM", "limit", 5));
        assertThat(partial.get("items")).as("partial identifier through pg_trgm").isNotEmpty();

        JsonNode prog = ok(c, "get_program", Map.of("name", "cotrtupc", "include", List.of("tables", "summary", "callers")));
        assertThat(prog.get("items").get(0).get("value").get("member").asText()).endsWith("COTRTUPC.cbl");
        Map<String, JsonNode> parts = new HashMap<>();
        prog.get("items").forEach(i -> parts.put(i.get("part").asText(), i.get("value")));
        assertThat(parts.get("tables")).isNotEmpty();
        assertThat(parts.get("summary").get("status").asText()).isEqualTo("not_available");

        JsonNode cb = ok(c, "get_copybook", Map.of("name", "CVACT01Y"));
        JsonNode first = cb.get("items").get(0);
        assertThat(first.get("member").asText()).endsWith("CVACT01Y.cpy");
        assertThat(first.get("line").asInt()).isPositive();
        assertThat(first.has("offset") && first.has("length")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "stdio"})
    void b2_flowToolsAnswerOnRealData(String t) throws Exception {
        McpSyncClient c = client(t);
        JsonNode jf = ok(c, "job_flow", Map.of("job", "POSTTRAN"));
        JsonNode step = jf.get("items").get(0);
        assertThat(step.get("pgm").asText()).isEqualTo("CBTRN02C");
        assertThat(step.get("member").asText()).isEqualTo("app/jcl/POSTTRAN.jcl");
        assertThat(step.get("datasets_out").findValues("dsn").stream().map(JsonNode::asText).toList()).contains("AWS.M2.CARDDEMO.DALYREJS");
        assertThat(step.get("datasets_in").findValues("dsn").stream().map(JsonNode::asText).toList()).contains("AWS.M2.CARDDEMO.DALYTRAN.PS");
        assertThat(ok(c, "job_flow", Map.of("program", "CBTRN02C")).get("items").get(0).get("job").asText()).isEqualTo("POSTTRAN");
        assertThat(error(c, "job_flow", Map.of()).get("code").asText()).isEqualTo("BAD_ARGUMENT");

        JsonNode sf = ok(c, "screen_flow", Map.of("map", "COSGN0A")).get("items").get(0);
        assertThat(sf.get("member").asText()).isEqualTo("app/bms/COSGN00.bms");
        assertThat(sf.get("fields").findValues("name").stream().map(JsonNode::asText).toList()).contains("USERID");
        assertThat(sf.get("owning_programs").findValues("program").stream().map(JsonNode::asText).toList()).contains("COSGN00C");
        JsonNode tx = ok(c, "screen_flow", Map.of("transaction", "CC00"));
        assertThat(tx.get("items").get(0).get("programs").toString()).contains("COSGN00C");

        JsonNode trace = ok(c, "trace_field", Map.of("data_item", "WS-M03B-AREA", "program", "CBSTM03A", "direction", "downstream", "depth", 2));
        assertThat(trace.get("items").findValues("to_item").stream().map(JsonNode::asText).toList()).contains("LK-M03B-AREA");
        JsonNode cross = null;
        for (JsonNode i : trace.get("items")) if ("LK-M03B-AREA".equals(i.path("to_item").asText())) cross = i;
        assertThat(cross.get("op").asText()).isEqualTo("CALL USING");
        assertThat(cross.get("to_program").asText()).isEqualTo("CBSTM03B");
        assertThat(cross.get("line").asInt()).isPositive();
        JsonNode up = ok(c, "trace_field", Map.of("data_item", "LK-M03B-AREA", "program", "CBSTM03B", "direction", "upstream"));
        assertThat(up.get("items").findValues("from_item").stream().map(JsonNode::asText).toList()).contains("WS-M03B-AREA");
        assertThat(error(c, "trace_field", Map.of("data_item", "NO-SUCH-ITEM")).get("code").asText()).isEqualTo("NOT_FOUND");
        JsonNode sqlTouch = ok(c, "trace_field", Map.of("data_item", "DCL-TR-TYPE", "program", "COTRTUPC"));
        assertThat(sqlTouch.get("items").findValues("tables").stream().map(JsonNode::asText).toList().toString()).contains("TRANSACTION_TYPE");

        JsonNode who = ok(c, "who_calls", Map.of("program", "CBTRN02C"));
        assertThat(who.get("items").findValues("edge_type").stream().map(JsonNode::asText).toList()).contains("JCL_STEP");
    }

    /** Review Focus 5 over the wire. */
    @ParameterizedTest
    @ValueSource(strings = {"http", "stdio"})
    void c_paginationIsCappedAndLossless(String t) throws Exception {
        McpSyncClient c = client(t);
        List<String> expected = Files.readAllLines(CORPUS.resolve("app/cbl/COACTUPC.cbl"), java.nio.charset.StandardCharsets.ISO_8859_1);
        List<String> got = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> a = new HashMap<>(Map.of("member", "app/cbl/COACTUPC.cbl"));
            if (cursor != null) a.put("cursor", cursor);
            McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest("get_source", a));
            String body = text(r);
            assertThat(body.length()).isLessThanOrEqualTo(20_000);
            JsonNode n = OM.readTree(body);
            n.get("items").forEach(i -> got.add(i.get("text").asText()));
            cursor = n.get("next_cursor").isNull() ? null : n.get("next_cursor").asText();
            pages++;
        } while (cursor != null);
        assertThat(pages).isGreaterThan(1);
        assertThat(got).hasSameSizeAs(expected);
        for (int i = 0; i < got.size(); i++) assertThat(got.get(i)).isEqualTo(expected.get(i).stripTrailing());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "stdio"})
    void d_errorsAreStructured(String t) throws Exception {
        McpSyncClient c = client(t);
        JsonNode nf = error(c, "get_program", Map.of("name", "COSGNOOC"));
        assertThat(nf.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(nf.get("hint").asText()).contains("COSGN00C");
        assertThat(error(c, "get_program", Map.of()).get("code").asText()).isEqualTo("BAD_ARGUMENT");
        assertThat(error(c, "get_source", Map.of("member", "app/cbl/CBSTM03A.CBL", "line_start", 9999999)).get("code").asText()).isEqualTo("OUT_OF_RANGE");
        assertThat(error(c, "get_source", Map.of("member", "app/cbl/CBSTM03A.CBL", "cursor", "garbage!")).get("code").asText()).isEqualTo("BAD_CURSOR");
        assertThat(error(c, "who_calls", Map.of("program", "X' OR 1=1 --")).get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void e_unauthenticatedAndRevokedAreRejected() throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(init));
        assertThat(c.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        HttpRequest.Builder bad = HttpRequest.newBuilder(URI.create(base() + "/mcp")).header("Authorization", "Bearer tk_nope").header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(init));
        HttpResponse<String> r = c.send(bad.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("UNAUTHENTICATED");
        assertThat(c.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void f_roleIsEnforcedAndEveryCallAudited() throws Exception {
        McpSyncClient sme = httpClient(smeToken);
        try {
            assertThat(error(sme, "inventory_summary", Map.of()).get("code").asText()).isEqualTo("FORBIDDEN");
        } finally {
            sme.closeGracefully();
        }
        ok(httpClient, "who_calls", Map.of("program", "CBSTM03B"));
        try (Connection con = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = con.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT outcome, params_hash FROM audit_log WHERE \"user\"='sme1' AND tool='inventory_summary' ORDER BY id DESC LIMIT 1")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("FORBIDDEN");
                assertThat(r.getString(2)).hasSize(64);
            }
            try (ResultSet r = s.executeQuery("SELECT latency_ms FROM audit_log WHERE \"user\"='dev1' AND tool='who_calls' ORDER BY id DESC LIMIT 1")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getLong(1)).isGreaterThanOrEqualTo(0);
            }
            try (ResultSet r = s.executeQuery("SELECT count(*) FROM audit_log WHERE \"user\" LIKE '%@stdio'")) {
                r.next();
                assertThat(r.getLong(1)).as("stdio calls are audited too").isPositive();
            }
        }
    }

    /** Stdout is the stdio protocol: not a banner, not a logger, not a library's chatter. */
    @Test
    void i_cliStdoutHoldsOnlyCommandOutput() throws Exception {
        assertThat(run("index")).startsWith("Stats[");
        String token = run("token", "--user", "stdoutcheck");
        assertThat(token).startsWith("tk_").doesNotContain("\n");
    }

    private static HttpResponse<String> rest(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode findLink(String legacy, String modernContains, String source) throws Exception {
        for (JsonNode l : OM.readTree(rest("GET", "/api/links", adminToken).body())) {
            if (l.get("legacy").asText().equals(legacy) && l.get("modern_name").asText().contains(modernContains) && (source == null || l.get("source").asText().equals(source))) return l;
        }
        throw new AssertionError("no link " + legacy + " -> " + modernContains);
    }

    /** M3b: the whole loop, over real processes, against a repository with real commits. */
    @Test
    void h_targetCodeIsIndexedLinkedConfirmedAndReported() throws Exception {
        JsonNode search = ok(httpClient, "search", Map.of("query", "AccountViewResponse", "kinds", List.of("modern")));
        assertThat(search.get("items").findValues("member").stream().map(JsonNode::asText).toList()).anyMatch(m -> m.endsWith("AccountViewResponse.java"));
        JsonNode ep = ok(httpClient, "search", Map.of("query", "AccountController", "kinds", List.of("modern")));
        assertThat(ep.get("items")).isNotEmpty();

        // Candidates from the three signals. COACTVWC is named in a comment; CBACT04C in a commit message.
        JsonNode comment = findLink("COACTVWC", "AccountService", "name_match");
        assertThat(comment.get("status").asText()).isEqualTo("candidate");
        assertThat(comment.get("confidence").asDouble()).isEqualTo(0.7, org.assertj.core.data.Offset.offset(0.001));
        JsonNode commit = findLink("CBACT04C", "InterestJobConfig", "commit_ref");
        assertThat(commit.get("evidence").asText()).contains("Port CBACT04C");
        assertThat(commit.get("status").asText()).isEqualTo("candidate");

        JsonNode impl = ok(httpClient, "get_modern_impl", Map.of("program", "COACTVWC"));
        JsonNode first = impl.get("items").get(0);
        assertThat(first.get("authoritative").asBoolean()).isFalse();
        assertThat(first.get("status").asText()).isEqualTo("candidate");
        assertThat(first.get("path").asText()).endsWith("AccountService.java");

        // A developer cannot confirm; nobody can without a token.
        assertThat(rest("POST", "/api/links/" + comment.get("id").asLong() + "/confirm", devToken).statusCode()).isEqualTo(403);
        assertThat(rest("POST", "/api/links/" + comment.get("id").asLong() + "/confirm", null).statusCode()).isEqualTo(401);
        assertThat(rest("POST", "/api/links/999999/confirm", adminToken).statusCode()).isEqualTo(404);

        // Before confirming, the gap report says missing; after, implemented, and the program's stage moves.
        assertThat(ok(httpClient, "gap_report", Map.of("scope", "COACTVWC")).get("items").get(0).get("status").asText()).isEqualTo("missing");
        assertThat(rest("POST", "/api/links/" + comment.get("id").asLong() + "/confirm", adminToken).statusCode()).isEqualTo(200);
        JsonNode after = ok(httpClient, "get_modern_impl", Map.of("program", "COACTVWC")).get("items").get(0);
        assertThat(after.get("authoritative").asBoolean()).isTrue();
        JsonNode gap = ok(httpClient, "gap_report", Map.of("scope", "COACTVWC")).get("items").get(0);
        assertThat(gap.get("status").asText()).isEqualTo("implemented");
        assertThat(gap.get("modern_locations").get(0).get("path").asText()).endsWith("AccountService.java");
        try (Connection con = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement st = con.createStatement();
                ResultSet r = st.executeQuery("SELECT m.stage, m.target_refs::text FROM migration_status m JOIN program p ON p.id=m.program_id WHERE p.program_id='COACTVWC'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo("translated");
            assertThat(r.getString(2)).contains("AccountService.java");
        }
        // The gap report lists an uncovered rule once one exists.
        try (Connection con = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement st = con.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO knowledge_artifact (unit_kind, unit_id, kind, content, model, prompt_version, content_hash, member_id)
                    SELECT 'program', p.id, 'rule', '{"rules":[{"rule_id":"R1","statement":"inactive accounts are not shown","line_start":200,"line_end":210}]}'::jsonb, 'test', 'v0', 'x', p.member_id
                    FROM program p WHERE p.program_id='COACTVWC'""");
        }
        JsonNode g2 = ok(httpClient, "gap_report", Map.of("scope", "COACTVWC")).get("items").get(0);
        assertThat(g2.get("status").asText()).isEqualTo("partial");
        assertThat(g2.get("rules_uncovered").asInt()).isEqualTo(1);
        assertThat(g2.get("rules").get(0).get("coverage").asText()).isEqualTo("uncovered");
        assertThat(ok(httpClient, "gap_report", Map.of("scope", "POSTTRAN")).get("items").get(0).get("program").asText()).isEqualTo("CBTRN02C");

        // A rejected link stays rejected when the repository is indexed again, and a confirmed one stays confirmed.
        JsonNode tableLink = findLink("CBACT04C", "InterestJobConfig", null);
        assertThat(rest("POST", "/api/links/" + tableLink.get("id").asLong() + "/reject", adminToken).statusCode()).isEqualTo(200);
        run("discover", "--root", CORPUS.toString(), "--threads", "8", "--target", targetRepo.toString());
        assertThat(findLink("CBACT04C", "InterestJobConfig", null).get("status").asText()).isEqualTo("rejected");
        assertThat(findLink("COACTVWC", "AccountService", null).get("status").asText()).isEqualTo("confirmed");
        assertThat(ok(httpClient, "get_modern_impl", Map.of("program", "CBACT04C")).toString()).doesNotContain("\"name\":\"com.acme.card.batch.InterestJobConfig\"").contains("INTCALC");

        // The deviation register: a developer proposes, it has no effect until a lead approves.
        Map<String, Object> dev = Map.of("legacy_ref", Map.of("program", "COACTVWC"), "modern_ref", Map.of("path", "AccountService.java"), "change", "status is always Y", "reason", "statuses moved to another service",
                "comparison_rule", Map.of("kind", "ignore_field", "field", "ACCT-ACTIVE-STATUS"));
        long id = ok(httpClient, "record_deviation", dev).get("items").get(0).get("id").asLong();
        assertThat(ok(httpClient, "deviations", Map.of("program", "COACTVWC")).get("items")).isEmpty();
        assertThat(rest("POST", "/api/deviations/" + id + "/approve", devToken).statusCode()).isEqualTo(403);
        assertThat(rest("POST", "/api/deviations/" + id + "/approve", adminToken).statusCode()).isEqualTo(200);
        JsonNode d = ok(httpClient, "deviations", Map.of("program", "COACTVWC")).get("items").get(0);
        assertThat(d.get("approved_by").asText()).isEqualTo("lead1");
        assertThat(d.get("comparison_rule").get("field").asText()).isEqualTo("ACCT-ACTIVE-STATUS");
        assertThat(error(httpClient, "record_deviation", Map.of("legacy_ref", Map.of(), "modern_ref", Map.of(), "change", "x", "reason", "y", "comparison_rule", Map.of("kind", "nonsense"))).get("code").asText()).isEqualTo("BAD_ARGUMENT");
    }

    @Test
    void z_serverLogHasNoStackTraces() throws Exception {
        String log = Files.readString(REPO.resolve("cli/target/mcp-it.log"));
        assertThat(log).doesNotContain("AccessDenied").doesNotContain("already committed");
        assertThat(Files.readString(REPO.resolve("cli/target/api-it.log"))).doesNotContain("Exception");
    }
}
