package com.modtoolkit.parsers.cobol;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** A failure in one member must never stop a job: timeouts, stack and memory exhaustion all become a failed outcome. */
public final class ResilientParser {
    public record Outcome(CobolFacts facts, String parser, boolean ok, String error, Loc errorAt, List<String> rejectedBy) {}

    private static final long STACK_BYTES = 512L * 1024 * 1024; // ANTLR recursion is deep on long IF chains.

    private static final java.util.concurrent.locks.ReentrantLock SERIAL = new java.util.concurrent.locks.ReentrantLock();

    private final List<CobolParser> chain;
    private final Duration timeout;

    public ResilientParser(List<CobolParser> chain, Duration timeout) {
        this.chain = chain;
        this.timeout = timeout;
    }

    public Outcome parse(ExpandedSource src) {
        java.util.ArrayList<String> rejected = new java.util.ArrayList<>();
        String lastError = "no parser configured";
        Loc lastAt = null;
        for (CobolParser p : chain) {
            AtomicReference<CobolFacts> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread t = new Thread(null, () -> {
                try {
                    result.set(p.parse(src));
                } catch (Throwable e) { // Including OutOfMemoryError and StackOverflowError, by design.
                    failure.set(e);
                }
            }, "parse-" + src.member(), STACK_BYTES);
            t.setDaemon(true);
            // Taken here, not inside the thread, so that waiting for a turn does not use up this member's time budget.
            boolean locked = false;
            try {
                if (p.serial()) {
                    SERIAL.lockInterruptibly();
                    locked = true;
                }
                t.start();
                // The fallback is an island parser whose backtracking can blow up on a big member: it gets a quarter of
                // the budget, since a member it cannot do in time goes to the report either way.
                t.join(p == chain.get(0) ? timeout.toMillis() : Math.max(1, timeout.toMillis() / 4));
                if (t.isAlive()) {
                    t.interrupt();
                    t.join(2000);
                    failure.set(new CobolParseException("timed out after " + (p == chain.get(0) ? timeout.toMillis() : timeout.toMillis() / 4) + "ms", null));
                    result.set(null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(null, p.name(), false, "interrupted", null, rejected);
            } finally {
                if (locked) SERIAL.unlock();
            }
            if (result.get() != null) {
                return new Outcome(result.get(), p.name(), true, null, null, rejected);
            }
            Throwable f = failure.get();
            String why = p.name() + ": " + (f == null ? "no result" : f.getClass().getSimpleName() + ": " + f.getMessage());
            // The primary parser's reason says what is unusual about the source; a fallback's rarely adds to it.
            if (rejected.isEmpty()) {
                lastError = why;
                lastAt = f instanceof CobolParseException c ? c.at() : null;
            }
            rejected.add(why);
        }
        return new Outcome(null, null, false, lastError, lastAt, rejected);
    }
}
