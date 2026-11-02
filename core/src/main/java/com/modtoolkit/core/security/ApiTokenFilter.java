package com.modtoolkit.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Recognizes issued tk_ tokens. Anything else falls through to the JWT filter, or to a 401. */
public class ApiTokenFilter extends OncePerRequestFilter {
    private final TokenService tokens;

    public ApiTokenFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer tk_")) {
            tokens.authenticate(h.substring(7)).ifPresent(id -> {
                List<SimpleGrantedAuthority> auth = id.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.username(), null, auth));
            });
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"token is unknown or revoked\",\"hint\":\"ask an admin for a new MCP token\"}}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
