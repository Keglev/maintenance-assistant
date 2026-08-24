package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The one thing the embedding budget does without a database: refuse to record nothing.
 *
 * <p>{@code record} runs in its own {@code REQUIRES_NEW} transaction, because the usage happened and
 * the money is spent whether or not the surrounding protocol transaction goes on to succeed. That
 * makes a no-op call expensive in the only way that matters — it would open a transaction and write
 * a row for a call that never happened, once per batch of a seeding run. The guard returns before
 * touching the database at all.
 *
 * <p><b>The null {@code JdbcClient} is the assertion.</b> Passing one and watching the call return
 * quietly proves the guard short-circuits ahead of any query; if the guard were removed this would
 * fail with a {@code NullPointerException} rather than passing for the wrong reason.
 *
 * <p>OUT OF SCOPE: the headroom check and the counters themselves, which need the real table and
 * belong to the integration suites.
 *
 * <p>SIBLING: ChatBudgetTest, which pins the identical rule on the query side.
 */
class EmbeddingBudgetTest {

    private static final EmbeddingProperties PROPERTIES = new EmbeddingProperties(
            "https://api/v1", "secret", "BAAI/bge-m3", 1024, 32,
            2, Duration.ofSeconds(1), Duration.ofSeconds(30), 500);

    @Test
    void record_zeroCalls_writesNothingAtAll() {
        assertThatCode(() -> new EmbeddingBudget(null, PROPERTIES).record(0, 11L))
                .doesNotThrowAnyException();
    }

    @Test
    void record_aNegativeCallCount_writesNothingAtAll() {
        // Not reachable from the client today, and guarded anyway: a negative count would otherwise
        // subtract from the daily total and buy back budget that was already spent.
        assertThatCode(() -> new EmbeddingBudget(null, PROPERTIES).record(-1, 0L))
                .doesNotThrowAnyException();
    }
}
