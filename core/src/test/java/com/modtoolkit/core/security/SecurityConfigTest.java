package com.modtoolkit.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {
    static final String BASE = "default-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data:; connect-src 'self'";

    @Test
    void theCspLetsTheUiTalkToTheIdentityProviderOnly() {
        assertThat(SecurityConfig.csp("https://idp.example.com:9443/realms/toolkit")).isEqualTo(BASE + " https://idp.example.com:9443");
        assertThat(SecurityConfig.csp("https://login.example.com/tenant/v2.0")).isEqualTo(BASE + " https://login.example.com");
        assertThat(SecurityConfig.csp("")).isEqualTo(BASE);
        assertThat(SecurityConfig.csp(null)).isEqualTo(BASE);
    }
}
