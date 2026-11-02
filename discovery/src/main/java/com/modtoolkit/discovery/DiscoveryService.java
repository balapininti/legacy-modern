package com.modtoolkit.discovery;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

@Service
public class DiscoveryService {
    private final JobLauncher launcher;
    private final Job discoverJob;
    private final DiscoverJobConfig.RunContext ctx;

    public DiscoveryService(JobLauncher launcher, Job discoverJob, DiscoverJobConfig.RunContext ctx) {
        this.launcher = launcher;
        this.discoverJob = discoverJob;
        this.ctx = ctx;
    }

    public BatchStatus run(DiscoverOptions options) throws Exception {
        return run(options, -1);
    }

    /** @param stopAfter stop the parse step after this many members, as a crash would; -1 for a normal run */
    public BatchStatus run(DiscoverOptions options, int stopAfter) throws Exception {
        ctx.options = options;
        ctx.stopAfter = stopAfter;
        JobExecution e = launcher.run(discoverJob, new JobParametersBuilder().addLong("run", System.nanoTime()).toJobParameters());
        if (e.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException("discover ended " + e.getStatus() + ": " + e.getAllFailureExceptions());
        }
        return e.getStatus();
    }
}
