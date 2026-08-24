package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The one thing the chat budget does without a database: refuse to record nothing.
 *
 * <p>{@code record} runs in its own {@code REQUIRES_NEW} transaction, because the money is spent
 * whether or not the surrounding request succeeds. That makes a no-op call expensive in the only way
 * that matters here — it would open a transaction and write a row for a call that never happened,
 * on the hot path of every question. The guard returns before touching the database at all.
 *
 * <p><b>The null {@code JdbcClient} is the assertion.</b> Passing one and watching the call return
 * quietly proves the guard short-circuits ahead of any query; if the guard were removed this would
 * fail with a {@code NullPointerException} rather than passing for the wrong reason.
 *
 * <p>OUT OF SCOPE: the headroom check and the counters themselves, which need the real table and
 * belong to the integration suites.
 *
 * <p>SIBLING: EmbeddingBudgetTest, which pins the identical rule on the ingestion side.
 */
class ChatBudgetTest {

    private static final ChatProperties PROPERTIES = new ChatProperties(
            "https://api/v1", "secret", "meta-llama/Llama-3.3-70B-Instruct", 1200, 0.2,
            Duration.ofSeconds(20), 1, Duration.ofMillis(500), 200);

    @Test
    void record_zeroCalls_writesNothingAtAll() {
        assertThatCode(() -> new ChatBudget(null, PROPERTIES).record(0, 812L, 195L))
                .doesNotThrowAnyException();
    }

    @Test
    void record_aNegativeCallCount_writesNothingAtAll() {
        // Not reachable from the client today, and guarded anyway: a negative count would otherwise
        // subtract from the daily total and buy back budget that was already spent.
        assertThatCode(() -> new ChatBudget(null, PROPERTIES).record(-1, 0L, 0L))
                .doesNotThrowAnyException();
    }
}
