package com.modtoolkit.knowledge;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/** The knowledge job. Each unit commits on its own, so the step holds no transaction across hours of model calls. */
@Configuration
public class KnowledgeJobConfig {
    public static final class Request {
        volatile LlmClient llm;
        volatile KnowledgeOptions options;
        volatile KnowledgeRunner.Stats stats;
    }

    @Bean
    public Request knowledgeRequest() {
        return new Request();
    }

    @Bean
    public Job knowledgeJob(JobRepository repo, @Qualifier("knowledgeStep") Step step) {
        return new JobBuilder("knowledge", repo).start(step).build();
    }

    @Bean
    public Step knowledgeStep(JobRepository repo, PlatformTransactionManager tm, Request req, KnowledgeRunner runner) {
        return new StepBuilder("knowledge-units", repo).tasklet((c, cc) -> {
            req.stats = runner.run(req.llm, req.options);
            return RepeatStatus.FINISHED;
        }, tm).transactionAttribute(new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED)).allowStartIfComplete(true).build();
    }

    @Service
    public static class Launcher {
        private final JobLauncher launcher;
        private final Job job;
        private final Request req;

        public Launcher(JobLauncher launcher, @Qualifier("knowledgeJob") Job job, Request req) {
            this.launcher = launcher;
            this.job = job;
            this.req = req;
        }

        public KnowledgeRunner.Stats run(LlmClient llm, KnowledgeOptions o) throws Exception {
            req.llm = llm;
            req.options = o;
            JobExecution e = launcher.run(job, new JobParametersBuilder().addLong("run", System.nanoTime()).toJobParameters());
            if (e.getStatus() != BatchStatus.COMPLETED) throw new IllegalStateException("knowledge ended " + e.getStatus() + ": " + e.getAllFailureExceptions());
            return req.stats;
        }
    }
}
