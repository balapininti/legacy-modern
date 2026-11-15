package com.modtoolkit.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Through the Zowe CLI and z/OSMF. The CLI is an external tool the client installs; it is not bundled (EPL-2.0).
 *
 * <p>Host, user and password go to the child as {@code ZOWE_OPT_*} environment variables (Zowe maps every option
 * {@code --foo-bar} to {@code ZOWE_OPT_FOO_BAR}), so the secret is never on a command line where other local users
 * or process listings can see it. On Windows the CLI is npm's {@code zowe.cmd} shim, which CreateProcess runs through
 * cmd.exe; the JDK's default (legacy) quoting does not escape cmd metacharacters, so arguments to a batch file are
 * checked and refused rather than handed to cmd.exe to interpret.
 */
public final class ZoweCapture implements CaptureClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String zowe;
    private final Map<String, String> connection;

    public ZoweCapture(String zoweCommand, String host, String user, String password) {
        this.zowe = zoweCommand;
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZOWE_OPT_HOST", host);
        env.put("ZOWE_OPT_USER", user);
        env.put("ZOWE_OPT_PASSWORD", password);
        env.put("ZOWE_OPT_REJECT_UNAUTHORIZED", "false");
        this.connection = env;
    }

    private JsonNode json(String... args) throws Exception {
        JsonNode n = JSON.readTree(run(null, command(args, true)));
        if (!n.path("success").asBoolean(true)) throw new IOException("zowe: " + n.path("message").asText());
        return n.path("data");
    }

    private List<String> command(String[] args, boolean rfj) {
        List<String> c = new ArrayList<>(List.of(zowe));
        c.addAll(List.of(args));
        if (rfj) c.add("--rfj");
        if (batchFile(zowe)) c.subList(1, c.size()).forEach(ZoweCapture::checkBatchArgument);
        return c;
    }

    static boolean batchFile(String command) {
        String up = command.toUpperCase(Locale.ROOT);
        return up.endsWith(".CMD") || up.endsWith(".BAT");
    }

    /**
     * The JDK quotes an argument only when it holds a space, so cmd.exe would read {@code & | < > ^} as operators, and it
     * expands {@code %VAR%} even inside quotes. None of these occur in a jobid, a DSN or an ordinary file path.
     */
    static void checkBatchArgument(String arg) {
        for (char ch : arg.toCharArray()) {
            if ("\"%!^&|<>\r\n".indexOf(ch) >= 0) {
                throw new IllegalArgumentException("argument for a .cmd Zowe CLI holds '" + ch + "', which cmd.exe would interpret: " + arg);
            }
        }
    }

    private String run(String stdin, List<String> cmd) throws Exception {
        ProcessBuilder b = new ProcessBuilder(cmd).redirectErrorStream(true);
        connection.forEach((k, v) -> {
            if (v != null) b.environment().put(k, v);
        });
        Process p = b.start();
        if (stdin != null) p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("zowe timed out");
        }
        return out;
    }

    @Override
    public String submit(String jcl) throws Exception {
        JsonNode n = JSON.readTree(run(jcl, command(new String[] {"zos-jobs", "submit", "stdin"}, true)));
        if (!n.path("success").asBoolean(false)) throw new IOException("submit failed: " + n.path("message").asText(n.toString()));
        return n.path("data").path("jobid").asText();
    }

    @Override
    public String waitFor(String jobId, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            JsonNode d = json("zos-jobs", "view", "job-status-by-jobid", jobId);
            if ("OUTPUT".equals(d.path("status").asText())) return d.path("retcode").asText();
            Thread.sleep(2000);
        }
        throw new IOException("job " + jobId + " did not finish in " + timeout);
    }

    @Override
    public void downloadDataset(String dsn, Path to) throws Exception {
        Files.createDirectories(to.toAbsolutePath().getParent());
        json("zos-files", "download", "data-set", dsn, "--binary", "--file", to.toString());
    }

    @Override
    public String spool(String jobId) throws Exception {
        return run(null, command(new String[] {"zos-jobs", "view", "all-spool-content", "--jobid", jobId}, false));
    }
}
