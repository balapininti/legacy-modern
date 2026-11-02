package com.modtoolkit.api;

import com.modtoolkit.core.config.ConfigLoadException;
import com.modtoolkit.core.config.ConfigValidator;
import com.modtoolkit.core.config.Doctor;
import com.modtoolkit.core.config.ToolkitConfig;
import com.modtoolkit.core.migration.MigrationService;
import com.modtoolkit.core.security.TokenService;
import com.modtoolkit.discovery.DiscoverOptions;
import com.modtoolkit.discovery.DiscoveryService;
import com.modtoolkit.retrieval.ArtifactChunks;
import com.modtoolkit.retrieval.ChunkIndexer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Setup wizard and Admin page: everything here is admin only. */
@RestController
@RequestMapping("/api/admin")
@ConditionalOnWebApplication
@PreAuthorize("hasRole('admin')")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    public record NewUser(String username, List<String> roles) {}
    public record NewToken(String username, String name) {}
    public record Yaml(String yaml) {}

    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final DiscoveryService discovery;
    private final ChunkIndexer indexer;
    private final ArtifactChunks artifactChunks;
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private volatile String running;

    public AdminController(JdbcTemplate jdbc, TokenService tokens, DiscoveryService discovery, ChunkIndexer indexer, ArtifactChunks artifactChunks) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.discovery = discovery;
        this.indexer = indexer;
        this.artifactChunks = artifactChunks;
    }

    private static Path configPath() {
        return Path.of(System.getenv().getOrDefault("TOOLKIT_CONFIG", "toolkit.yaml"));
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() throws Exception {
        Path p = configPath();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", p.toString());
        m.put("yaml", Files.exists(p) ? Files.readString(p) : "");
        return m;
    }

    /** Validates what was typed, without saving it. Doctor's live checks run against the same text. */
    @PostMapping("/config/doctor")
    public Map<String, Object> doctor(@RequestBody Yaml y) throws Exception {
        Path tmp = Files.createTempFile("toolkit", ".yaml");
        try {
            Files.writeString(tmp, y.yaml());
            List<Doctor.CheckResult> r = Doctor.fromEnvironment().runChecked(tmp);
            return Map.of("ok", Doctor.ok(r), "checks", r);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Yaml y) throws Exception {
        Path tmp = Files.createTempFile("toolkit", ".yaml");
        try {
            Files.writeString(tmp, y.yaml());
            var issues = ConfigValidator.validate(ToolkitConfig.load(tmp));
            if (!issues.isEmpty()) return ApiErrors.body(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", issues.get(0).path() + " " + issues.get(0).message(), issues.size() + " problems; run doctor to see them all");
        } catch (ConfigLoadException e) {
            return ApiErrors.body(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", e.getMessage(), "fix the yaml");
        } finally {
            Files.deleteIfExists(tmp);
        }
        Files.writeString(configPath(), y.yaml());
        return ResponseEntity.ok(Map.of("saved", configPath().toString()));
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return jdbc.queryForList("""
                SELECT u.id, u.username, u.enabled, coalesce((SELECT string_agg(r.name, ',' ORDER BY r.name) FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = u.id), '') AS roles
                FROM app_user u ORDER BY u.username""");
    }

    @PostMapping("/users")
    public Map<String, Object> addUser(@RequestBody NewUser u) {
        long id = tokens.ensureUser(u.username(), u.roles());
        return Map.of("id", id, "username", u.username());
    }

    @PutMapping("/users/{id}/roles")
    public Map<String, Object> setRoles(@PathVariable long id, @RequestBody List<String> roles) {
        String name = jdbc.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, id);
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", id);
        tokens.ensureUser(name, roles);
        return Map.of("id", id, "roles", roles);
    }

    @GetMapping("/tokens")
    public List<Map<String, Object>> listTokens() {
        return jdbc.queryForList("SELECT t.id, u.username, t.name, t.created_at::text AS created_at, t.last_used_at::text AS last_used_at, t.revoked_at::text AS revoked_at FROM api_token t JOIN app_user u ON u.id = t.user_id ORDER BY t.id DESC");
    }

    /** Shown once; only a hash is kept. */
    @PostMapping("/tokens")
    public Map<String, Object> newToken(@RequestBody NewToken t) {
        return Map.of("token", tokens.issue(t.username(), List.of(), t.name() == null ? "mcp" : t.name()));
    }

    @DeleteMapping("/tokens/{id}")
    public Map<String, Object> revoke(@PathVariable long id) {
        if (tokens.revoke(id) == 0) throw new MigrationService.NotFound("no live token " + id);
        return Map.of("id", id, "revoked", true);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "200") int limit, @RequestParam(defaultValue = "0") int offset) {
        return jdbc.queryForList("SELECT ts::text AS ts, \"user\", action, tool, params_hash, latency_ms, outcome FROM audit_log ORDER BY id DESC LIMIT ? OFFSET ?", Math.min(limit, 1000), Math.max(offset, 0));
    }

    @GetMapping("/llm-runs")
    public List<Map<String, Object>> llmRuns() {
        return jdbc.queryForList("SELECT id, model, units, tokens_in, tokens_out, cost_usd, status, started_at::text AS started_at, ended_at::text AS ended_at FROM llm_run ORDER BY id DESC LIMIT 100");
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("recent", jdbc.queryForList("""
                SELECT e.job_execution_id AS id, i.job_name, e.status, e.exit_code, e.start_time::text AS start_time, e.end_time::text AS end_time
                FROM batch_job_execution e JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id ORDER BY e.job_execution_id DESC LIMIT 20"""));
        return m;
    }

    /** One job at a time from the UI. The knowledge pass runs from the CLI, where its cost is a deliberate step. */
    @PostMapping("/jobs/{name}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String name, Principal who) {
        if (!List.of("discover", "index").contains(name)) return ApiErrors.body(HttpStatus.BAD_REQUEST, "BAD_ARGUMENT", "unknown job " + name, "discover or index");
        synchronized (this) {
            if (running != null) return ApiErrors.body(HttpStatus.CONFLICT, "BUSY", running + " is already running", "wait for it to finish");
            running = name;
        }
        jobs.submit(() -> {
            try {
                log.info("{} started {}", who.getName(), name);
                if (name.equals("discover")) {
                    ToolkitConfig c = ToolkitConfig.load(configPath());
                    discovery.run(DiscoverOptions.of(Path.of(c.source().root())).withThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2)));
                } else {
                    indexer.index();
                    artifactChunks.index();
                }
            } catch (Exception e) {
                log.error("job {} failed", name, e);
            } finally {
                running = null;
            }
        });
        return ResponseEntity.accepted().body(Map.of("started", name));
    }
}
