package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns a {@link ProtocolReceivedEvent} into an indexing run on a worker thread.
 *
 * <p><b>This listener is the seam ADR-001 names.</b> Extracting the ingestion service in Phase 5
 * replaces this class with a Kafka consumer in a separate deployable and changes nothing else: the
 * publisher still publishes an id, the indexer still reads the row, and the status still moves
 * {@code RECEIVED → INDEXED | FAILED}. Keeping the listener this thin — no logic, only dispatch —
 * is what keeps that true.
 *
 * <p>{@code AFTER_COMMIT} matters. The upload writes the protocol row and publishes in one
 * transaction; a worker that started before that commit would look for a row that is not visible
 * yet and fail a protocol that is perfectly fine. {@code fallbackExecution} lets the backlog run,
 * which publishes outside a transaction, use exactly the same path rather than a second one.
 */
@Component
class IngestionEventListener {

    private final ProtocolIndexer indexer;

    IngestionEventListener(ProtocolIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * Starts indexing once the upload's transaction has committed.
     *
     * <p>AFTER_COMMIT is the contract, not a default: the indexer runs on another thread and would
     * otherwise read a protocol row that the uploading transaction has not made visible yet — and,
     * worse, would index one that later rolled back. {@code fallbackExecution} keeps the listener
     * working when a caller publishes outside a transaction, which the seed runner does.
     */
    @Async(IngestionAsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onProtocolReceived(ProtocolReceivedEvent event) {
        // index() records its own failures as protocol state; nothing is thrown back at a thread
        // pool that has nowhere to report it.
        indexer.index(event.protocolId());
    }
}
