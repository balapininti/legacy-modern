package com.modtoolkit.core.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.unit.DataSize;

/**
 * One policy for the MCP endpoint, REST API and web UI: the API, MCP and metrics need a bearer token (health, login and
 * the OpenAPI document do not); the UI's files and page routes are public, since the app signs in from there.
 */
@Configuration
@ConditionalOnWebApplication
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain toolkitSecurity(HttpSecurity http, TokenService tokens, ObjectProvider<JwtDecoder> jwt, org.springframework.core.env.Environment env) throws Exception {
        http.csrf(c -> c.disable())
                .headers(h -> h.httpStrictTransportSecurity(s -> s.maxAgeInSeconds(31536000).includeSubDomains(false))
                        .contentSecurityPolicy(c -> c.policyDirectives(csp(env.getProperty("toolkit.oidc.issuer", "")))))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // The request was authorized on arrival; a stateless async re-dispatch has no context to check.
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/api/auth/login", "/api/auth/config", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/**", "/mcp", "/mcp/**", "/actuator/**").authenticated()
                        // The web UI: reading its files and page routes; nothing else in the app answers there.
                        .requestMatchers(HttpMethod.GET, "/**").permitAll().requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"a bearer token is required\",\"hint\":\"send Authorization: Bearer <token>\"}}");
                }))
                .addFilterBefore(new ApiTokenFilter(tokens), UsernamePasswordAuthenticationFilter.class);
        JwtDecoder decoder = jwt.getIfAvailable();
        if (decoder != null) {
            DefaultBearerTokenResolver jwtOnly = new DefaultBearerTokenResolver();
            // An issued tk_ token was settled by ApiTokenFilter; handed on, it would be rejected as a malformed JWT.
            http.oauth2ResourceServer(o -> o.bearerTokenResolver(req -> {
                String h = req.getHeader("Authorization");
                return h != null && h.startsWith("Bearer tk_") ? null : jwtOnly.resolve(req);
            }).jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(rolesConverter())));
        }
        return http.build();
    }

    /** The proxy's policy: same origin only, plus the identity provider the UI signs in against. */
    static String csp(String issuer) {
        String csp = "default-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data:; connect-src 'self'";
        if (issuer == null || issuer.isBlank()) return csp;
        URI u = URI.create(issuer.strip());
        return csp + " " + u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
    }

    /** Ahead of everything, security included, so an oversized body is refused before any work. */
    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimit(@Value("${toolkit.http.max-body-size:2MB}") DataSize max) {
        FilterRegistrationBean<BodyLimitFilter> r = new FilterRegistrationBean<>(new BodyLimitFilter(max.toBytes()));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    /** Only an issuer the client configured is trusted; without one, tokens are the only way in. */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${toolkit.oidc.issuer:}' != ''")
    JwtDecoder oidcDecoder(org.springframework.core.env.Environment env) {
        // Fetched on first use, so the toolkit can start while the identity provider is down.
        String issuer = env.getRequiredProperty("toolkit.oidc.issuer");
        return new org.springframework.security.oauth2.jwt.SupplierJwtDecoder(() -> org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withIssuerLocation(issuer).build());
    }

    /** Maps a "roles" or "groups" claim onto the toolkit roles; unknown values grant nothing. */
    static Converter<Jwt, AbstractAuthenticationToken> rolesConverter() {
        return jwt -> {
            Collection<GrantedAuthority> auth = new ArrayList<>();
            for (String claim : List.of("roles", "groups")) {
                List<String> values = jwt.getClaimAsStringList(claim);
                if (values == null) continue;
                for (String v : values) {
                    if (List.of("admin", "developer", "sme-reviewer").contains(v)) auth.add(new SimpleGrantedAuthority("ROLE_" + v));
                }
            }
            String name = jwt.getClaimAsString("preferred_username");
            return new JwtAuthenticationToken(jwt, auth, name != null ? name : jwt.getSubject());
        };
    }

}
