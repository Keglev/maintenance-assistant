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
     * <p>The {@code deleted_at IS NULL} clause is <b>redundant and stays anyway</b>. Soft deletion
     * removes the chunks first, so an archived protocol has nothing here to match — retrieval's
     * safety is structural rather than filtered, which is the property ADR-006's revision leans on.
     * This line is what stands between a future bug in that ordering and a deleted protocol being
     * cited in a green Mode A answer, and it costs a null check on a row already joined.
     *
     * @param questionVector the embedded question, same model and width as the stored chunks
     * @param approvedOnly   narrow retrieval to protocols an administrator has vouched for.
     *                       <b>False by default, and that is the decision of 2026-08-11, not an
     *                       oversight:</b> the admin may not review at a weekend and the factory
     *                       does not stop, so the newest protocol — the one about the fault
     *                       happening now — must be findable before anyone signs it off. The
     *                       parameter exists so a caller can ASK for the reviewed subset; it must
     *                       never quietly become the default.
     */
    List<RetrievedChunk> retrieve(UUID machineId, float[] questionVector, int topK,
                                  boolean approvedOnly, List<String> lexicalTerms,
                                  double lexicalWeight) {
        String vector = toVectorLiteral(questionVector);
        // Divisor, never zero: with no terms every match count is 0, so the boost is 0 * anything
        // and the ORDER BY collapses to the pure vector ordering this query had before ADR-009.
        int termCount = Math.max(1, lexicalTerms.size());

        return jdbc.sql("""
                        SELECT c.id                                                AS chunk_id,
                               c.protocol_id                                       AS protocol_id,
                               c.content                                           AS content,
                               p.title                                             AS title,
                               p.error_code                                        AS error_code,
                               p.language                                          AS language,
                               p.incident_date                                     AS incident_date,
                               p.approval_state                                    AS approval_state,
                               1 - (c.embedding <=> CAST(:vector AS vector))       AS similarity,
                               (SELECT count(*)
                                  FROM unnest(string_to_array(:terms, ' ')) AS term
                                 WHERE term <> '' AND c.content ILIKE '%' || term || '%')
                                                                                   AS lexical_matches
                        FROM chunk c
                        JOIN protocol p ON p.id = c.protocol_id
                        WHERE c.machine_id = :machineId
                          AND c.embedding IS NOT NULL
                          AND p.deleted_at IS NULL
                          AND (NOT :approvedOnly OR p.approval_state = 'APPROVED')
                        ORDER BY (1 - (c.embedding <=> CAST(:vector AS vector)))
                                 + :lexicalWeight * (CAST((SELECT count(*)
                                        FROM unnest(string_to_array(:terms, ' ')) AS term
                                       WHERE term <> '' AND c.content ILIKE '%' || term || '%')
                                     AS float8) / :termCount)
                                 DESC
                        LIMIT :topK
                        """)
                // CAST(... AS vector) rather than the ::vector shorthand, because the named-parameter
                // parser reads `::` as a parameter marker — the same trap ProtocolIndexWriter hit.
                // Every cast in this statement is spelled the long way for that reason.
                .param("vector", vector)
                .param("machineId", machineId)
                .param("topK", topK)
                .param("approvedOnly", approvedOnly)
                .param("terms", LexicalTerms.joined(lexicalTerms))
                .param("lexicalWeight", lexicalWeight)
                .param("termCount", termCount)
                .query((rs, rowNum) -> new RetrievedChunk(
                        rs.getObject("chunk_id", UUID.class),
                        rs.getObject("protocol_id", UUID.class),
                        rs.getString("content"),
                        rs.getString("title"),
                        rs.getString("error_code"),
                        rs.getString("language"),
                        rs.getObject("incident_date", LocalDate.class),
                        rs.getDouble("similarity"),
                        rs.getInt("lexical_matches"),
                        "APPROVED".equals(rs.getString("approval_state"))))
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
