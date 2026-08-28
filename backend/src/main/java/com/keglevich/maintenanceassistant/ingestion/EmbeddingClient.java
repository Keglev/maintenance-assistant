package com.keglevich.maintenanceassistant.ingestion;

import java.util.List;

/**
 * The one call this application makes to the LLM provider during ingestion.
 *
 * <p>Narrow on purpose. The spike in {@code spike/spring-ai-boot4/} established that Spring AI 2.0
 * works on Boot 4.1 but is a large dependency for a single POST, and that its abstraction leaks
 * precisely where the provider is OpenAI-<em>compatible</em> rather than OpenAI. Keeping the
 * contract this small means adopting it later — for the Phase 3 chat path, where it earns its
 * weight — is a second implementation of this interface, not a rewrite. It is also what lets the
 * integration tests run without a network.
 */
public interface EmbeddingClient {

    /**
     * Vector width this client produces. Checked against the database column rather than trusted,
     * because a silent width change corrupts retrieval instead of breaking it.
     */
    int dimensions();

    /**
     * Embeds every text, batching internally as needed.
     *
     * <p><b>Implementations record their own usage</b> in {@code EmbeddingBudget}, per request, as
     * the provider serves it — not the caller on success. The money is spent when the request is
     * answered, whatever happens to the response afterwards. Recording it in the caller is how the
     * first real run of this pipeline made 150 paid calls the counter never saw: every one of them
     * failed while converting the response, so the success path that did the counting never ran.
     *
     * @param texts inputs, in order
     * @return vectors in the same order as {@code texts}, plus what the run cost
     * @throws EmbeddingException on a failure the caller cannot retry away
     */
    EmbeddingBatch embed(List<String> texts);

    /**
     * @param vectors      one vector per input, same order
     * @param providerCalls HTTP requests actually made — what the provider bills and what the daily
     *                      budget counts, which is not the number of texts
     * @param promptTokens input tokens as reported by the provider; embeddings bill input only
     */
    record EmbeddingBatch(List<float[]> vectors, int providerCalls, long promptTokens) {
    }

    /** Anything that stops a protocol being indexed. Carries the text stored in {@code failure_reason}. */
    class EmbeddingException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
