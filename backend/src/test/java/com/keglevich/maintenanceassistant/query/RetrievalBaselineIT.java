package com.keglevich.maintenanceassistant.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient;
import com.keglevich.maintenanceassistant.ingestion.EmbeddingProvenanceVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE RETRIEVAL BASELINE — the instrument v1.3.0 is judged against, and the reading it took.
 *
 * <p>This measures. <b>It does not assert quality and it must never be made to.</b> A baseline that
 * failed the build the day retrieval got worse sounds appealing and is the wrong instrument: the
 * numbers it produces are a reading of a 19-question set against a 165-protocol corpus, and a
 * threshold drawn across them would become a line someone tunes against rather than a fact someone
 * reads. The assertions in this class are about the INSTRUMENT, not the result — see
 * {@link #theInstrumentIsSound()}.
 *
 * <p>Skipped unless {@code LLM_API_KEY} is set, the same gate as {@link QueryDemoVerificationIT} and
 * {@code DuplicateSimilarityCalibrationIT}, so CI never runs it and it never spends money by
 * accident. Like both of those it needs the local development Postgres with the corpus indexed and
 * the real provider — a stubbed embedding would measure the stub.
 *
 * <p><b>Reproduce</b> (PowerShell, from {@code backend/}), local stack up and corpus indexed:
 * <pre>
 *   $env:LLM_API_KEY = (Select-String -Path ..\spike\adr-002\.env -Pattern '^IONOS_API_KEY=').Line.Split('=')[1]
 *   $env:LLM_CHAT_MODEL = 'meta-llama/Llama-3.3-70B-Instruct'
 *   mvn verify -Dit.test=RetrievalBaselineIT
 * </pre>
 *
 * <p>It rewrites {@code src/test/resources/retrieval/baseline.md} on every run, and that file is
 * committed. Writing a record into the source tree rather than into {@code target/} is deliberate:
 * PR 2 (hybrid search) and PR 3 (the reranker) are judged by the DIFF of that file, and a report
 * that only ever existed in a build directory cannot be diffed by anyone who did not run it.
 *
 * <p><b>What it cannot tell you</b>, stated here because a number in a table looks more certain than
 * it is: 19 questions over 165 synthetic protocols measure DIRECTION, not quality. The set was
 * drafted by reading the corpus, so it inherits whatever that reading missed.
 *
 * <p>What it no longer inherits is doubt about the expectations themselves: the set was
 * <b>ratified by Carlos on 2026-08-20</b>, so every number below is measured against a ruled set
 * rather than against a draft. The limits above are about the set's SIZE and SOURCE, which
 * ratification does not change. See ADR-008.
 */
@SpringBootTest
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+",
        disabledReason = "needs a real provider key; set LLM_API_KEY to run")
class RetrievalBaselineIT {

    /** Committed, and diffed by the PRs that follow. Relative to {@code backend/}. */
    private static final Path REPORT = Path.of("src", "test", "resources", "retrieval", "baseline.md");

    /** The sweep range for M1.3. Wide enough to contain both wrong answers, not just the right one. */
    private static final double SWEEP_FROM = 0.40;
    private static final double SWEEP_TO = 0.75;
    private static final int SWEEP_STEPS = 35;

    /** IONOS bge-m3, EUR per 1M input tokens — ADR-002's price sheet, used for the cost line. */
    private static final double EMBEDDING_EUR_PER_MILLION_TOKENS = 0.02;

    /**
     * 0 = every chunk.
     *
     * <p>A full scan of this corpus is 182 chunks in 6 batched calls, which is cheaper than the 19
     * answers this run already buys. Sampling here would trade a real guarantee for nothing.
     */
    private static final int PROVENANCE_SAMPLE = 0;

    @Autowired QueryService queries;
    @Autowired ChunkRetriever retriever;
    @Autowired EmbeddingClient embeddingClient;
    @Autowired EmbeddingProvenanceVerifier provenance;
    @Autowired QueryProperties queryProperties;
    @Autowired JdbcClient jdbc;

    @Value("${maintenance.duplicates.similarity-threshold}")
    double duplicateThreshold;

    private final ObjectMapper json = new ObjectMapper();

    // ===========================================================================================
    // The run
    // ===========================================================================================

