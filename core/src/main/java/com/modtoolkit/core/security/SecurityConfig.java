package com.modtoolkit.core.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** One policy for the MCP endpoint and REST API: everything needs a bearer token except health. */
@Configuration
@ConditionalOnWebApplication
public class SecurityConfig {
    @Bean
    SecurityFilterChain toolkitSecurity(HttpSecurity http, TokenService tokens, ObjectProvider<JwtDecoder> jwt) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // The request was authorized on arrival; a stateless async re-dispatch has no context to check.
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/api/auth/login", "/api/auth/config").permitAll().anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"a bearer token is required\",\"hint\":\"send Authorization: Bearer <token>\"}}");
                }))
                .addFilterBefore(new ApiTokenFilter(tokens), UsernamePasswordAuthenticationFilter.class);
        JwtDecoder decoder = jwt.getIfAvailable();
        if (decoder != null) {
            http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(rolesConverter())));
        }
        return http.build();
    }

    /** Only an issuer the client configured is trusted; without one, tokens are the only way in. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("toolkit.oidc.issuer")
    JwtDecoder oidcDecoder(org.springframework.core.env.Environment env) {
        return JwtDecoders.fromIssuerLocation(env.getRequiredProperty("toolkit.oidc.issuer"));
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
