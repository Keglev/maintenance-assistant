package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * The NFR-7 stop for ingestion: a global daily ceiling on provider calls.
 *
 * <p>Global rather than per user, because ingestion is background load. A Schichtleiter uploads a
 * protocol and a worker embeds it later; the shape of abuse this guards against is a re-index storm
 * or a retry loop, not one person clicking too often. Per-user rate limiting belongs to the query
 * path in Phase 3, where a request maps to a person.
 *
 * <p>Counted in <em>provider calls</em>, not chunks, because that is the unit the provider bills
 * and rate-limits. The stop is graceful: {@link BudgetExhaustedException} fails the protocol being
 * worked on with a readable reason, leaves its row and file in place, and the backlog run picks it
 * up again the next day. Nothing is lost, and the spend stops.
 *
 * <p>ADR-002 records that no provider-side hard cap exists — IONOS offers cost alerts only — so
 * this counter is the actual ceiling, which is why it lives in the database and survives a restart
 * rather than being an in-memory counter.
 */
@Component
class EmbeddingBudget {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBudget.class);

    private final JdbcClient jdbc;
    private final EmbeddingProperties properties;

    EmbeddingBudget(JdbcClient jdbc, EmbeddingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Reserves headroom before a protocol is embedded.
     *
     * <p>Checked per protocol rather than per batch: a protocol that runs out of budget halfway
     * would be left with some of its chunks embedded and the rest not, which is worse than not
     * starting it. {@code estimatedCalls} is what that protocol will need.
     *
     * @throws BudgetExhaustedException if today's ceiling would be exceeded
     */
    void checkHeadroom(int estimatedCalls) {
        int used = usedToday();
        if (used + estimatedCalls > properties.dailyCallBudget()) {
            throw new BudgetExhaustedException(
                    "daily embedding budget reached: %d of %d calls used today, %d more needed"
                            .formatted(used, properties.dailyCallBudget(), estimatedCalls));
        }
    }

    /**
     * Records what a run actually cost.
     *
     * <p>Its own transaction: the usage happened and the money is spent whether or not the
     * surrounding protocol transaction goes on to succeed. Rolling the counter back with a failed
     * protocol would make a retry loop invisible to the budget — exactly the case it exists for.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(int calls, long promptTokens) {
        if (calls <= 0) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO embedding_budget (usage_date, calls, prompt_tokens, updated_at)
                        VALUES (:day, :calls, :tokens, now())
                        ON CONFLICT (usage_date) DO UPDATE SET
                            calls         = embedding_budget.calls + EXCLUDED.calls,
                            prompt_tokens = embedding_budget.prompt_tokens + EXCLUDED.prompt_tokens,
                            updated_at    = now()
                        """)
                .param("day", LocalDate.now())
                .param("calls", calls)
                .param("tokens", promptTokens)
                .update();
    }

    /**
     * Today's call count, or zero on a day nothing has been embedded yet.
     *
     * <p>{@code coalesce(max(...), 0)} rather than a plain select, and the aggregate is the whole
     * point: there is at most one row per day, so {@code max} returns that row's value — but on a
     * day with no row it returns one NULL row rather than none, which {@code coalesce} turns into
     * zero. A plain select would return no rows at all and {@code single()} would throw on the
     * first call of every day.
     */
    int usedToday() {
        return jdbc.sql("SELECT coalesce(max(calls), 0) FROM embedding_budget WHERE usage_date = :day")
                .param("day", LocalDate.now())
                .query(Integer.class)
                .single();
    }

    /**
     * Today's prompt-token total, for the operator view rather than for the guard.
     *
     * <p>Reported and never enforced: the ceiling this class defends is a CALL count, because that
     * is what the provider bills per request and what {@link #checkHeadroom(int)} can predict
     * before a run. Tokens are only knowable afterwards, so a token budget would refuse work on
     * yesterday's evidence. Same zero-on-empty-day reasoning as {@link #usedToday()}.
     */
    long tokensToday() {
        return jdbc.sql("SELECT coalesce(max(prompt_tokens), 0) FROM embedding_budget WHERE usage_date = :day")
                .param("day", LocalDate.now())
                .query(Long.class)
                .single();
    }

    /**
     * Written at the end of a run rather than per protocol. ADR-002 prices bge-m3 at EUR 0.02 per
     * million input tokens, so this is a reading in cents by design — the point of logging it is
     * that "measured in cents" stays a measurement and not an assumption.
     */
    void logUsage(String context) {
        long tokens = tokensToday();
        double eur = tokens / 1_000_000.0 * 0.02;
        log.info("Embedding usage after {}: {} calls, {} input tokens today (~EUR {}), budget {}",
                context, usedToday(), tokens, String.format(java.util.Locale.ROOT, "%.4f", eur),
                properties.dailyCallBudget());
    }

    /** Thrown when today's ceiling is reached. The message becomes {@code protocol.failure_reason}. */
    static class BudgetExhaustedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        BudgetExhaustedException(String message) {
            super(message);
        }
    }
}
