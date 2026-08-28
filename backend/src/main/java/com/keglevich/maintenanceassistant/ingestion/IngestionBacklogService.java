package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The catch-up path.
 *
 * <p>The 150 seeded protocols were written straight into the database by the corpus seed, which
 * predates this pipeline and publishes no event. They sit at {@code RECEIVED} with no worker
 * coming. A protocol that failed on a provider outage is in the same position: the event that
 * would have indexed it is long gone.
 *
 * <p>So this finds them by state rather than by event — the state <em>is</em> the queue — and
 * republishes the same {@link ProtocolReceivedEvent} the upload path uses, so there is one indexing
 * path and not two.
 *
 * <p>Re-running is safe. Indexing replaces a protocol's chunks rather than adding to them
 * ({@link ProtocolIndexWriter}), so a protocol picked up twice ends with the chunks the current
 * code produces, once.
 */
@Service
public class IngestionBacklogService {

    private static final Logger log = LoggerFactory.getLogger(IngestionBacklogService.class);

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final IngestionProperties properties;
    private final EmbeddingProperties embeddingProperties;
    private final EmbeddingBudget budget;

    IngestionBacklogService(JdbcClient jdbc, ApplicationEventPublisher events,
                            IngestionProperties properties, EmbeddingProperties embeddingProperties,
                            EmbeddingBudget budget) {
        this.jdbc = jdbc;
        this.events = events;
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
        this.budget = budget;
    }

    /**
     * @param statuses which states to pick up; {@code RECEIVED} for a first pass, {@code FAILED}
     *                 for a retry. Archived protocols are never picked up: a protocol soft-deleted
     *                 while it was still RECEIVED would otherwise be a permanent backlog entry that
     *                 rebuilds its own chunks on every run — a deletion the next catch-up undoes.
     */
    public int enqueue(List<String> statuses, Integer limit) {
        int max = limit != null && limit > 0 ? limit : properties.backlogBatchSize();
        List<UUID> pending = jdbc.sql("""
                        SELECT id FROM protocol
                        WHERE status IN (:statuses)
                          AND deleted_at IS NULL
                        ORDER BY created_at
                        LIMIT :max
                        """)
                .param("statuses", statuses)
                .param("max", max)
                .query(UUID.class)
                .list();

        if (pending.isEmpty()) {
            log.info("Backlog: nothing in {}", statuses);
            return 0;
        }

        log.info("Backlog: enqueueing {} protocols in {} ({} embedding calls used today)",
                pending.size(), statuses, budget.usedToday());
        // Submission is bounded by the executor's queue; the overflow policy runs the excess on
        // this thread, so a 150-protocol backlog throttles rather than being dropped.
        pending.forEach(id -> events.publishEvent(new ProtocolReceivedEvent(id)));
        return pending.size();
    }

    /**
     * Counts by status, for the operator answering "is it done yet".
     *
     * <p>Live protocols only. The question behind this number is "is the corpus indexed", and an
     * archived protocol is not part of the corpus — counting it would report a permanent shortfall
     * of RECEIVED rows that no backlog run can ever clear, because the backlog correctly refuses to
     * pick them up.
     */
    public List<StatusCount> statusCounts() {
        return jdbc.sql("""
                        SELECT status, count(*) AS n FROM protocol
                        WHERE deleted_at IS NULL
                        GROUP BY status ORDER BY status
                        """)
                .query((rs, rowNum) -> new StatusCount(rs.getString("status"), rs.getLong("n")))
                .list();
    }

    /**
     * Today's provider usage and what is left of the ceiling.
     *
     * <p>Reported alongside the status because the two questions are asked together: whether the
     * corpus is indexed, and what it cost. NFR-7's third layer is visibility, and a number nobody
     * can read without a database session is not visible.
     */
    public Usage usageToday() {
        int used = budget.usedToday();
        long tokens = budget.tokensToday();
        return new Usage(used, Math.max(0, dailyBudget() - used), tokens,
                // ADR-002 prices bge-m3 at EUR 0.02 per million input tokens; embeddings bill input
                // only. Reported in EUR so "measured in cents" stays a measurement.
                Math.round(tokens / 1_000_000.0 * 0.02 * 100_000.0) / 100_000.0);
    }

    private int dailyBudget() {
        return embeddingProperties.dailyCallBudget();
    }

    /**
     * How many protocols sit in one indexing state, as the status endpoint reports them.
     *
     * <p>{@code status} is the database's own string rather than an enum: the set is owned by the
     * schema and a new state added there should surface in the operator view without a code change
     * that only renames it.
     */
    public record StatusCount(String status, long count) {
    }

    /** @param estimatedEur input tokens priced at the ADR-002 rate, rounded to five decimals */
    public record Usage(int callsToday, int callsRemaining, long promptTokensToday, double estimatedEur) {
    }
}
