package com.modtoolkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ServerTlsTest {
    @TempDir
    Path dir;

    Path file(String name) throws Exception {
        Path p = dir.resolve("with space").resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        return p;
    }

    @Test
    void aPkcs12KeystoreBecomesTheServerBundle() throws Exception {
        Path ks = file("toolkit.p12");
        Map<String, Object> p = ServerTls.properties(new MockEnvironment().withProperty("TOOLKIT_TLS_KEYSTORE", ks.toString()).withProperty("TOOLKIT_TLS_PASSWORD", "pw"));
        assertThat(p).containsEntry("server.ssl.bundle", "toolkit")
                .containsEntry("spring.ssl.bundle.jks.toolkit.keystore.location", ks.toUri().toString())
                .containsEntry("spring.ssl.bundle.jks.toolkit.keystore.password", "pw")
                .containsEntry("spring.ssl.bundle.jks.toolkit.keystore.type", "PKCS12");
        assertThat(p.keySet()).noneMatch(k -> k.contains(".pem."));
    }

    @Test
    void aPemPairWinsOverAKeystore() throws Exception {
        Path cert = file("tls.crt");
        Path key = file("tls.key");
        Map<String, Object> p = ServerTls.properties(new MockEnvironment().withProperty("TOOLKIT_TLS_KEYSTORE", file("k.p12").toString())
                .withProperty("TOOLKIT_TLS_CERT", cert.toString()).withProperty("TOOLKIT_TLS_KEY", key.toString()));
        assertThat(p).containsEntry("server.ssl.bundle", "toolkit")
                .containsEntry("spring.ssl.bundle.pem.toolkit.keystore.certificate", cert.toUri().toString())
                .containsEntry("spring.ssl.bundle.pem.toolkit.keystore.private-key", key.toUri().toString())
                .doesNotContainKey("spring.ssl.bundle.pem.toolkit.keystore.private-key-password");
        assertThat(p.keySet()).noneMatch(k -> k.contains(".jks."));
    }

    @Test
    void anEncryptedPemKeyTakesThePassword() throws Exception {
        Map<String, Object> p = ServerTls.properties(new MockEnvironment().withProperty("TOOLKIT_TLS_CERT", file("tls.crt").toString())
                .withProperty("TOOLKIT_TLS_KEY", file("tls.key").toString()).withProperty("TOOLKIT_TLS_PASSWORD", "pw"));
        assertThat(p).containsEntry("spring.ssl.bundle.pem.toolkit.keystore.private-key-password", "pw");
    }

    @Test
    void misconfigurationIsExplained() throws Exception {
        assertThatThrownBy(() -> ServerTls.properties(new MockEnvironment())).hasMessageContaining("TOOLKIT_TLS_KEYSTORE").hasMessageContaining("TOOLKIT_TLS_CERT");
        assertThatThrownBy(() -> ServerTls.properties(new MockEnvironment().withProperty("TOOLKIT_TLS_CERT", file("tls.crt").toString())))
                .hasMessageContaining("TOOLKIT_TLS_KEY");
        assertThatThrownBy(() -> ServerTls.properties(new MockEnvironment().withProperty("TOOLKIT_TLS_KEYSTORE", dir.resolve("missing.p12").toString())))
                .hasMessageContaining("missing.p12");
    }
}
