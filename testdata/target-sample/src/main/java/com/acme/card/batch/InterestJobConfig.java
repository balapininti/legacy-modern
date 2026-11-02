package com.acme.card.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InterestJobConfig {
    @Bean
    public Job intcalcJob(JobRepository repo, Step interestStep) {
        return new JobBuilder("INTCALC", repo).start(interestStep).build();
    }

    @Bean
    public Step interestStep(JobRepository repo) {
        return new StepBuilder("STEP15", repo).build();
    }
}
