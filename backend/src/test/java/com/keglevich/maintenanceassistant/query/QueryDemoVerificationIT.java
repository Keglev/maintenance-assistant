package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three demo cases, measured against the <b>real</b> provider and the real indexed corpus.
 *
 * <p>Skipped unless {@code LLM_API_KEY} is set, so CI never runs it and it never spends money by
 * accident. It exists because the numbers this project quotes — 0.695, 0.577, 0.502 and the 0.55
 * threshold between them — are measurements, and a measurement that cannot be re-taken is a claim.
 * Running this is how the claim stays honest after a prompt change, a model change or an
 * embedding-model change, and it is the same code path production runs rather than a script that
 * approximates it.
 *
 * <p>What it does <em>not</em> cover is the HTTP and security layer, which needs a Keycloak token
 * the realm deliberately does not issue by password grant; that layer is covered by
 * {@code QueryControllerIT}.
 *
 * <p>Reproduce: see the header of {@code application-demo.yml}. Requires the local development
 * Postgres with the corpus already indexed.
 */
@SpringBootTest
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+",
        disabledReason = "needs a real provider key; set LLM_API_KEY to run")
class QueryDemoVerificationIT {

    /** The E-47 demo: German question, German protocols, four of them on this machine. */
    private static final String CASE_A_QUESTION = "Presse kommt nicht auf Druck, Fehler E-47, was tun?";
    private static final String CASE_A_MACHINE = "PR-03";

    /** The cross-language demo: German question, the matching protocol is written in English. */
    private static final String CASE_B_QUESTION = "Band läuft nach rechts aus dem Lauf, Material fällt herunter";
    private static final String CASE_B_MACHINE = "FB-04";

    /** The deliberate gap: no protocol on this machine covers dosing. */
    private static final String CASE_C_QUESTION = "Dosierung ist ungenau, die Füllmenge schwankt";
    private static final String CASE_C_MACHINE = "AB-02";

    @Autowired
    QueryService queries;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ChunkRetriever retriever;
    @Autowired
    EmbeddingClient embeddingClient;
    @Autowired
    ChatProperties chatProperties;
    @Autowired
    QueryProperties queryProperties;

    @Test
    @DisplayName("demo 1 — E-47 on Presse 3 is Mode A, with citations that exist")
    void e47IsGrounded() {
        Measured measured = measure("E-47 / PR-03", CASE_A_QUESTION, CASE_A_MACHINE, QueryRole.TECHNIKER);

        assertThat(measured.bestSimilarity()).isGreaterThanOrEqualTo(queryProperties.similarityThreshold());
        assertThat(measured.answer().mode()).isEqualTo(QueryAnswer.AnswerMode.A);
        assertThat(measured.answer().citations()).isNotEmpty();
        assertThat(measured.answer().language()).isEqualTo("de");
        assertThat(measured.answer().citations())
                .as("every cited protocol must be a row that exists")
                .allSatisfy(citation -> assertThat(protocolExists(citation.protocolId())).isTrue());
    }

    @Test
    @DisplayName("demo 2 — a German question reaches the English protocol, and is answered in German")
    void crossLanguageIsGrounded() {
        Measured measured = measure("DE→EN / FB-04", CASE_B_QUESTION, CASE_B_MACHINE, QueryRole.TECHNIKER);

        assertThat(measured.bestSimilarity())
                .as("this is the case ADR-002's 0.583 would have refused")
                .isGreaterThanOrEqualTo(queryProperties.similarityThreshold());
        assertThat(measured.answer().mode()).isEqualTo(QueryAnswer.AnswerMode.A);
        assertThat(measured.answer().language())
                .as("the language pin covers the whole answer, not only its first sentence")
                .isEqualTo("de");
    }

    @Test
    @DisplayName("demo 3 — the dosing gap on AB-02 is Mode B, and cites nothing")
    void theGapIsUngrounded() {
        Measured measured = measure("gap / AB-02", CASE_C_QUESTION, CASE_C_MACHINE, QueryRole.TECHNIKER);

        assertThat(measured.bestSimilarity()).isLessThan(queryProperties.similarityThreshold());
        assertThat(measured.answer().mode()).isEqualTo(QueryAnswer.AnswerMode.B);
        assertThat(measured.answer().citations()).isEmpty();
        assertThat(measured.answer().answer())
                .as("the spike saw a citation appended to a refusal; the Mode B schema has no "
                        + "field for one, and any bracketed text is stripped as well")
                .doesNotContain("[");
    }

    @Test
    @DisplayName("an operator's answer to the E-47 question is not a technician's answer")
    void theOperatorAnswerDiffers() {
        Measured operator = measure("E-47 / operator", CASE_A_QUESTION, CASE_A_MACHINE, QueryRole.OPERATOR);
        Measured techniker = measure("E-47 / techniker", CASE_A_QUESTION, CASE_A_MACHINE, QueryRole.TECHNIKER);

        assertThat(operator.answer().mode()).isEqualTo(QueryAnswer.AnswerMode.A);
        assertThat(operator.answer().answer()).isNotEqualTo(techniker.answer().answer());
    }

    // ---------------------------------------------------------------------------------------

    private Measured measure(String label, String question, String machineNo, QueryRole role) {
        UUID machineId = jdbc.sql("SELECT id FROM machine WHERE machine_no = :no")
                .param("no", machineNo).query(UUID.class).single();

        // Retrieval is run once on its own first, so the similarity reported below is the number
        // the threshold was compared against rather than one inferred from the outcome.
        float[] vector = embeddingClient.embed(List.of(question)).vectors().get(0);
        List<RetrievedChunk> hits = retriever.retrieve(machineId, vector, queryProperties.topK(), false,
                LexicalTerms.extract(question), queryProperties.lexicalWeight());
        // MAX, matching QueryService: the list is ordered by the fused score since ADR-009, so the
        // head is no longer guaranteed to be the most similar chunk.
        double best = hits.stream().mapToDouble(RetrievedChunk::similarity).max().orElse(0.0);

        long startedAt = System.nanoTime();
        // A fresh subject per call, so the rate limiter never shapes a measurement.
        QueryAnswer answer = queries.ask(question, machineId, role, UUID.randomUUID().toString(), false);
        long millis = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("%n=== %s | model=%s | threshold=%.3f%n", label, chatProperties.model(),
                queryProperties.similarityThreshold());
        System.out.printf("best similarity : %.4f  ->  Mode %s%n", best, answer.mode());
        System.out.printf("latency         : %d ms (embedding + retrieval + chat)%n", millis);
        System.out.printf("language        : %s%n", answer.language());
        System.out.printf("claims/citations: %d / %d%n", answer.claims().size(), answer.citations().size());
        answer.citations().forEach(citation -> System.out.printf("  [%s] %s (%s, %.4f)%n",
                citation.label(), citation.title(), citation.errorCode(), citation.similarity()));
        System.out.printf("answer          : %s%n", answer.answer());

        return new Measured(best, millis, answer);
    }

    private boolean protocolExists(UUID protocolId) {
        return jdbc.sql("SELECT count(*) FROM protocol WHERE id = :id")
                .param("id", protocolId).query(Long.class).single() == 1L;
    }

    private record Measured(double bestSimilarity, long millis, QueryAnswer answer) {
    }
}
