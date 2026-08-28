package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Does the stored index belong to the embedding model that answers questions against it?
 *
 * <p><b>The defect this exists for.</b> A vector's shape says nothing about its provenance. A row
 * written by a different embedding model is the right width, unit length, well-formed, and carries
 * {@code status = 'INDEXED'} — and it is orthogonal to every question, so the protocol simply can
 * never be retrieved. No count, no health endpoint, no functional test and no integration test can
 * see it. The 15 protocols v1.2 added sat in the development database in exactly that state until
 * the retrieval baseline (ADR-008) measured 0/3 on them and asked why.
 *
 * <p><b>The check is direct rather than statistical.</b> Re-embed a chunk's own stored text with the
 * model configured right now and compare with the vector stored beside it. The same model over the
 * same text agrees at ~0.9999; a foreign vector scores ≤0.04. There is nothing in between, so this
 * needs no tuning and no threshold argument — {@link #AGREEMENT_FLOOR} is set at 0.95 only to leave
 * room for a provider that is not bit-deterministic across calls.
 *
 * <p><b>It costs provider calls, so nothing calls it per request.</b> It has exactly two front
 * doors, and deliberately one implementation behind them: {@code EmbeddingProvenanceRunner} for an
 * operator with a database, and the key-gated {@code RetrievalBaselineIT} for a developer with a
 * corpus. Two instruments for one number is how two numbers start disagreeing.
 */
@Service
public class EmbeddingProvenanceVerifier {

    /**
     * Below this, a stored vector did not come from the configured model.
     *
     * <p>Not a tuned threshold. The two populations sit at ~0.9999 and ~0.02, so any value in the
     * wide middle gives the same answer; this one is close to 1.0 because the question being asked
     * is "is this the same model", not "is this similar".
     */
    public static final double AGREEMENT_FLOOR = 0.95;

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddingClient;

    EmbeddingProvenanceVerifier(JdbcClient jdbc, EmbeddingClient embeddingClient) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Verifies a sample of the index.
     *
     * @param sampleSize how many chunks to check; <b>zero or less means every chunk</b>. A full scan
     *                   of the present corpus is 182 chunks in 6 batched provider calls, so "all" is
     *                   affordable today and the sample exists for the corpus this becomes later
     * @return the report, with every probe in it — the passing ones too, because a report that lists
     *         only failures cannot be told apart from a report that failed to look
     */
    public Report verify(int sampleSize) {
        List<StoredChunk> chunks = sample(sampleSize);
        if (chunks.isEmpty()) {
            return new Report(List.of());
        }

        // One call for the whole sample: EmbeddingClient batches internally and the provider bills
        // per request, so probing chunk by chunk would turn a 182-chunk scan into 182 round trips.
        // Order is preserved, which is what lets the results be paired back up by index.
        List<float[]> fresh = embeddingClient.embed(chunks.stream().map(StoredChunk::content).toList())
                .vectors();

        List<Probe> probes = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            StoredChunk chunk = chunks.get(i);
            probes.add(new Probe(chunk.protocolId(), chunk.title(),
                    agreementWithStored(chunk.id(), fresh.get(i))));
        }
        return new Report(probes);
    }

    /**
     * A time-stratified sample: the newest, the oldest, and an even spread between them.
     *
     * <p>Stratified by {@code created_at} because <b>this defect travels in batches</b> — one run of
     * one wrongly configured provider writes many rows at once, so the population that is wrong is
     * almost always contiguous in time. A uniformly random sample of 24 out of 182 would have had
     * roughly even odds of missing a 15-row batch entirely; taking the newest rows first cannot miss
     * the most recent one, which is the batch a deployment or a seeding run just wrote.
     *
     * <p>The spread is by even spacing rather than {@code random()} so that two runs against an
     * unchanged database probe the same rows and can be compared.
     */
    private List<StoredChunk> sample(int sampleSize) {
        List<StoredChunk> all = jdbc.sql("""
                        SELECT c.id, c.protocol_id, c.content, p.title
                        FROM chunk c
                        JOIN protocol p ON p.id = c.protocol_id
                        WHERE p.deleted_at IS NULL AND c.embedding IS NOT NULL
                        ORDER BY p.created_at DESC, c.id
                        """)
                .query((rs, rowNum) -> new StoredChunk(rs.getObject("id", UUID.class),
                        rs.getObject("protocol_id", UUID.class), rs.getString("content"),
                        rs.getString("title")))
                .list();

        if (sampleSize <= 0 || sampleSize >= all.size()) {
            return all;
        }

        // A LinkedHashSet because the three strata overlap on a small table, and a chunk probed
        // twice would be paid for twice and counted twice.
        LinkedHashSet<StoredChunk> chosen = new LinkedHashSet<>();
        int third = Math.max(1, sampleSize / 3);
        for (int i = 0; i < third; i++) {
            chosen.add(all.get(i));
            chosen.add(all.get(all.size() - 1 - i));
        }
        int remaining = sampleSize - chosen.size();
        if (remaining > 0) {
            int stride = Math.max(1, all.size() / (remaining + 1));
            for (int i = stride; i < all.size() && chosen.size() < sampleSize; i += stride) {
                chosen.add(all.get(i));
            }
        }
        return List.copyOf(chosen);
    }

    private double agreementWithStored(UUID chunkId, float[] fresh) {
        StringBuilder literal = new StringBuilder(fresh.length * 12 + 2).append('[');
        for (int i = 0; i < fresh.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(fresh[i]);
        }
        // CAST(... AS vector) rather than ::vector — the named-parameter parser reads `::` as a
        // parameter marker, the trap ProtocolIndexWriter and ChunkRetriever both carry a note about.
        return jdbc.sql("SELECT 1 - (embedding <=> CAST(:v AS vector)) FROM chunk WHERE id = :id")
                .param("v", literal.append(']').toString())
                .param("id", chunkId)
                .query(Double.class)
                .single();
    }

    /**
     * One chunk as stored, carrying the text the verifier re-embeds to compare against.
     *
     * <p>{@code content} is read back from the database rather than re-derived from the source
     * file, because the question this class answers is whether the STORED vector matches the
     * STORED text — re-chunking the file would test the chunker instead.
     */
    private record StoredChunk(UUID id, UUID protocolId, String content, String title) {
    }

    /** One chunk's agreement between its stored vector and a fresh embedding of its own text. */
    public record Probe(UUID protocolId, String title, double agreement) {

        /**
         * Whether this chunk's stored vector belongs to some other text.
         *
         * <p>A threshold rather than an equality test, and the gap is what makes it safe: the same
         * text re-embeds at ~0.9999 while a foreign vector scores at most ~0.04, so anything near
         * the floor is a mis-stored vector and not a rounding difference.
         */
        public boolean foreign() {
            return agreement < AGREEMENT_FLOOR;
        }
    }

    /** What the scan found. */
    public record Report(List<Probe> probes) {

        /** The probes that failed, which is what an operator reads first and a test asserts on. */
        public List<Probe> foreign() {
            return probes.stream().filter(Probe::foreign).toList();
        }

        /**
         * Whether the scan found nothing wrong.
         *
         * <p>Named for the answer rather than for the count so a caller reads the verdict instead
         * of re-deriving it; an empty foreign list is the definition of clean, not a coincidence.
         */
        public boolean clean() {
            return foreign().isEmpty();
        }

        /** Operator-readable, and the same text in the runner's output and a test's failure. */
        public String describe() {
            StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                    "embedding provenance: %d chunks checked, %d foreign%n",
                    probes.size(), foreign().size()));
            for (Probe probe : probes) {
                out.append(String.format(Locale.ROOT, "  %s  %.4f  %s%n",
                        probe.foreign() ? "FOREIGN" : "ok     ", probe.agreement(), probe.title()));
            }
            if (!clean()) {
                out.append("""

                        These chunks were written by a DIFFERENT embedding model than the one
                        configured now. They are orthogonal to every question, so their protocols
                        cannot be retrieved at all — the rows look healthy and the search is blind
                        to them. Re-index the affected protocols through the configured provider.
                        """);
            }
            return out.toString();
        }
    }
}
