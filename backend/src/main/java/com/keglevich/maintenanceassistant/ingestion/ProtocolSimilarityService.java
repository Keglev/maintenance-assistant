package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Does this protocol already exist?" — asked of the vectors the corpus already has, and answered
 * to an approver who then decides.
 *
 * <h2>SIMILARITY WARNS. IT NEVER BLOCKS.</h2>
 *
 * <p>This is the governing rule of the feature and it is not a preference. Nothing in this class,
 * in {@link ProtocolApprovalService} or in the controller refuses an approval, downgrades one, or
 * makes one conditional on a score. The only thing a high similarity does is put three links in
 * front of a human.
 *
 * <p><b>The E-47 seed is why.</b> Four protocols on PR-03 carry the fault code E-47: a worn piston
 * seal, a pressure-relief valve sticking, a programme change, and a slow build-up. Four different
 * root causes, four legitimate protocols, all four correctly cited together in the demo answer. They
 * are the tightest <em>fault</em> cluster in the corpus and they score 0.76–0.83 against each other
 * (measured; see {@code DuplicateSimilarityCalibrationIT}). Any threshold low enough to be
 * "sensitive" flags all six of their pairs, and a system that refused the fourth E-47 protocol would
 * have removed the exact knowledge that makes the demo answer good. Duplicate detection that blocks
 * would make this corpus worse, not better.
 *
 * <h2>What is compared: the PROTOCOL, as the mean of its chunks</h2>
 *
 * <p>Each protocol is reduced to one vector — the centroid of its chunk embeddings — and two
 * protocols are compared by the cosine similarity of their centroids. The obvious alternative is the
 * best chunk-to-chunk match, {@code max} over every pair of chunks, and it was measured alongside
 * this one and rejected:
 *
 * <ul>
 *   <li><b>A maximum over a set that grows with document length is not a comparison of documents.</b>
 *       A protocol with six chunks gets six chances to score high on one of them. Under chunk-max,
 *       adding a paragraph the other document does not contain <em>cannot lower</em> the score —
 *       the statistic is monotone in length. That is precisely the "two long protocols overlapping
 *       in one paragraph" case, and it would read as a duplicate.</li>
 *   <li><b>The centroid is length-aware in the right direction.</b> Material the other document does
 *       not contain moves the mean away from it, which is what "these two documents say different
 *       things" should do to a number.</li>
 *   <li><b>It is measurable in this corpus.</b> E-47 #1 (two chunks) against #13 (one chunk) scores
 *       0.8205 by chunk-max and 0.7698 by centroid. The case where chunk-max reads distinctly higher
 *       is exactly the shape the false positive has: one section of a longer document matching a
 *       shorter one whole.</li>
 * </ul>
 *
 * <p>The cost is stated rather than hidden: a genuine duplicate buried inside a long protocol is
 * <em>diluted</em> by the centroid and may fall below the threshold. That is the side to err on for
 * a feature whose whole premise is not crying wolf — a missed warning costs a second protocol in the
 * corpus, which this system tolerates by design, while a false one costs the approver's trust in
 * every warning after it.
 *
 * <h2>What it costs</h2>
 *
 * <p>One statement, no provider call, and nothing new written. The candidate's chunks already exist
 * — it was indexed when it was filed — so this asks the vectors a question rather than computing new
 * ones. It runs on an administrator's click and on the approval that follows, never per search.
 *
 * <p><b>Measured</b> with {@code EXPLAIN ANALYZE} against the 165-protocol corpus (182 chunks, 25
 * protocols on the busiest machine): <b>1.7 ms execution, 4.8 ms planning</b>. It is a sequential
 * scan of {@code chunk} and deliberately does not use the HNSW index — that index answers "nearest
 * neighbours of this vector", and this asks for an aggregate per protocol, which is a different
 * question. The cost is therefore linear in the corpus's chunk count rather than logarithmic. At
 * this size that is 1.7 ms; a hundredfold corpus would be a fifth of a second on a click, which is
 * still the right trade for a query nobody runs in a loop. If it ever stops being, the fix is a
 * stored per-protocol centroid, not an index this shape of query cannot use.
 *
 * <p>Archived protocols are excluded. They are out of every answer and out of every list; offering
 * one as "the protocol this duplicates" would invite an approver to compare against something the
 * corpus no longer contains and cannot cite.
 */
@Service
public class ProtocolSimilarityService {

    private final JdbcClient jdbc;
    private final DuplicateProperties properties;