    @Test
    @DisplayName("baseline — every golden question through the real retrieval path, written to baseline.md")
    void takeTheBaseline() throws Exception {
        List<Question> questions = loadGoldenSet();
        CorpusFingerprint corpus = fingerprintCorpus();

        Instant started = Instant.now();
        long embeddingCalls = 0;
        long embeddingTokens = 0;

        List<Result> results = new ArrayList<>();
        for (Question q : questions) {
            UUID machineId = machineId(q.machineNo());

            // Retrieval, measured directly. The question is embedded here so the ranked list and its
            // similarities can be inspected; ask() embeds again on its own path, and both calls are
            // counted into the cost line rather than hidden.
            EmbeddingClient.EmbeddingBatch batch = embeddingClient.embed(List.of(q.question()));
            embeddingCalls += batch.providerCalls();
            embeddingTokens += batch.promptTokens();
            List<RetrievedChunk> hits =
                    retriever.retrieve(machineId, batch.vectors().get(0), queryProperties.topK(), false,
                            LexicalTerms.extract(q.question()), queryProperties.lexicalWeight());

            // The whole path, unchanged, including the Mode A -> Mode B fall-through that a
            // similarity number alone cannot predict.
            QueryAnswer answer = queries.ask(q.question(), machineId, QueryRole.TECHNIKER,
                    "baseline-" + q.id(), false);
            embeddingCalls++;

            // What the expected protocol ACTUALLY scored, whether or not it made the top k, and what
            // outranked it. Without this a miss is unreadable: "not in the top 5" does not say
            // whether the right protocol sat at rank 6 with 0.59 or at rank 40 with 0.31, and those
            // are different problems for PR 2 to solve. Pure SQL against the vector already embedded
            // above — no provider call.
            results.add(Result.of(q, hits, answer,
                    trueSimilarity(machineId, batch.vectors().get(0), q.expected()),
                    rankedTitles(hits)));
        }
        Duration elapsed = Duration.between(started, Instant.now());

        EmbeddingProvenanceVerifier.Report health = provenance.verify(PROVENANCE_SAMPLE);
        embeddingCalls += health.probes().size();

        String md = header(corpus, questions.size())
                + indexHealth(health)
                + perQuestionTable(results)
                + aggregates(results)
                + perCase(results)
                + misses(results)
                + thresholdSweep(results)
                + duplicateWindow()
                + costAndRuntime(elapsed, embeddingCalls, embeddingTokens, questions.size())
                + corpusDrift(corpus);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, md);

