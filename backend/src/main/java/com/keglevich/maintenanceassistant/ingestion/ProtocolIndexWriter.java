package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The database side of indexing, kept apart from {@link ProtocolIndexer} for one reason: the
 * transaction must not span the embedding call.
 *
 * <p>Embedding a protocol is an HTTP round trip measured in seconds. Holding a pooled connection
 * open across it would let one slow provider response occupy a connection that the web layer needs,
 * and a provider outage would hold the whole pool. So the indexer reads, embeds and only then calls
 * in here, where the write is a short transaction that either completes or leaves nothing behind.
 */
@Component
class ProtocolIndexWriter {

    private final JdbcClient jdbc;

    ProtocolIndexWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces a protocol's chunks and marks it indexed, atomically.
     *
     * <p>Delete-then-insert rather than upsert, which is what makes re-processing idempotent: a
     * protocol re-indexed after its chunking or its embedding model changed must end up with
     * exactly the chunks the current code produces, not those plus a previous generation's. Running
     * it twice leaves the same rows; running it after a change leaves the new ones.
     */
    @Transactional
    void writeChunks(UUID protocolId, UUID machineId, String errorCode, String language,
                     List<String> chunkTexts, List<float[]> vectors) {

        jdbc.sql("DELETE FROM chunk WHERE protocol_id = :protocolId")
                .param("protocolId", protocolId)
                .update();

        for (int i = 0; i < chunkTexts.size(); i++) {
            jdbc.sql("""
                            INSERT INTO chunk (
                                id, protocol_id, chunk_index, content, embedding,
                                language, machine_id, error_code)
                            VALUES (
                                :id, :protocolId, :chunkIndex, :content, CAST(:embedding AS vector),
                                :language, :machineId, :errorCode)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("protocolId", protocolId)
                    .param("chunkIndex", i)
                    .param("content", chunkTexts.get(i))
                    // pgvector's text input format. CAST(... AS vector) rather than the `::vector`
                    // shorthand because the named-parameter parser reads `::` as a parameter marker.
                    .param("embedding", toVectorLiteral(vectors.get(i)))
                    .param("language", language)
                    // Denormalized from the protocol so Phase 3 filters and ranks in one query
                    // (ADR-004). Rewritten here on every re-index, which is the only place a
                    // protocol's machine or error code could have moved.
                    .param("machineId", machineId)
                    .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                    .update();
        }

        jdbc.sql("""
                        UPDATE protocol
                        SET status = 'INDEXED', indexed_at = :now, failure_reason = NULL, updated_at = :now
                        WHERE id = :protocolId
                        """)
                .param("protocolId", protocolId)
                .param("now", OffsetDateTime.now())
                .update();
    }

    /**
     * Records a failure without touching the chunks or the file.
     *
     * <p>Its own transaction, because the failure has to be recorded even when the work that failed
     * was rolled back — a FAILED protocol with no reason is the state this column exists to prevent.
     */
    @Transactional
    void markFailed(UUID protocolId, String reason) {
        jdbc.sql("""
                        UPDATE protocol
                        SET status = 'FAILED', failure_reason = :reason, indexed_at = NULL, updated_at = :now
                        WHERE id = :protocolId
                        """)
                .param("protocolId", protocolId)
                .param("reason", truncate(reason))
                .param("now", OffsetDateTime.now())
                .update();
    }

    /** {@code [0.1,-0.2,…]} — pgvector's own input syntax. */
    private static String toVectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder(vector.length * 12 + 2);
        out.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        return out.append(']').toString();
    }

    /** A provider can return a very long error body; the useful part is always at the front. */
    private static String truncate(String reason) {
        if (reason == null) {
            return "unknown";
        }
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000) + "…";
    }
}
