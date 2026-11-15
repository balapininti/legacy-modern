package com.modtoolkit.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.PropertyResolver;

/**
 * The server's certificate as a Spring Boot SSL bundle named "toolkit". A PEM certificate and key (what a client CA
 * issues) win when both are set; otherwise the PKCS12 keystore that {@code toolkit setup} makes with keytool.
 */
final class ServerTls {
    static final String BUNDLE = "toolkit";

    private ServerTls() {}

    static Map<String, Object> properties(PropertyResolver env) {
        String cert = get(env, "TOOLKIT_TLS_CERT");
        String key = get(env, "TOOLKIT_TLS_KEY");
        String keystore = get(env, "TOOLKIT_TLS_KEYSTORE");
        String password = get(env, "TOOLKIT_TLS_PASSWORD");
        Map<String, Object> p = new LinkedHashMap<>();
        if (cert != null || key != null) {
            if (cert == null || key == null) throw new IllegalArgumentException("set both TOOLKIT_TLS_CERT and TOOLKIT_TLS_KEY (PEM files), or neither");
            String b = "spring.ssl.bundle.pem." + BUNDLE + ".";
            p.put(b + "keystore.certificate", url(cert, "TOOLKIT_TLS_CERT"));
            p.put(b + "keystore.private-key", url(key, "TOOLKIT_TLS_KEY"));
            if (password != null) p.put(b + "keystore.private-key-password", password);
            p.put(b + "options.enabled-protocols", "TLSv1.3,TLSv1.2");
        } else if (keystore != null) {
            String b = "spring.ssl.bundle.jks." + BUNDLE + ".";
            p.put(b + "keystore.location", url(keystore, "TOOLKIT_TLS_KEYSTORE"));
            p.put(b + "keystore.type", "PKCS12");
            if (password != null) p.put(b + "keystore.password", password);
            p.put(b + "options.enabled-protocols", "TLSv1.3,TLSv1.2");
        } else {
            throw new IllegalArgumentException("no TLS certificate: set TOOLKIT_TLS_KEYSTORE and TOOLKIT_TLS_PASSWORD (a PKCS12 keystore, as toolkit setup makes),"
                    + " or TOOLKIT_TLS_CERT and TOOLKIT_TLS_KEY (PEM files from your CA)");
        }
        p.put("server.ssl.bundle", BUNDLE);
        return p;
    }

    private static String get(PropertyResolver env, String name) {
        String v = env.getProperty(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** A file: URL, so a Windows path with spaces or a drive letter reaches the resource loader intact. */
    private static String url(String file, String var) {
        Path p = Path.of(file).toAbsolutePath().normalize();
        if (!Files.isReadable(p)) throw new IllegalArgumentException(var + " names " + p + ", which is not a readable file");
        return p.toUri().toString();
    }
}
