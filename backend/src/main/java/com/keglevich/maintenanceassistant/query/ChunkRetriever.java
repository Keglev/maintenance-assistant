package com.keglevich.maintenanceassistant.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Retrieval: one SQL statement that filters by machine and ranks by vector distance.
 *
 * <p><b>One statement is the whole point of ADR-004.</b> The alternative architecture — a dedicated
 * vector database — would make this two lookups against two stores that have to agree with each
 * other, and the "which machine is this chunk on" half would either be duplicated into the vector
 * store or applied after ranking, which silently returns fewer than {@code topK} results. Here the
 * filter is a column on {@code chunk}, denormalized by ingestion for exactly this query, so the
 * relational predicate and the nearest-neighbour ordering are evaluated together and the answer is
 * always the top {@code k} <em>of this machine</em>.
 *
 * <p>{@code <=>} is pgvector's cosine distance, and it is the operator the HNSW index in V1 was
 * built for ({@code vector_cosine_ops}); an index built for a different distance function would be
 * silently unused by this query. Similarity is reported as {@code 1 - distance}, which is what the
 * threshold in {@link QueryProperties} is expressed in and what ADR-002's measured demo figures are.
 */
@Component
class ChunkRetriever {

    private final JdbcClient jdbc;

    ChunkRetriever(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The top {@code topK} chunks for this machine, best first.
     *
     * @param questionVector the embedded question, same model and width as the stored chunks
     */
    List<RetrievedChunk> retrieve(UUID machineId, float[] questionVector, int topK) {
        String vector = toVectorLiteral(questionVector);
        return jdbc.sql("""
                        SELECT c.id                                                AS chunk_id,
                               c.protocol_id                                       AS protocol_id,
                               c.content                                           AS content,
                               p.title                                             AS title,
                               p.error_code                                        AS error_code,
                               p.language                                          AS language,
                               p.incident_date                                     AS incident_date,
                               1 - (c.embedding <=> CAST(:vector AS vector))       AS similarity
                        FROM chunk c
                        JOIN protocol p ON p.id = c.protocol_id
                        WHERE c.machine_id = :machineId
                          AND c.embedding IS NOT NULL
                        ORDER BY c.embedding <=> CAST(:vector AS vector)
                        LIMIT :topK
                        """)
                // CAST(... AS vector) rather than the ::vector shorthand, because the named-parameter
                // parser reads `::` as a parameter marker — the same trap ProtocolIndexWriter hit.
                .param("vector", vector)
                .param("machineId", machineId)
                .param("topK", topK)
                .query((rs, rowNum) -> new RetrievedChunk(
                        rs.getObject("chunk_id", UUID.class),
                        rs.getObject("protocol_id", UUID.class),
                        rs.getString("content"),
                        rs.getString("title"),
                        rs.getString("error_code"),
                        rs.getString("language"),
                        rs.getObject("incident_date", LocalDate.class),
                        rs.getDouble("similarity")))
                .list();
    }

    /** Whether a machine exists at all — an unknown id is the caller's mistake, not an empty answer. */
    boolean machineExists(UUID machineId) {
        return jdbc.sql("SELECT count(*) FROM machine WHERE id = :id")
                .param("id", machineId)
                .query(Long.class)
                .single() > 0;
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
}
