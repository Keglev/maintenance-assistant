package com.keglevich.maintenanceassistant.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

/**
 * The NFR-7 spend ceiling for the query path: a global daily limit on chat calls.
 *
 * <p>Global <em>and</em> per user, unlike ingestion. The per-user Bucket4j limit in
 * {@link QueryRateLimiter} stops one person hammering the endpoint; this stops the aggregate,
 * whether it arrives from twenty demo visitors or from one person patiently spacing their requests.
 * Two different shapes of the same risk, so two different guards — neither substitutes for the
 * other, which is why the ingestion counter and this one are also separate rows in separate tables.
 *
 * <p>Counted in <em>provider calls</em>, and counted by the client as each request is served rather
 * than by the caller on success. That is not a preference: the first live run of the ingestion
 * pipeline made 150 paid calls that a caller-side counter never saw, because every one of them
 * failed while converting the response and only the success path did the counting.
 *
 * <p>ADR-002 records that the provider offers cost alerts and no hard cap, so this counter is the
 * actual ceiling — which is why it lives in Postgres and survives a restart.
 */
@Component
class ChatBudget {

    private static final Logger log = LoggerFactory.getLogger(ChatBudget.class);

    /** IONOS list price for Llama-3.3-70B-Instruct, EUR per million tokens, input and output alike. */
    private static final double EUR_PER_MILLION_TOKENS = 0.65;

    private final JdbcClient jdbc;
    private final ChatProperties properties;

    ChatBudget(JdbcClient jdbc, ChatProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Checked before a question is sent, not after.
     *
     * <p>{@code estimatedCalls} is 2 when a Mode A answer may fall through to Mode B, so a question
     * cannot start on the last unit of budget and then be unable to finish honestly.
     *
     * @throws BudgetExhaustedException if today's ceiling would be exceeded
     */
    void checkHeadroom(int estimatedCalls) {
        int used = usedToday();
        if (used + estimatedCalls > properties.dailyCallBudget()) {
            throw new BudgetExhaustedException(
                    "daily chat budget reached: %d of %d calls used today"
                            .formatted(used, properties.dailyCallBudget()));
        }
    }

    /**
     * Records what a call actually cost.
     *
     * <p>Its own transaction, because the money is spent whether or not the surrounding request goes
     * on to succeed. Rolling the counter back with a failed answer would make a retry loop invisible
     * to the budget — exactly the case it exists for.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(int calls, long promptTokens, long completionTokens) {
        if (calls <= 0) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO chat_budget (usage_date, calls, prompt_tokens, completion_tokens, updated_at)
                        VALUES (:day, :calls, :promptTokens, :completionTokens, now())
                        ON CONFLICT (usage_date) DO UPDATE SET
                            calls             = chat_budget.calls + EXCLUDED.calls,
                            prompt_tokens     = chat_budget.prompt_tokens + EXCLUDED.prompt_tokens,
                            completion_tokens = chat_budget.completion_tokens + EXCLUDED.completion_tokens,
                            updated_at        = now()
                        """)
                .param("day", LocalDate.now())
                .param("calls", calls)
                .param("promptTokens", promptTokens)
                .param("completionTokens", completionTokens)
                .update();
    }

    int usedToday() {
        return jdbc.sql("SELECT coalesce(max(calls), 0) FROM chat_budget WHERE usage_date = :day")
                .param("day", LocalDate.now())
                .query(Integer.class)
                .single();
    }

    /** NFR-7's third layer is visibility: a spend nobody can read is not a controlled spend. */
    Usage usageToday() {
        return jdbc.sql("""
                        SELECT coalesce(max(calls), 0)             AS calls,
                               coalesce(max(prompt_tokens), 0)     AS prompt_tokens,
                               coalesce(max(completion_tokens), 0) AS completion_tokens
                        FROM chat_budget WHERE usage_date = :day
                        """)
                .param("day", LocalDate.now())
                .query((rs, rowNum) -> new Usage(
                        rs.getInt("calls"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        properties.dailyCallBudget()))
                .single();
    }

    void logUsage() {
        Usage usage = usageToday();
        double eur = (usage.promptTokens() + usage.completionTokens()) / 1_000_000.0 * EUR_PER_MILLION_TOKENS;
        log.info("Chat usage today: {} of {} calls, {} in / {} out tokens (~EUR {})",
                usage.calls(), usage.dailyCallBudget(), usage.promptTokens(), usage.completionTokens(),
                String.format(Locale.ROOT, "%.4f", eur));
    }

    /** Today's spend, as the ingestion status endpoint reports its own. */
    record Usage(int calls, long promptTokens, long completionTokens, int dailyCallBudget) {
    }

    /** Thrown when today's ceiling is reached. Becomes a 503 with a readable message. */
    static class BudgetExhaustedException extends RuntimeException {
        BudgetExhaustedException(String message) {
            super(message);
        }
    }
}
