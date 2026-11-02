package com.modtoolkit.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Submit, wait, download, keep the spool. The raw bytes stay beside the fixtures made from them. */
public final class CaptureRunner {
    public record Result(String jobId, String returnCode, List<String> datasets, Path dir) {}

    private final CaptureClient client;

    public CaptureRunner(CaptureClient client) {
        this.client = client;
    }

    public Result run(String jobCard, String steps, List<String> datasets, Path out, Duration timeout) throws Exception {
        if (jobCard == null || jobCard.isBlank()) throw new IllegalArgumentException("zos.job_card is required");
        String jcl = jobCard.stripTrailing() + "\n" + steps;
        Files.createDirectories(out);
        Files.writeString(out.resolve("job.jcl"), jcl);
        String id = client.submit(jcl);
        String rc = client.waitFor(id, timeout);
        Files.writeString(out.resolve("SYSOUT.txt"), client.spool(id));
        for (String d : datasets) client.downloadDataset(d, out.resolve(d + ".output"));
        return new Result(id, rc, datasets, out);
    }
}
