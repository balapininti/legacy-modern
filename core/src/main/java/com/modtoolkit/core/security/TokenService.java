package com.modtoolkit.core.security;

import com.modtoolkit.core.Hashes;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Personal MCP bearer tokens. Only a hash is stored, so a database copy holds nothing usable. */
@Service
public class TokenService {
    public record Identity(String username, List<String> roles) {
        public boolean has(String... anyOf) {
            for (String r : anyOf) if (roles.contains(r)) return true;
            return false;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public TokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long ensureUser(String username, List<String> roles) {
        jdbc.update("INSERT INTO app_user (username) VALUES (?) ON CONFLICT (username) DO NOTHING", username);
        long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
        for (String r : roles) {
            int n = jdbc.update("INSERT INTO user_role (user_id, role_id) SELECT ?, id FROM role WHERE name = ? ON CONFLICT DO NOTHING", id, r);
            if (n == 0 && jdbc.queryForObject("SELECT count(*) FROM role WHERE name = ?", Long.class, r) == 0) {
                throw new IllegalArgumentException("unknown role " + r);
            }
        }
        return id;
    }

    /** @return the token, shown once */
    public String issue(String username, List<String> roles, String tokenName) {
        long user = ensureUser(username, roles);
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = "tk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.update("INSERT INTO api_token (user_id, name, token_hash) VALUES (?,?,?)", user, tokenName, Hashes.sha256(token));
        return token;
    }

    public int revoke(long tokenId) {
        return jdbc.update("UPDATE api_token SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", tokenId);
    }

    public Optional<Identity> authenticate(String token) {
        List<Long> users = jdbc.queryForList("SELECT user_id FROM api_token t JOIN app_user u ON u.id = t.user_id WHERE t.token_hash = ? AND t.revoked_at IS NULL AND u.enabled", Long.class, Hashes.sha256(token));
        if (users.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE api_token SET last_used_at = now() WHERE token_hash = ?", Hashes.sha256(token));
        String name = jdbc.queryForObject("SELECT username FROM app_user WHERE id = ?", String.class, users.get(0));
        return Optional.of(new Identity(name, rolesOf(users.get(0))));
    }

    public List<String> rolesOf(long userId) {
        return jdbc.queryForList("SELECT r.name FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY 1", String.class, userId);
    }
}
