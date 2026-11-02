package com.modtoolkit.knowledge;

/** The one way the toolkit talks to a model. Tests and CI use recorded answers; no test sends source anywhere. */
public interface LlmClient {
    record Response(String text, long tokensIn, long tokensOut) {}

    /** A failure worth retrying, such as a timeout or a rate limit. */
    class TransientFailure extends RuntimeException {
        public TransientFailure(String m, Throwable c) {
            super(m, c);
        }
    }

    Response complete(String unitKey, String system, String user);

    String model();
}