    ProtocolSimilarityService(JdbcClient jdbc, DuplicateProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Another protocol on the same machine that says close to what this one says.
     *
     * @param similarity cosine similarity of the two protocols' centroids, 0..1
     * @param approval   <b>the candidate's own approval state, and it is the point of the card
     *                   rather than a detail on it.</b> "Nearly the same as a protocol an
     *                   administrator already vouched for" is a merge-or-reject question; "nearly
     *                   the same as one nobody has reviewed" may well be two people describing the
     *                   same fault from different angles, which this corpus wants both of.
     */
    public record SimilarProtocol(UUID id, String title, LocalDate incidentDate, String uploadedBy,
                                  OffsetDateTime uploadedAt, double similarity,
                                  ProtocolApprovalService.Approval approval) {
    }

    /**
     * What the approver is told, and what the ledger records.
     *
     * @param comparable false when the protocol has no embedded chunks yet — it was filed seconds
     *                   ago, or its indexing failed. Reported rather than folded into "nothing
     *                   similar found", because those are different facts and only one of them is a
     *                   statement about the corpus. Nothing is blocked either way.
     * @param candidates the most similar first, at most {@link DuplicateProperties#maxCandidates()}
     * @param total      how many cleared the threshold in all. Reported separately so a long tail is
     *                   visible rather than silently truncated to three.
     * @param allIds     every protocol above the threshold, not only the three shown. <b>This is the
     *                   ledger's field, not the screen's.</b> The auditor's question is "did anybody
     *                   notice these two say the same thing", and an audit row that named three ids
     *                   out of five would answer it wrongly while looking complete. The interface
     *                   ignores it and reads {@code candidates}.
     * @param threshold  the number this run used, sent to the client so no screen and no test can
     *                   hard-code a value the configuration is free to change
     */
    public record SimilarityReport(boolean comparable, List<SimilarProtocol> candidates, int total,
                                   List<UUID> allIds, double threshold) {

        /** Whether an approver has anything to look at. */
        public boolean any() {
            return !candidates.isEmpty();
        }
    }

    /**
     * Every live protocol on this machine whose centroid is at least as close as the threshold.
     *
     * <p>One statement, in the ADR-004 tradition: the "same machine" predicate and the
     * nearest-neighbour ordering are evaluated together, so the answer is always the most similar
     * <em>of this machine</em> rather than a global ranking filtered afterwards.
     *
     * <p>The comparison is scoped to one machine and that is a domain rule, not an optimisation. A
     * seal replacement on PR-03 and a seal replacement on PR-07 are two maintenance events, not one
     * written twice; the whole plant would otherwise be full of "duplicates" that are simply the
     * same job done on different equipment.
     *
     * <p>{@code <=>} is pgvector's cosine distance and similarity is {@code 1 - distance}, the same
     * convention {@code ChunkRetriever} and ADR-002's measured figures use — one number to reason
     * about, even though the two thresholds it is compared against are answering different questions.
     */
    public SimilarityReport findSimilar(UUID protocolId) {
        List<SimilarProtocol> above = jdbc.sql("""
                        WITH subject AS (
                            SELECT p.machine_id, avg(c.embedding) AS centroid
                            FROM protocol p JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.id = :id
                              AND p.deleted_at IS NULL
                              AND c.embedding IS NOT NULL
                            GROUP BY p.machine_id
                        ),
                        others AS (
                            SELECT p.id, p.title, p.incident_date, p.uploaded_by, p.created_at,
                                   p.approval_state, p.approved_by, p.approved_at,
                                   avg(c.embedding) AS centroid
                            FROM protocol p JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.machine_id = (SELECT machine_id FROM subject)
                              AND p.id <> :id
                              AND p.deleted_at IS NULL
                              AND c.embedding IS NOT NULL
                            GROUP BY p.id
                        )
                        SELECT o.id, o.title, o.incident_date, o.uploaded_by, o.created_at,
                               o.approval_state, o.approved_by, o.approved_at,
                               1 - (o.centroid <=> s.centroid) AS similarity
                        FROM others o CROSS JOIN subject s
                        WHERE 1 - (o.centroid <=> s.centroid) >= :threshold
                        ORDER BY similarity DESC, o.id
                        """)
                // NOT limited in SQL, deliberately. The result is bounded by one machine's corpus
                // and the count of everything above the threshold is part of what the approver is
                // told — a query that returned three rows could not distinguish "three similar
                // protocols" from "eleven, and here are three of them".
                .param("id", protocolId)
                .param("threshold", properties.similarityThreshold())
                .query((rs, rowNum) -> new SimilarProtocol(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("incident_date", LocalDate.class),
                        rs.getString("uploaded_by"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getDouble("similarity"),
                        new ProtocolApprovalService.Approval(
                                rs.getString("approval_state"),
                                rs.getString("approved_by"),
                                rs.getObject("approved_at", OffsetDateTime.class))))
                .list();

        boolean comparable = indexed(protocolId);
        int limit = Math.max(1, properties.maxCandidates());
        return new SimilarityReport(comparable,
                List.copyOf(above.subList(0, Math.min(limit, above.size()))),
                above.size(),
                above.stream().map(SimilarProtocol::id).toList(),
                properties.similarityThreshold());
    }

    /**
     * Whether this protocol has anything to compare with.
     *
     * <p>A second, trivial query rather than an outer join on the first: an empty result from the
     * statement above means either "nothing is similar" or "this protocol has no vectors", and those
     * two facts must not arrive as the same empty list.
     */
    private boolean indexed(UUID protocolId) {
        return jdbc.sql("""
                        SELECT count(*) FROM chunk
                        WHERE protocol_id = :id AND embedding IS NOT NULL
                        """)
                .param("id", protocolId)
                .query(Long.class)
                .single() > 0;
    }
}
