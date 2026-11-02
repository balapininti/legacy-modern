package com.modtoolkit.core.config;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Live checks of every config block. Nothing is sent to an endpoint beyond a reachability probe. */
public final class Doctor {
    public enum Status { PASS, FAIL, SKIP }

    public record CheckResult(String check, Status status, String detail) {}

    private final Function<String, String> env;

    public Doctor(Function<String, String> env) {
        this.env = env;
    }

    public List<CheckResult> run(ToolkitConfig c) {
        List<CheckResult> out = new ArrayList<>();
        List<ConfigIssue> issues = ConfigValidator.validate(c);
        for (ConfigIssue i : issues) {
            out.add(new CheckResult("config " + i.path(), Status.FAIL, i.message()));
        }
        if (issues.isEmpty()) {
            out.add(new CheckResult("config", Status.PASS, "all blocks valid"));
        }
        out.add(source(c));
        out.add(database());
        out.add(llm(c));
        out.add(zos(c));
        out.add(identityProvider(c));
        return out;
    }

    public static boolean ok(List<CheckResult> results) {
        return results.stream().noneMatch(r -> r.status() == Status.FAIL);
    }

    private CheckResult source(ToolkitConfig c) {
        if (c.source() == null || c.source().root() == null || c.source().root().isBlank()) {
            return new CheckResult("source", Status.FAIL, "source.root is not set");
        }
        Path root = Path.of(c.source().root());
        if (!Files.isDirectory(root)) {
            return new CheckResult("source", Status.FAIL, root + " is not a directory");
        }
        if (!Files.isReadable(root)) {
            return new CheckResult("source", Status.FAIL, root + " is not readable");
        }
        return new CheckResult("source", Status.PASS, root.toString());
    }

    private CheckResult database() {
        String url = env.apply("TOOLKIT_DB_URL");
        if (url == null || url.isBlank()) {
            return new CheckResult("database", Status.FAIL, "TOOLKIT_DB_URL is not set");
        }
        try (Connection con = DriverManager.getConnection(url, env.apply("TOOLKIT_DB_USER"), env.apply("TOOLKIT_DB_PASSWORD"));
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_available_extensions WHERE name = 'vector'")) {
            rs.next();
            if (rs.getInt(1) == 0) {
                return new CheckResult("database", Status.FAIL, "connected, but the pgvector extension is not available");
            }
            return new CheckResult("database", Status.PASS, con.getMetaData().getDatabaseProductVersion());
        } catch (Exception e) {
            return new CheckResult("database", Status.FAIL, e.getMessage());
        }
    }

    private CheckResult llm(ToolkitConfig c) {
        if (c.llm() == null) {
            return new CheckResult("llm", Status.SKIP, "no llm block; discovery and MCP v1 do not need one");
        }
        String keyEnv = c.llm().apiKeyEnv();
        if (keyEnv != null && (env.apply(keyEnv) == null || env.apply(keyEnv).isBlank())
                && !"openai-compatible".equals(c.llm().provider())) {
            return new CheckResult("llm", Status.FAIL, keyEnv + " is not set");
        }
        if (c.llm().endpoint() == null || c.llm().endpoint().isBlank()) {
            return new CheckResult("llm", Status.PASS, "provider default endpoint; key present");
        }
        return probe("llm", c.llm().endpoint());
    }

    private CheckResult zos(ToolkitConfig c) {
        if (c.zos() == null || !Boolean.TRUE.equals(c.zos().enabled()) || "none".equals(c.zos().method())) {
            return new CheckResult("zos", Status.SKIP, "disabled; harness uses exported fixtures");
        }
        for (String e : new String[] {c.zos().userEnv(), c.zos().passwordEnv()}) {
            if (e != null && (env.apply(e) == null || env.apply(e).isBlank())) {
                return new CheckResult("zos", Status.FAIL, e + " is not set");
            }
        }
        if (c.zos().host() == null || c.zos().host().isBlank()) {
            return new CheckResult("zos", Status.FAIL, "zos.host is not set");
        }
        if ("zowe".equals(c.zos().method())) {
            return probe("zos", "https://" + c.zos().host() + "/zosmf/info");
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(c.zos().host(), 21), 5000);
            return new CheckResult("zos", Status.PASS, "FTP port reachable on " + c.zos().host());
        } catch (Exception e) {
            return new CheckResult("zos", Status.FAIL, "FTP port unreachable on " + c.zos().host() + ": " + e.getMessage());
        }
    }

    private CheckResult identityProvider(ToolkitConfig c) {
        if (c.ui() == null || c.ui().oidcIssuer() == null || c.ui().oidcIssuer().isBlank()) {
            return new CheckResult("identity provider", Status.SKIP, "no OIDC issuer; local accounts and tokens only");
        }
        return probe("identity provider", c.ui().oidcIssuer().replaceAll("/$", "") + "/.well-known/openid-configuration");
    }

    private static CheckResult probe(String name, String url) {
        try {
            HttpURLConnection h = (HttpURLConnection) URI.create(url).toURL().openConnection();
            h.setConnectTimeout(5000);
            h.setReadTimeout(5000);
            h.setRequestMethod("GET");
            int code = h.getResponseCode();
            // Any HTTP answer below 500 proves reachability; auth is exercised by the real call.
            return code < 500
                    ? new CheckResult(name, Status.PASS, url + " answered " + code)
                    : new CheckResult(name, Status.FAIL, url + " answered " + code);
        } catch (Exception e) {
            return new CheckResult(name, Status.FAIL, url + " unreachable: " + e);
        }
    }

    /** Convenience for the CLI. */
    public static Doctor fromEnvironment() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return new Doctor(System::getenv);
    }

    public List<CheckResult> runChecked(Path configPath) {
        try {
            return run(ToolkitConfig.load(configPath));
        } catch (ConfigLoadException e) {
            return List.of(new CheckResult("config", Status.FAIL, e.getMessage()));
        }
    }
}
