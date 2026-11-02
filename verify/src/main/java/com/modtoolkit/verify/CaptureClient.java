package com.modtoolkit.verify;

import java.nio.file.Path;

/** Submits a job on the test LPAR and brings back what it wrote. Zowe first, FTP JES where Zowe is not allowed. */
public interface CaptureClient {
    /** @return the job id */
    String submit(String jcl) throws Exception;

    /** Waits for the job to finish. @return its return code text, for example CC 0000 */
    String waitFor(String jobId, java.time.Duration timeout) throws Exception;

    void downloadDataset(String dsn, Path to) throws Exception;

    /** The job's spool, for the capture record. */
    String spool(String jobId) throws Exception;
}
