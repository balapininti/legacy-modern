package com.acme.card.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class InterestJobConfig {
    @Bean
    public Job intcalcJob(JobRepository repo, Step interestStep) {
        return new JobBuilder("INTCALC", repo).start(interestStep).build();
    }

    @Bean
    public Step interestStep(JobRepository repo, PlatformTransactionManager tm) {
        return new StepBuilder("STEP15", repo).tasklet((contribution, context) -> RepeatStatus.FINISHED, tm).build();
    }
}
