package com.keglevich.maintenanceassistant.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic vectors derived from the text's hash, shared by the suites that run the pipeline.
 *
 * <p>Not meaningful as embeddings. These tests are about the pipeline and the schema — that
 * {@code vector(1024)} accepts what the client produces, that a re-index genuinely rewrites the
 * chunks — not about retrieval quality, which is measured against the real provider in the manual
 * corpus run.
 *
 * <p><b>No call leaves the machine.</b> A suite that depended on a provider being up, on a key being
 * present, or that spent money to run, would be a suite nobody runs before pushing.
 *
 * <p>Determinism is the property the edit tests lean on: the same text always produces the same
 * vector, so a vector that changed after an edit changed because the <em>text</em> did.
 */
class FakeEmbeddingClient implements EmbeddingClient {

    private final AtomicInteger calls = new AtomicInteger();
    private final EmbeddingBudget budget;
    private volatile String nextFailure;

    FakeEmbeddingClient(EmbeddingBudget budget) {
        this.budget = budget;
    }

    void reset() {
        calls.set(0);
        nextFailure = null;
    }

    int calls() {
        return calls.get();
    }

    void failNext(String message) {
        nextFailure = message;
    }

    @Override
    public int dimensions() {
        return 1024;
    }

    @Override
    public EmbeddingBatch embed(List<String> texts) {
        String failure = nextFailure;
        if (failure != null) {
            nextFailure = null;
            throw new EmbeddingException(failure);
        }
        calls.incrementAndGet();
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            float[] vector = new float[1024];
            int seed = text.hashCode();
            for (int i = 0; i < vector.length; i++) {
                seed = seed * 1_103_515_245 + 12_345;
                vector[i] = (seed >>> 8) / (float) (1 << 24) - 0.5f;
            }
            vectors.add(vector);
        }
        // Roughly a token per four characters, so the budget assertions have something real to
        // count without pretending this is the provider's own accounting.
        long tokens = texts.stream().mapToLong(t -> Math.max(1, t.length() / 4)).sum();
        // Same contract as the real client: the implementation records its own usage.
        budget.record(1, tokens);
        return new EmbeddingBatch(vectors, 1, tokens);
    }
}