        // THE ONE RESULT THIS HARNESS IS ALLOWED TO ASSERT, added with ADR-009's gate change.
        //
        // Everything else here measures and reports; a falling recall is news, not a build failure.
        // This is different in kind: the gate exists to keep an unanswerable question from acquiring
        // a citation (NFR-2), and ADR-009 widened it. A widening that let a Mode B question through
        // would not be a worse number, it would be the anti-hallucination guarantee gone — so it
        // fails here rather than being noticed in a table by whoever reads carefully.
        assertThat(results.stream().filter(r -> r.question().isModeB()).toList())
                .as("a question the corpus cannot answer must stay ungrounded and cite nothing")
                .allSatisfy(r -> {
                    assertThat(r.mode()).as("%s mode", r.question().id())
                            .isEqualTo(QueryAnswer.AnswerMode.B);
                    assertThat(r.citedAnything()).as("%s citations", r.question().id()).isFalse();
                    assertThat(r.lexicallyGrounded())
                            .as("%s acquired an exact-term match, which is how this could break",
                                    r.question().id())
                            .isFalse();
                });
        System.out.println("\n" + md);
        System.out.println("written: " + REPORT.toAbsolutePath());
    }

    /**
     * The only assertions in this class, and every one of them is about the instrument.
     *
     * <p>Each would fail on a broken MEASUREMENT — a fixture that has drifted away from the corpus, a
     * corpus that is not the one the numbers claim, a question about a machine that does not exist.
     * None of them fails on a bad RESULT: recall may drop to zero and this test stays green, because
     * a falling number is news for a person to read and not a build to break. If someone later adds
     * {@code assertThat(recall).isGreaterThan(...)} here, the set stops being a measurement and
     * becomes a target — the one failure mode ADR-008 names.
     */
    @Test
    @DisplayName("instrument — the fixture matches the corpus and the corpus matches the file")
    void theInstrumentIsSound() throws Exception {
        List<Question> questions = loadGoldenSet();
        CorpusFingerprint corpus = fingerprintCorpus();

        assertThat(questions).as("a golden set of nothing measures nothing").isNotEmpty();

        // FLIPPED 2026-08-20, when Carlos ratified the set. It asserted isFalse() before: the set was
        // a proposal, and a robot marking its own expectations as ruled would have been the exact
        // failure ADR-008 was written to prevent. Now the assertion guards the other direction — a
        // question added later must be ruled on before the metrics can quote it, because a set that
        // is half proposal and half ruling reports one number for two different kinds of claim.
        assertThat(questions).allSatisfy(q -> assertThat(q.ratified())
                .as("%s is not ratified — a new question is a proposal until its owner rules on it, "
                        + "and the aggregates must not silently include one", q.id())
                .isTrue());

        for (Question q : questions) {
            assertThat(machineId(q.machineNo()))
                    .as("%s asks about machine %s, which does not exist", q.id(), q.machineNo())
                    .isNotNull();
            assertThat(corpus.liveIds())
                    .as("%s expects protocols that are not in the corpus", q.id())
                    .containsAll(q.expected());
        }

        assertThat(corpus.missingFromDatabase())
                .as("the corpus file has protocols this database does not — the reading would be of a "
                        + "smaller corpus than the one it names")
                .isEmpty();
    }

    /**
     * IS THE INDEX IN THE MODEL'S SPACE? — the check this baseline had to grow, on its first run.
     *
     * <p>A stored vector is only comparable with a question vector if both came out of the same
     * model. Nothing in the schema records which model wrote a row, so an index embedded by two
     * different models is <b>silently</b> half-unsearchable: the rows are present, {@code status} is
     * {@code INDEXED}, the vectors are unit-length and well-formed, and every one of them is
     * orthogonal to every question. No count, no health endpoint and no functional test can see it.
     *
     * <p>The check is direct rather than statistical: re-embed a chunk's own stored text with the
     * model configured RIGHT NOW and compare with the vector stored beside it. Same model, same text
     * gives ~1.0. Anything materially lower means the row was written by something else.
     *
     * <p>It asserts, and the assertion is about the INSTRUMENT, not about quality: a baseline taken
     * over an index that is not in the query model's space is not a reading of retrieval, it is a
     * reading of an accident. Key-gated like the rest of this class, so it can never gate CI.
     */
    @Test
    @DisplayName("index health — stored vectors belong to the currently configured embedding model")
    void theIndexIsInTheModelsSpace() {
        EmbeddingProvenanceVerifier.Report report = provenance.verify(PROVENANCE_SAMPLE);
        System.out.println("\n" + report.describe());

        assertThat(report.foreign())
                .as("these chunks were embedded by a different model than the one configured now, so "
                        + "they are orthogonal to every question and cannot be retrieved at all:\n%s",
                        report.describe())
                .isEmpty();
    }

    // ===========================================================================================
    // Report sections
    // ===========================================================================================

    private String header(CorpusFingerprint corpus, int questionCount) {
        return fmt("""
                # Retrieval baseline

                Generated by `RetrievalBaselineIT`. **Do not edit by hand** — re-run the harness (see
                the class javadoc) and commit the diff.

                | | |
                |---|---|
                | Questions | %d, all ratified (Carlos, 2026-08-20) |
                | Corpus in the database | %d live protocols, %d chunks |
                | Corpus file | %d protocols |
                | Query threshold (configured) | %.3f |
                | top-k | %d |
                | Embedding model | bge-m3, 1024 dims |

                """, questionCount, corpus.liveIds().size(), corpus.chunkCount(),
                corpus.fileIds().size(), queryProperties.similarityThreshold(), queryProperties.topK());
    }

    /**
     * The health warning, at the top of the document rather than in a footnote.
     *
     * <p>A reader who takes the tables below as a reading of retrieval, when some of the index is not
     * in the query model's space, has been misled by this file. So the file says so itself, first.
     */
    private String indexHealth(EmbeddingProvenanceVerifier.Report report) {
        List<EmbeddingProvenanceVerifier.Probe> foreign = report.foreign();
        int checked = report.probes().size();
        if (foreign.isEmpty()) {
            return fmt("""
                    ## Index health

                    %d sampled chunks re-embedded with the configured model and compared with their stored
                    vector: all agree (>= 0.95). The index is in the query model's space.

                    """, checked);
        }
        StringBuilder out = new StringBuilder(fmt("""
                > ## WARNING — PART OF THIS INDEX IS NOT IN THE QUERY MODEL'S SPACE
                >
                > %d of %d sampled chunks disagree with a fresh embedding of their own text. A stored
                > vector written by a different embedding model is orthogonal to every question: the
                > protocol is present, `INDEXED`, unit-length and well-formed, and **cannot be retrieved
                > at all**. Any row below that expects one of these protocols is measuring the accident,
                > not retrieval.

                | agreement | protocol |
                |---|---|
                """, foreign.size(), checked));
        foreign.forEach(p -> out.append(fmt("| %.4f | %s |\n", p.agreement(), p.title())));
        return out.append('\n').toString();
    }

    private String perQuestionTable(List<Result> results) {
        StringBuilder out = new StringBuilder("""
                ## Per question

                `rank` is the position of the first EXPECTED protocol in the retrieved list, deduplicated
                by protocol (chunk is the search unit, protocol is the citation unit). `-` means it was
                not in the top 5 at all.

                The two score components are reported separately, never fused: `best sim` is pure cosine
                and `lexical` is the exact terms the question carried and how many retrieved chunks
                contain them. A question is grounded by either.

                | id | case | lang | machine | rank | best sim | sim of expected | lexical | mode | citation | ok |
                |---|---|---|---|---|---|---|---|---|---|---|
                """);
        for (Result r : results) {
            out.append(fmt("| %s | %s | %s | %s | %s | %.4f | %s | %s | %s | %s | %s |\n",
                    r.question().id(), r.question().caseLabel(), r.question().language(),
                    r.question().machineNo(),
                    r.rank() == 0 ? "-" : String.valueOf(r.rank()),
                    r.bestSimilarity(),
                    r.expectedSimilarity() == null ? "-" : fmt("%.4f", r.expectedSimilarity()),
                    r.lexicalTerms().isEmpty() ? "-"
                            : fmt("`%s` in %d", String.join(",", r.lexicalTerms()), r.lexicalChunks()),
                    r.mode(),
                    r.question().isModeB()
                            ? (r.citedAnything() ? "cited (wrong)" : "none (right)")
                            : (r.citedExpected() ? "expected" : "other/none"),
                    r.correct() ? "OK" : "**MISS**"));
        }
        return out.append('\n').toString();
    }

    private String aggregates(List<Result> results) {
        List<Result> answerable = results.stream().filter(r -> !r.question().isModeB()).toList();
        List<Result> modeB = results.stream().filter(r -> r.question().isModeB()).toList();

        return fmt("""
                ## Aggregates

                Recall and MRR are computed over the %d questions that HAVE a right answer. The %d Mode B
                questions are excluded from them by construction — there is nothing to recall — and are
                reported on their own terms.

                | metric | value |
                |---|---|
                | recall@1 | %s |
                | recall@3 | %s |
                | recall@5 | %s |
                | MRR | %.4f |
                | Mode A/B decision correct | %s |
                | Mode B questions answered ungrounded | %s |
                | Answered fully correctly | %s |

                """, answerable.size(), modeB.size(),
                pct(recallAt(answerable, 1), answerable.size()),
                pct(recallAt(answerable, 3), answerable.size()),
                pct(recallAt(answerable, 5), answerable.size()),
                mrr(answerable),
                pct(results.stream().filter(Result::modeCorrect).count(), results.size()),
                pct(modeB.stream().filter(Result::modeCorrect).count(), modeB.size()),
                pct(results.stream().filter(Result::correct).count(), results.size()));
    }

    private String perCase(List<Result> results) {
        StringBuilder out = new StringBuilder("""
                ## By case

                The breakdown is the point. An aggregate that hides "every exact-term question failed" is
                worse than no aggregate.

                | case | n | recall@1 | recall@3 | recall@5 | MRR | mode correct |
                |---|---|---|---|---|---|---|
                """);
        Map<String, List<Result>> byCase = new LinkedHashMap<>();
        for (Result r : results) {
            byCase.computeIfAbsent(r.question().caseLabel(), k -> new ArrayList<>()).add(r);
        }
        byCase.forEach((label, group) -> {
            List<Result> scored = group.stream().filter(r -> !r.question().isModeB()).toList();
            out.append(fmt("| %s | %d | %s | %s | %s | %s | %s |\n", label, group.size(),
                    scored.isEmpty() ? "n/a" : pct(recallAt(scored, 1), scored.size()),
                    scored.isEmpty() ? "n/a" : pct(recallAt(scored, 3), scored.size()),
                    scored.isEmpty() ? "n/a" : pct(recallAt(scored, 5), scored.size()),
                    scored.isEmpty() ? "n/a" : fmt("%.4f", mrr(scored)),
                    pct(group.stream().filter(Result::modeCorrect).count(), group.size())));
        });
        return out.append('\n').toString();
    }

    /**
     * Every miss, with what was retrieved instead and what the right protocol actually scored.
     *
     * <p>This is the section PR 2 works from. An aggregate says how many questions failed; only this
     * says whether the right protocol lost narrowly to a neighbour, was never close, or won the
     * ranking and was then cut by the threshold — three different defects with three different fixes.
     */
    private String misses(List<Result> results) {
        List<Result> missed = results.stream().filter(r -> !r.correct()).toList();
        if (missed.isEmpty()) {
            return "## Misses\n\nNone.\n\n";
        }
        StringBuilder out = new StringBuilder("""
                ## Misses in detail

                `true similarity` is the expected protocol's best chunk score with no top-k limit — what
                it really scored, whether or not it was returned.

                """);
        for (Result r : missed) {
            out.append(fmt("**%s** (%s, %s) — %s\n\n", r.question().id(), r.question().caseLabel(),
                    r.question().machineNo(), r.question().question()));
            out.append(fmt("- mode %s, best retrieved %.4f, true similarity of the expected protocol %s\n",
                    r.mode(), r.bestSimilarity(),
                    r.trueExpectedSimilarity() == null ? "n/a" : fmt("%.4f", r.trueExpectedSimilarity())));
            out.append("- retrieved instead:\n");
            r.retrievedTitles().forEach(t -> out.append("  - `").append(t).append("`\n"));
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * M1.3, the query half. The sweep is arithmetic over similarities already retrieved — no second
     * embedding call and no second answer, so the whole curve costs nothing beyond the run above.
     *
     * <p>Three failure columns, because a threshold has three ways to be wrong and only one of them is
     * obvious: it can push an answerable question into Mode B, it can let an unanswerable question
     * produce a citation, and — the one a single "accuracy" number hides — it can keep a question in
     * Mode A while cutting the RIGHT protocol out of the sources the model is allowed to cite.
     */
    private String thresholdSweep(List<Result> results) {
        StringBuilder out = new StringBuilder("""
                ## Query threshold sweep (M1.3a)

                Recomputed from the retrieved similarities; no provider call. `expected above t` counts
                answerable questions whose right protocol is still among the offered sources at that
                threshold — `QueryService.labelByProtocol` only offers the model sources at or above it,
                so a protocol below the line cannot be cited even when the question stays Mode A.

                | threshold | Mode A questions | answerable lost to Mode B | Mode B wrongly grounded | expected above t | |
                |---|---|---|---|---|---|
                """);
        List<Result> answerable = results.stream().filter(r -> !r.question().isModeB()).toList();
        List<Result> modeB = results.stream().filter(r -> r.question().isModeB()).toList();

        for (int i = 0; i <= SWEEP_STEPS; i++) {
            final double t = SWEEP_FROM + (SWEEP_TO - SWEEP_FROM) * i / SWEEP_STEPS;
            // The gate as it actually is since ADR-009: similarity OR an exact term. A question
            // grounded by its code is Mode A at every threshold, and a sweep that ignored that would
            // describe a system this is not.
            long grounded = results.stream()
                    .filter(r -> r.bestSimilarity() >= t || r.lexicallyGrounded()).count();
            long lost = answerable.stream()
                    .filter(r -> r.bestSimilarity() < t && !r.lexicallyGrounded()).count();
            long wronglyGrounded = modeB.stream()
                    .filter(r -> r.bestSimilarity() >= t || r.lexicallyGrounded()).count();
            long expectedAbove = answerable.stream().filter(r -> r.expectedSimilarity() != null
                    && r.expectedSimilarity() >= t).count();
            out.append(fmt("| %.2f | %d | %d | %d | %d / %d | %s |\n", t, grounded, lost,
                    wronglyGrounded, expectedAbove, answerable.size(),
                    Math.abs(t - queryProperties.similarityThreshold()) < 0.005 ? "**configured**" : ""));
        }
        return out.append('\n').toString();
    }

    /**
     * M1.3, the duplicate half — the CEILING only, re-measured on today's corpus.
     *
     * <p>The floor of the window (what a real duplicate scores) is not re-implemented here:
     * {@code DuplicateSimilarityCalibrationIT} already files a re-narration and a verbatim re-file
     * through the real intake and measures both. Copying that apparatus into a second test would give
     * the project two instruments for one number, which is how two numbers start disagreeing.
     */
    private String duplicateWindow() {
        List<Pair> top = jdbc.sql("""
                        WITH cent AS (
                            SELECT p.id, p.machine_id, m.machine_no, p.title, avg(c.embedding) v
                            FROM protocol p
                            JOIN machine m ON m.id = p.machine_id
                            JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.deleted_at IS NULL
                            GROUP BY p.id, m.machine_no
                        )
                        SELECT a.machine_no, a.title AS ta, b.title AS tb, 1 - (a.v <=> b.v) AS similarity
                        FROM cent a JOIN cent b ON b.machine_id = a.machine_id AND b.id > a.id
                        ORDER BY similarity DESC
                        LIMIT 8
                        """)
                .query((rs, i) -> new Pair(rs.getString("machine_no"), rs.getString("ta"),
                        rs.getString("tb"), rs.getDouble("similarity")))
                .list();

        Double mean = jdbc.sql("""
                        WITH cent AS (
                            SELECT p.id, p.machine_id, avg(c.embedding) v
                            FROM protocol p JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.deleted_at IS NULL
                            GROUP BY p.id
                        )
                        SELECT avg(1 - (a.v <=> b.v))
                        FROM cent a JOIN cent b ON b.machine_id = a.machine_id AND b.id > a.id
                        """).query(Double.class).single();

        Long pairCount = jdbc.sql("""
                        WITH cent AS (
                            SELECT p.id, p.machine_id FROM protocol p
                            JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.deleted_at IS NULL GROUP BY p.id, p.machine_id
                        )
                        SELECT count(*) FROM cent a JOIN cent b
                          ON b.machine_id = a.machine_id AND b.id > a.id
                        """).query(Long.class).single();

        StringBuilder out = new StringBuilder(fmt("""
                ## Duplicate threshold — the legitimate ceiling (M1.3b)

                Protocol centroids, cosine, same machine only. This is the CEILING half of the window;
                the floor (a re-narration and a verbatim re-file) is measured by
                `DuplicateSimilarityCalibrationIT`, which is the instrument for it and is not duplicated
                here.

                | | |
                |---|---|
                | Same-machine pairs | %d |
                | Mean over all pairs | %.4f |
                | Configured threshold | %.3f |
                | **Highest legitimate pair** | **%.4f** |
                | Margin, threshold - ceiling | %.4f |

                Top pairs:

                | similarity | machine | a | b |
                |---|---|---|---|
                """, pairCount, mean, duplicateThreshold, top.get(0).similarity(),
                duplicateThreshold - top.get(0).similarity()));
        top.forEach(p -> out.append(fmt("| %.4f | %s | %s | %s |\n",
                p.similarity(), p.machineNo(), p.a(), p.b())));
        return out.append('\n').toString();
    }

    private String costAndRuntime(Duration elapsed, long embeddingCalls, long embeddingTokens,
                                  int questionCount) {
        double embeddingEur = embeddingTokens / 1_000_000.0 * EMBEDDING_EUR_PER_MILLION_TOKENS;
        return fmt("""
                ## Runtime and cost of one run

                | | |
                |---|---|
                | Wall clock | %d s |
                | Embedding provider calls | %d (%d prompt tokens) |
                | Embedding cost | EUR %.6f |
                | Chat calls | %d, one answer per question (a Mode A fall-through would add one) |

                The chat calls are the expensive half and are NOT priced here, because this run does not
                see the provider's token accounting for them. The figure that matters for re-running it is
                that it spends %d answers out of the 400/day budget in `maintenance.chat`.

                """, elapsed.toSeconds(), embeddingCalls, embeddingTokens, embeddingEur,
                questionCount, questionCount);
    }

    private String corpusDrift(CorpusFingerprint corpus) {
        if (corpus.extraInDatabase().isEmpty() && corpus.missingFromDatabase().isEmpty()) {
            return "## Corpus drift\n\nNone. The database holds exactly the corpus file.\n";
        }
        StringBuilder out = new StringBuilder("""
                ## Corpus drift — READ THIS BEFORE COMPARING RUNS

                The database this was measured against is NOT exactly the corpus file. Every number above
                is a reading of what was actually there.

                """);
        corpus.extraInDatabase().forEach(id -> out.append(fmt("- extra in the database: `%s` - %s\n",
                id, titleOf(id))));
        corpus.missingFromDatabase().forEach(id -> out.append(
                fmt("- in the corpus file, absent from the database: `%s`\n", id)));
        return out.append('\n').toString();
    }

    // ===========================================================================================
    // Metrics
    // ===========================================================================================

    private static long recallAt(List<Result> results, int k) {
        return results.stream().filter(r -> r.rank() > 0 && r.rank() <= k).count();
    }

    private static double mrr(List<Result> results) {
        return results.stream().mapToDouble(r -> r.rank() == 0 ? 0.0 : 1.0 / r.rank()).average().orElse(0);
    }

    private static String pct(long hit, long total) {
        return total == 0 ? "n/a" : fmt("%d/%d (%.0f%%)", hit, total, 100.0 * hit / total);
    }

    /**
     * Every number in the report goes through here.
     *
     * <p>{@code String.formatted} uses the DEFAULT locale, and this project is developed on a German
     * one: it would write {@code 0,4712} into a committed markdown table, which is both wrong in a
     * document read as English and a diff that changes with the machine that ran it.
     */
    private static String fmt(String template, Object... args) {
        return String.format(Locale.ROOT, template, args);
    }

    // ===========================================================================================
    // Fixtures and the corpus
    // ===========================================================================================

    private List<Question> loadGoldenSet() throws IOException {
        JsonNode root = json.readTree(
                new ClassPathResource("retrieval/golden-questions.json").getInputStream());
        List<Question> out = new ArrayList<>();
        for (JsonNode n : root.get("questions")) {
            Set<UUID> expected = new LinkedHashSet<>();
            n.get("expected").forEach(e -> expected.add(UUID.fromString(e.asText())));
            out.add(new Question(n.get("id").asText(), n.get("question").asText(),
                    n.get("language").asText(), n.get("machineNo").asText(), expected,
                    n.get("case").asText(), n.get("ratified").asBoolean()));
        }
        return out;
    }

    /**
     * What the reading was actually taken against.
     *
     * <p>Recorded because a development database accumulates: a drill upload, an e2e leftover, a
     * protocol somebody filed by hand in August. Retrieval cannot tell those from the corpus, so a
     * baseline measured on a drifted database and compared with one measured on a clean one is two
     * different experiments reported as one.
     */
    private CorpusFingerprint fingerprintCorpus() throws IOException {
        Set<UUID> file = new TreeSet<>();
        for (String line : Files.readAllLines(corpusFile())) {
            if (!line.isBlank()) {
                file.add(UUID.fromString(json.readTree(line).get("id").asText()));
            }
        }
        Set<UUID> live = new TreeSet<>(jdbc.sql("SELECT id FROM protocol WHERE deleted_at IS NULL")
                .query(UUID.class).list());
        long chunks = jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single();

        Set<UUID> extra = new TreeSet<>(live);
        extra.removeAll(file);
        Set<UUID> missing = new TreeSet<>(file);
        missing.removeAll(live);
        return new CorpusFingerprint(file, live, chunks, extra, missing);
    }

    /** The corpus lives in {@code main/resources}; this test runs from {@code backend/}. */
    private static Path corpusFile() {
        return Path.of("src", "main", "resources", "corpus", "protocols.ndjson");
    }

    /**
     * The expected protocol's best chunk similarity, ignoring top-k entirely.
     *
     * <p>The number a miss is diagnosed with. {@code ChunkRetriever} stops at top-k, so a protocol
     * that lost is simply absent and carries no score; this asks the same distance of the same
     * vectors with no limit, which is what says how far it lost by.
     */
    private Double trueSimilarity(UUID machineId, float[] questionVector, Set<UUID> expected) {
        if (expected.isEmpty()) {
            return null;
        }
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < questionVector.length; i++) {
            literal.append(i > 0 ? "," : "").append(questionVector[i]);
        }
        return jdbc.sql("""
                        SELECT max(1 - (c.embedding <=> CAST(:vector AS vector)))
                        FROM chunk c
                        JOIN protocol p ON p.id = c.protocol_id
                        WHERE c.machine_id = :machineId AND p.deleted_at IS NULL
                          AND c.protocol_id IN (:expected)
                        """)
                .param("vector", literal.append(']').toString())
                .param("machineId", machineId)
                .param("expected", expected)
                .query(Double.class).optional().orElse(null);
    }

    private static List<String> rankedTitles(List<RetrievedChunk> hits) {
        List<String> titles = new ArrayList<>();
        for (RetrievedChunk hit : hits) {
            String line = fmt("%.4f  %s", hit.similarity(), hit.title());
            if (!titles.contains(line)) {
                titles.add(line);
            }
        }
        return titles;
    }

    private UUID machineId(String machineNo) {
        return jdbc.sql("SELECT id FROM machine WHERE machine_no = :no")
                .param("no", machineNo).query(UUID.class).optional().orElse(null);
    }

    private String titleOf(UUID id) {
        return jdbc.sql("SELECT title FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse("(gone)");
    }

    // ===========================================================================================
    // Types
    // ===========================================================================================

    private record Question(String id, String question, String language, String machineNo,
                            Set<UUID> expected, String caseLabel, boolean ratified) {

        /** A question with no right answer is the Mode B case; the empty list IS the expectation. */
        boolean isModeB() {
            return expected.isEmpty();
        }
    }

    private record CorpusFingerprint(Set<UUID> fileIds, Set<UUID> liveIds, long chunkCount,
                                     Set<UUID> extraInDatabase, Set<UUID> missingFromDatabase) {
    }

    private record Pair(String machineNo, String a, String b, double similarity) {
    }

    /**
     * One question's reading.
     *
     * @param rank               1-based position of the first expected protocol among the retrieved
     *                           protocols, or 0 for "not in the top k"
     * @param expectedSimilarity the expected protocol's own best chunk similarity, or null if it was
     *                           not retrieved. This is the number the threshold sweep needs, and it is
     *                           NOT the same as the best similarity whenever something else outranked it
     */
    private record Result(Question question, int rank, double bestSimilarity, Double expectedSimilarity,
                          QueryAnswer.AnswerMode mode, boolean citedExpected, boolean citedAnything,
                          Double trueExpectedSimilarity, List<String> retrievedTitles,
                          List<String> lexicalTerms, long lexicalChunks) {

        /** The second component, reported beside the similarity rather than folded into it. */
        boolean lexicallyGrounded() {
            return lexicalChunks > 0;
        }

        static Result of(Question q, List<RetrievedChunk> hits, QueryAnswer answer,
                         Double trueExpectedSimilarity, List<String> retrievedTitles) {
            // Chunk is the search unit, protocol is the citation unit — rank by protocol, first
            // occurrence wins, exactly the way QueryService groups sources for the model.
            List<UUID> ranked = new ArrayList<>(new LinkedHashSet<>(
                    hits.stream().map(RetrievedChunk::protocolId).toList()));
            int rank = 0;
            for (int i = 0; i < ranked.size(); i++) {
                if (q.expected().contains(ranked.get(i))) {
                    rank = i + 1;
                    break;
                }
            }
            Double expectedSimilarity = hits.stream()
                    .filter(h -> q.expected().contains(h.protocolId()))
                    .map(RetrievedChunk::similarity)
                    .max(Double::compare)
                    .orElse(null);
            Set<UUID> cited = new LinkedHashSet<>(
                    answer.citations().stream().map(QueryAnswer.Citation::protocolId).toList());
            boolean citedExpected = cited.stream().anyMatch(q.expected()::contains);
            // MAX, not the head. Since ADR-009 the list is ordered by the fused score, so the first
            // element is not necessarily the most similar one — reading it would make the report
            // disagree with the gate, which asks the same question of the whole set.
            double best = hits.stream().mapToDouble(RetrievedChunk::similarity).max().orElse(0.0);
            return new Result(q, rank, best,
                    expectedSimilarity, answer.mode(), citedExpected, !cited.isEmpty(),
                    trueExpectedSimilarity, retrievedTitles,
                    LexicalTerms.extract(q.question()),
                    hits.stream().filter(h -> h.lexicalMatches() > 0).count());
        }

        /** Mode B questions are right when they stay ungrounded; the rest when they do not. */
        boolean modeCorrect() {
            return question.isModeB()
                    ? mode == QueryAnswer.AnswerMode.B && !citedAnything
                    : mode == QueryAnswer.AnswerMode.A;
        }

        /** The whole question answered right: the correct mode, and for Mode A the right citation. */
        boolean correct() {
            return question.isModeB() ? modeCorrect() : modeCorrect() && citedExpected;
        }
    }
}
