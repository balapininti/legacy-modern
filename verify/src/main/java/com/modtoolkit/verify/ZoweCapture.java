package com.modtoolkit.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Through the Zowe CLI and z/OSMF. The CLI is an external tool the client installs; it is not bundled (EPL-2.0). */
public final class ZoweCapture implements CaptureClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String zowe;
    private final List<String> connection;

    public ZoweCapture(String zoweCommand, String host, String user, String password) {
        this.zowe = zoweCommand;
        this.connection = List.of("--host", host, "--user", user, "--password", password, "--reject-unauthorized", "false");
    }

    private JsonNode json(String... args) throws Exception {
        JsonNode n = JSON.readTree(run(null, withConnection(args, true)));
        if (!n.path("success").asBoolean(true)) throw new IOException("zowe: " + n.path("message").asText());
        return n.path("data");
    }

    private List<String> withConnection(String[] args, boolean rfj) {
        List<String> c = new ArrayList<>(List.of(zowe));
        c.addAll(List.of(args));
        c.addAll(connection);
        if (rfj) c.add("--rfj");
        return c;
    }

    private String run(String stdin, List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
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
        JsonNode n = JSON.readTree(run(jcl, withConnection(new String[] {"zos-jobs", "submit", "stdin"}, true)));
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
        return run(null, withConnection(new String[] {"zos-jobs", "view", "all-spool-content", "--jobid", jobId}, false));
    }
}
