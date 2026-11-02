package com.modtoolkit.api;

import com.modtoolkit.core.security.TokenService;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A local admin for first setup, and OIDC against the client's identity provider once it is configured. */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication
public class AuthController {
    public record Login(String username, String password) {}

    private final JdbcTemplate jdbc;
    private final TokenService tokens;
    private final Environment env;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AuthController(JdbcTemplate jdbc, TokenService tokens, Environment env) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.env = env;
    }

    /** The first-run admin takes its password from the environment, so no default password ever ships. */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAdmin() {
        String pw = env.getProperty("TOOLKIT_ADMIN_PASSWORD");
        if (pw == null || pw.isBlank()) return;
        tokens.ensureUser("admin", List.of("admin"));
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE username = 'admin' AND password_hash IS NULL", bcrypt.encode(pw));
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oidc_issuer", env.getProperty("toolkit.oidc.issuer", ""));
        m.put("oidc_client_id", env.getProperty("TOOLKIT_OIDC_CLIENT", ""));
        return m;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Login l) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT password_hash FROM app_user WHERE username = ? AND enabled AND password_hash IS NOT NULL", l.username());
        // The same answer for an unknown user and a wrong password.
        if (rows.isEmpty() || l.password() == null || !bcrypt.matches(l.password(), (String) rows.get(0).get("password_hash"))) {
            return ApiErrors.body(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "wrong username or password", "check them and try again");
        }
        return ResponseEntity.ok(Map.of("token", tokens.issue(l.username(), List.of(), "ui-session"), "username", l.username()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication a, Principal p) {
        return Map.of("username", p.getName(), "roles", a.getAuthorities().stream().map(GrantedAuthority::getAuthority).map(r -> r.replaceFirst("^ROLE_", "")).toList());
    }
}
