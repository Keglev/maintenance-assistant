package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lexical half of ADR-009, against a real pgvector database.
 *
 * <p>The vectors here are the deterministic fake's, which is exactly right for this suite and would
 * be wrong for a quality claim: what is under test is the SQL — that a literal term is found, that
 * it moves a chunk in the ranking by a bounded amount, and above all that a question carrying no
 * term produces the identical result the pure-vector query produced. Whether hybrid retrieval makes
 * ANSWERS better is measured against the real provider in {@code RetrievalBaselineIT}, and nothing
 * in this file can or should speak to that.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(HybridRetrievalIT.FakeEmbeddingConfig.class)
class HybridRetrievalIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    private static final String MACHINE = "PR-07";

    @Autowired JdbcClient jdbc;
    @Autowired ChunkRetriever retriever;
    @Autowired QueryProperties properties;

    private UUID machineId;
    private UUID withCode;
    private UUID withoutCode;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        machineId = jdbc.sql("SELECT id FROM machine WHERE machine_no = :no")
                .param("no", MACHINE).query(UUID.class).single();

        withCode = insert("Presse 7 in der Leitwarte ausgegraut", "KOM-04",
                "PR-07 · KOM-04 · Presse 7 ausgegraut\nSymptom: keine Werte in der Warte. KOM-04.");
        withoutCode = insert("Schutztuer meldet offen obwohl geschlossen", null,
                "PR-07 · Schutztuer meldet offen\nSymptom: Tuer zu, Meldung bleibt stehen.");
    }

    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an exact code is found in the chunk that literally contains it, and only there")
    void findsTheExactCode() {
        List<RetrievedChunk> hits = retrieve(List.of("kom-04"));

        assertThat(hits).hasSize(2);
        assertThat(matched(hits, withCode)).isEqualTo(1);
        assertThat(matched(hits, withoutCode))
                .as("a chunk that does not contain the term must not be credited with it")
                .isZero();
    }

    @Test
    @DisplayName("case does not matter — a technician types what the display shows")
    void matchIsCaseInsensitive() {
        assertThat(matched(retrieve(List.of("kom-04")), withCode)).isEqualTo(1);
        assertThat(matched(retrieve(List.of("KOM-04")), withCode)).isEqualTo(1);
    }

    @Test
    @DisplayName("a German compound is left to the embedding — the stemmer case, asserted")
    void aCompoundIsNotMatchedLexically() {
        // "Schutztuer" appears in the protocol; "Schutztueren" does not. Postgres' german
        // configuration does not decompound and does not relate the two, which is why ADR-009 does
        // not use full-text search. LexicalTerms never produces a compound as a term in the first
        // place — this asserts the retrieval query agrees, so the two cannot drift apart.
        assertThat(LexicalTerms.extract("Schutztueren klemmen")).isEmpty();
        assertThat(retrieve(LexicalTerms.extract("Schutztueren klemmen")))
                .as("no terms means no lexical credit anywhere")
                .allSatisfy(hit -> assertThat(hit.lexicalMatches()).isZero());
    }

    @Test
    @DisplayName("no terms => the pure-vector result, identical order and identical scores")
    void withoutTermsNothingChanges() {
        List<RetrievedChunk> pureVector = retriever.retrieve(
                machineId, vector("Schutztuer"), properties.topK(), false, List.of(), 0.0);
        List<RetrievedChunk> hybridNoTerms = retriever.retrieve(
                machineId, vector("Schutztuer"), properties.topK(), false, List.of(),
                properties.lexicalWeight());

        assertThat(hybridNoTerms)
                .as("the hybrid query must reduce EXACTLY to its pre-ADR-009 form when a question "
                        + "carries no code — which is most questions")
                .containsExactlyElementsOf(pureVector);
        assertThat(hybridNoTerms).allSatisfy(hit -> assertThat(hit.lexicalMatches()).isZero());
    }

    @Test
    @DisplayName("the weight is a ceiling: zero disables the signal without disabling the match count")
    void weightZeroStillReportsTheComponent() {
        List<RetrievedChunk> hits = retriever.retrieve(
                machineId, vector("irgendetwas"), properties.topK(), false, List.of("kom-04"), 0.0);

        assertThat(matched(hits, withCode))
                .as("the component is still measured and reported; only its effect on order is off")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a lexical hit lifts its chunk in the ranking")
    void theMatchMovesTheRanking() {
        // Ask with the OTHER protocol's words so the vector prefers it, then add the code and watch
        // the ranking change. The fake's vectors are hash-derived, so the assertion is about the
        // ordering rule rather than about semantic quality.
        float[] question = vector("PR-07 · Schutztuer meldet offen\nSymptom: Tuer zu, Meldung bleibt stehen.");

        List<RetrievedChunk> vectorOnly = retriever.retrieve(
                machineId, question, properties.topK(), false, List.of(), properties.lexicalWeight());
        assertThat(vectorOnly.get(0).protocolId())
                .as("without the code the semantically identical chunk wins")
                .isEqualTo(withoutCode);

        List<RetrievedChunk> withTerm = retriever.retrieve(
                machineId, question, properties.topK(), false, List.of("kom-04"), 5.0);
        assertThat(withTerm.get(0).protocolId())
                .as("a large enough weight promotes the chunk that literally carries the term")
                .isEqualTo(withCode);
    }

    // -------------------------------------------------------------------------------------------

    private List<RetrievedChunk> retrieve(List<String> terms) {
        return retriever.retrieve(machineId, vector("egal"), properties.topK(), false, terms,
                properties.lexicalWeight());
    }

    /**
     * A deterministic topic vector: one axis per subject, plus an axis for "neither".
     *
     * <p>Local to this suite rather than shared, because what it has to do is specific — make the
     * vector ranking PREDICTABLE so the assertions are about the ordering rule and never about an
     * embedding's judgement. The "neither" axis exists because a zero vector has no cosine distance
     * for pgvector to rank.
     */
    private static float[] vector(String text) {
        List<String> topics = List.of("leitwarte", "schutztuer");
        float[] v = new float[1024];
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean matched = false;
        for (int i = 0; i < topics.size(); i++) {
            if (lower.contains(topics.get(i))) {
                v[i] = 1.0f;
                matched = true;
            }
        }
        if (!matched) {
            v[topics.size()] = 1.0f;
        }
        return v;
    }

    private static int matched(List<RetrievedChunk> hits, UUID protocolId) {
        return hits.stream().filter(hit -> hit.protocolId().equals(protocolId))
                .mapToInt(RetrievedChunk::lexicalMatches).max().orElse(-1);
    }

    private UUID insert(String title, String errorCode, String chunkText) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO protocol (id, machine_id, incident_date, protocol_type, error_code,
                                              title, language, source_file, status, uploaded_by)
                        VALUES (:id, :machineId, :date, 'STOERUNG', :errorCode, :title, 'de',
                                :source, 'INDEXED', 'schichtleiter')
                        """)
                .param("id", id).param("machineId", machineId).param("date", LocalDate.now())
                .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                .param("title", title).param("source", "PR-07/" + id + ".txt")
                .update();

        float[] embedding = vector(chunkText);
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            literal.append(i > 0 ? "," : "").append(embedding[i]);
        }
        jdbc.sql("""
                        INSERT INTO chunk (id, protocol_id, chunk_index, content, embedding,
                                           language, machine_id, error_code)
                        VALUES (:id, :protocolId, 0, :content, CAST(:embedding AS vector),
                                'de', :machineId, :errorCode)
                        """)
                .param("id", UUID.randomUUID()).param("protocolId", id).param("content", chunkText)
                .param("embedding", literal.append(']').toString())
                .param("machineId", machineId)
                .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                .update();
        return id;
    }

    /**
     * No provider call leaves the machine, and the {@code it} profile carries no key to make one
     * with — the retrieval SQL is what is under test, not an embedding.
     */
    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingClient fakeEmbeddingClient() {
            return new EmbeddingClient() {
                @Override
                public int dimensions() {
                    return 1024;
                }

                @Override
                public EmbeddingBatch embed(List<String> texts) {
                    return new EmbeddingBatch(texts.stream().map(HybridRetrievalIT::vector).toList(),
                            1, texts.size());
                }
            };
        }
    }
}
