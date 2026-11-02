package com.modtoolkit.discovery;

import com.modtoolkit.parsers.cobol.CopybookLibrary;
import com.modtoolkit.parsers.cobol.ProLeapCobolParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * inventory, then parse in parallel by member, then link across members. Pending status lives on the member row and
 * each member commits on its own, so any re-run resumes exactly where a stopped run ended.
 */
@Configuration
public class DiscoverJobConfig {
    private static final Logger log = LoggerFactory.getLogger(DiscoverJobConfig.class);

    /** The run's options; set by {@link DiscoveryService} before launch. */
    public static final class RunContext {
        volatile DiscoverOptions options;
        /** Test hook: stops the parse step after this many members, as a crash would. */
        volatile int stopAfter = -1;
    }

    @Bean
    public RunContext discoverRunContext() {
        return new RunContext();
    }

    @Bean
    public NodeStore nodeStore(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        return new NodeStore(jdbc, tm);
    }

    @Bean
    public Job discoverJob(JobRepository repo, Step inventoryStep, Step parseStep, Step linkStep) {
        return new JobBuilder("discover", repo).start(inventoryStep).next(parseStep).next(linkStep).build();
    }

    /** Steps hold no transaction of their own: a run of hours must not sit idle in one, and members commit singly. */
    private static DefaultTransactionAttribute noTx() {
        return new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Bean
    public Step inventoryStep(JobRepository repo, PlatformTransactionManager tm, JdbcTemplate jdbc, RunContext ctx, NodeStore nodes) {
        return new StepBuilder("inventory", repo).tasklet((c, cc) -> {
            nodes.clearCache();
            InventoryScanner.Counts n = new InventoryScanner(jdbc).scan(ctx.options);
            log.info("inventory: {} members, {} changed, {} removed", n.total(), n.changed(), n.removed());
            return RepeatStatus.FINISHED;
        }, tm).transactionAttribute(noTx()).allowStartIfComplete(true).build();
    }

    @Bean
    public Step parseStep(JobRepository repo, PlatformTransactionManager tm, JdbcTemplate jdbc, RunContext ctx, NodeStore nodes) {
        return new StepBuilder("parse", repo).tasklet((c, cc) -> {
            parseAll(jdbc, tm, ctx, nodes);
            return RepeatStatus.FINISHED;
        }, tm).transactionAttribute(noTx()).allowStartIfComplete(true).build();
    }

    private void parseAll(JdbcTemplate jdbc, PlatformTransactionManager tm, RunContext ctx, NodeStore nodes) throws InterruptedException {
        CopybookLibrary lib = new CopybookLibrary();
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, path, name, type, format, normalized_text FROM member WHERE normalized_text IS NOT NULL", rs -> {
            ids.put(rs.getString(2), rs.getLong(1));
            // A COPY target is defined by being referenced, not by how it reads: a copybook of only paragraphs has no
            // signature, so anything that could be text stays eligible, and a real program never shadows a copybook.
            if (!List.of("cobol", "jcl", "proc", "bms", "ddl", "csd").contains(rs.getString(4))) {
                lib.add(rs.getString(3), rs.getString(2), MemberProcessor.cobolLines(rs.getString(6), rs.getString(5)));
            }
        });
        MemberProcessor proc = new MemberProcessor(List.of(new ProLeapCobolParser()), ctx.options.memberTimeout(), lib, ids, new FactWriter(jdbc, nodes));
        TransactionTemplate memberTx = new TransactionTemplate(tm);
        // Copybooks first, so a program's copy_use row can point at its copybook row.
        List<Long> pending = jdbc.queryForList("SELECT id FROM member WHERE parse_status = 'pending' ORDER BY (type = 'copybook') DESC, id", Long.class);
        long copybooks = jdbc.queryForObject("SELECT count(*) FROM member WHERE parse_status = 'pending' AND type = 'copybook'", Long.class);
        log.info("parse: {} members pending, {} threads", pending.size(), ctx.options.threads());
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(ctx.options.threads());
        try {
            // Deleting a copybook row updates the copy_use rows of its users, so the two kinds never run together.
            runPhase(pool, pending.subList(0, (int) copybooks), jdbc, memberTx, proc, nodes, ctx, done, pending.size());
            runPhase(pool, pending.subList((int) copybooks, pending.size()), jdbc, memberTx, proc, nodes, ctx, done, pending.size());
            // Members only COPY statements revealed to be copybooks: reclassify and parse them now.
            List<Long> revealed = jdbc.queryForList("""
                    UPDATE member m SET type = 'copybook', parse_status = 'pending', classify_reason = 'referenced by COPY'
                    WHERE m.type IN ('unknown', 'control', 'assembler', 'pli', 'easytrieve', 'rexx', 'clist') AND m.normalized_text IS NOT NULL
                      AND EXISTS (SELECT 1 FROM copy_use u WHERE upper(u.copybook_name) = upper(m.name))
                    RETURNING m.id""", Long.class);
            runPhase(pool, revealed, jdbc, memberTx, proc, nodes, ctx, done, pending.size() + revealed.size());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void runPhase(ExecutorService pool, List<Long> ids, JdbcTemplate jdbc, TransactionTemplate tx, MemberProcessor proc, NodeStore nodes, RunContext ctx, AtomicInteger done, int total) throws InterruptedException {
        List<Future<?>> running = new ArrayList<>();
        for (long id : ids) running.add(pool.submit(() -> processOne(id, jdbc, tx, proc, nodes, ctx, done, total)));
        for (Future<?> f : running) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.error("unexpected failure in a parse worker", e.getCause());
            }
        }
    }

    private static void processOne(long id, JdbcTemplate jdbc, TransactionTemplate memberTx, MemberProcessor proc, NodeStore nodes, RunContext ctx, AtomicInteger done, int total) {
        if (ctx.stopAfter >= 0 && done.get() >= ctx.stopAfter) return;
        try {
            MemberProcessor.Row r = jdbc.queryForObject("SELECT id, path, name, type, format, normalized_text FROM member WHERE id = ?",
                    (rs, i) -> new MemberProcessor.Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), id);
            MemberProcessor.Result out = proc.process(r);
            nodes.clearMember(id);
            memberTx.executeWithoutResult(s -> MemberProcessor.record(jdbc, out));
        } catch (RuntimeException e) {
            // A failure while storing must not stop the job either; it lands in the report.
            log.warn("member {} failed: {}", id, e.toString());
            jdbc.update("UPDATE member SET parse_status='failed', parse_error=? WHERE id=?", "storing facts: " + e.getMessage(), id);
        }
        int n = done.incrementAndGet();
        if (n % 1000 == 0) log.info("parse: {} of {}", n, total);
    }

    @Bean
    public Step linkStep(JobRepository repo, PlatformTransactionManager tm, JdbcTemplate jdbc, NodeStore nodes) {
        return new StepBuilder("link", repo).tasklet((c, cc) -> {
            new GraphLinker(jdbc, nodes).link();
            return RepeatStatus.FINISHED;
        }, tm).allowStartIfComplete(true).build();
    }
}
