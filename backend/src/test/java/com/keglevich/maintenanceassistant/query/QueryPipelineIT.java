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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query path against a real pgvector database.
 *
 * <p>What this covers and the unit tests cannot: that the single filtered nearest-neighbour
 * statement of ADR-004 is actually accepted by Postgres, that {@code 1 - (embedding <=> …)} produces
 * the similarity the threshold is expressed in, and — the assertion that matters most — that the
 * machine filter really scopes the result. A global top-k that happens to be filtered afterwards
 * would pass every unit test in this module and quietly answer a question about Presse 3 out of
 * Presse 7's protocols.
 *
 * <p>The chat provider is a fake and no call leaves the machine: tests must not depend on a provider
 * being up, on a key being present, or spend money to run. What a fake cannot cover — that the real
 * models follow the prompt — is covered by {@code spike/adr-002/} and by the live demo run recorded
 * in the pull request.
 *
 * <p>The embedding fake is topic-based rather than hash-based: text mentioning a topic gets that
 * topic's vector, so a question and the chunks about the same topic land at similarity 1.0 and
 * everything else near 0. That gives a deterministic ranking to assert against without pretending
 * this measures retrieval quality, which is measured against the real provider.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(QueryPipelineIT.FakeProviderConfig.class)
class QueryPipelineIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    JdbcClient jdbc;
    @Autowired
    QueryService queries;
    @Autowired
    QueryCache cache;
    @Autowired
    FakeChatClient chat;

    private UUID presse3;
    private UUID presse7;

    @BeforeEach
    void reset() {
        chat.reset();
        cache.clear();
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        jdbc.sql("DELETE FROM chat_budget").update();
        presse3 = machineId("PR-03");
        presse7 = machineId("PR-07");

        // The same fault on two machines. Everything below depends on the query telling them apart.
        seedChunk(presse3, "E-47 Druckabfall im Presshub", "E-47",
                "PR-03 · E-47 · Druckabfall\nSymptom: Presse kommt nicht auf Druck, E-47.");
        seedChunk(presse3, "E-47 sporadisch", "E-47",
                "PR-03 · E-47 · sporadisch\nSymptom: Druck bricht kurz ein, E-47.");
        seedChunk(presse7, "E-47 an Presse 7", "E-47",
                "PR-07 · E-47 · Druckabfall\nSymptom: Presse 7 kommt nicht auf Druck, E-47.");
        seedChunk(presse3, "Lichtvorhang", "E-08",
                "PR-03 · E-08 · Lichtvorhang\nSymptom: Presse startet nicht, Lichtvorhang meldet.");
    }

    @Test
    @DisplayName("the vector query returns only chunks of the machine it was scoped to")
    void retrievalIsScopedToOneMachine() {
        QueryAnswer answer = queries.ask("Presse kommt nicht auf Druck, Fehler E-47",
                presse3, QueryRole.TECHNIKER, "sub-1", false);

        assertThat(answer.mode()).isEqualTo(QueryAnswer.AnswerMode.A);
        // Every source offered to the model, and therefore every citation it could produce, belongs
        // to the machine that was asked about.
        assertThat(chat.lastUserPrompt())
                .contains("PR-03")
                .as("a global top-k filtered after ranking would leak the other press in here")
                .doesNotContain("PR-07");
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations()).allSatisfy(citation ->
                assertThat(protocolMachine(citation.protocolId())).isEqualTo(presse3));
    }

    @Test
    @DisplayName("the same question against another machine retrieves that machine's protocol")
    void theSameQuestionOnAnotherMachineRetrievesItsOwn() {
        queries.ask("Presse kommt nicht auf Druck, Fehler E-47", presse7, QueryRole.TECHNIKER, "sub-2", false);

        assertThat(chat.lastUserPrompt()).contains("PR-07").doesNotContain("PR-03");
    }

    @Test
    @DisplayName("an archived protocol cannot be retrieved even if its chunk somehow survived")
    void archivedProtocolsAreNeverRetrieved() {
        // Moderation deletes the chunks, so this state should not exist. The chunk is left in place
        // here deliberately, to test the SECOND line of defence rather than the first: ADR-006's
        // objection to soft deletion was that it makes every query grow a filter it must never
        // forget, and this is the query that must never forget it. A deleted protocol surfacing in
        // a list is embarrassing; one cited in a green Mode A answer is the failure this whole
        // application exists to prevent.
        jdbc.sql("""
                        UPDATE protocol SET deleted_at = now()
                        WHERE machine_id = :machineId AND title LIKE 'E-47%'
                        """)
                .param("machineId", presse3)
                .update();
        cache.clear();

        queries.ask("Presse kommt nicht auf Druck, Fehler E-47", presse3, QueryRole.TECHNIKER, "sub-9", false);

        assertThat(chat.lastUserPrompt())
                .as("the archived protocols must not reach the model as sources")
                .doesNotContain("Druck bricht kurz ein");
    }

    @Test
    @DisplayName("similarity comes back on the 0..1 scale the threshold is expressed in")
    void similarityIsReportedOnTheThresholdScale() {
        QueryAnswer answer = queries.ask("Presse kommt nicht auf Druck, Fehler E-47",
                presse3, QueryRole.TECHNIKER, "sub-3", false);

        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations()).allSatisfy(citation ->
                assertThat(citation.similarity()).isBetween(0.55, 1.0));
    }

    @Test
    @DisplayName("a question nothing covers is answered as Mode B, with no citations")
    void anUncoveredQuestionIsModeB() {
        QueryAnswer answer = queries.ask("Dosierpumpe laeuft ungleichmaessig",
                presse3, QueryRole.TECHNIKER, "sub-4", false);

        assertThat(answer.mode()).isEqualTo(QueryAnswer.AnswerMode.B);
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.claims()).isEmpty();
    }

    @Test
    @DisplayName("a machine with no indexed chunks answers Mode B rather than failing")
    void aMachineWithoutProtocolsAnswersModeB() {
        UUID empty = machineId("AB-02");

        assertThat(queries.ask("Foerderschnecke blockiert", empty, QueryRole.TECHNIKER, "sub-5", false).mode())
                .isEqualTo(QueryAnswer.AnswerMode.B);
    }

    @Test
    @DisplayName("chat usage is counted in the database, once per provider call")
    void usageIsCounted() {
        queries.ask("Presse kommt nicht auf Druck, Fehler E-47", presse3, QueryRole.TECHNIKER, "sub-6", false);

        Integer calls = jdbc.sql("SELECT calls FROM chat_budget WHERE usage_date = :d")
                .param("d", LocalDate.now()).query(Integer.class).single();
        Long completion = jdbc.sql("SELECT completion_tokens FROM chat_budget WHERE usage_date = :d")
                .param("d", LocalDate.now()).query(Long.class).single();

        assertThat(calls).isEqualTo(1);
        assertThat(completion)
                .as("chat bills output as well as input, so both are counted")
                .isPositive();
    }

    @Test
    @DisplayName("a second identical question is served from the cache and costs nothing")
    void aRepeatedQuestionCostsNothing() {
        queries.ask("Presse kommt nicht auf Druck, Fehler E-47", presse3, QueryRole.TECHNIKER, "sub-7", false);
        queries.ask("  presse KOMMT nicht auf Druck, Fehler E-47 ", presse3, QueryRole.TECHNIKER, "sub-7", false);

        assertThat(chat.calls()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT calls FROM chat_budget WHERE usage_date = :d")
                .param("d", LocalDate.now()).query(Integer.class).single())
                .as("a cache hit reaches no provider, so it must not appear in the day's spend")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private UUID machineId(String machineNo) {
        return jdbc.sql("SELECT id FROM machine WHERE machine_no = :no")
                .param("no", machineNo).query(UUID.class).single();
    }

    private UUID protocolMachine(UUID protocolId) {
        return jdbc.sql("SELECT machine_id FROM protocol WHERE id = :id")
                .param("id", protocolId).query(UUID.class).single();
    }

    private void seedChunk(UUID machineId, String title, String errorCode, String content) {
        UUID protocolId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO protocol (id, machine_id, incident_date, protocol_type, error_code,
                                              title, language, status, uploaded_by)
                        VALUES (:id, :machineId, :date, 'STOERUNG', :errorCode, :title, 'de',
                                'INDEXED', 'schichtleiter')
                        """)
                .param("id", protocolId)
                .param("machineId", machineId)
                .param("date", LocalDate.now())
                .param("errorCode", errorCode)
                .param("title", title)
                .update();

        jdbc.sql("""
                        INSERT INTO chunk (id, protocol_id, chunk_index, content, embedding,
                                           language, machine_id, error_code)
                        VALUES (:id, :protocolId, 0, :content, CAST(:embedding AS vector),
                                'de', :machineId, :errorCode)
                        """)
                .param("id", UUID.randomUUID())
                .param("protocolId", protocolId)
                .param("content", content)
                .param("embedding", vectorLiteral(TopicEmbedding.of(content)))
                .param("machineId", machineId)
                .param("errorCode", errorCode)
                .update();
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        return out.append(']').toString();
    }

    // ---------------------------------------------------------------------------------------
    // The fake provider
    // ---------------------------------------------------------------------------------------

    @TestConfiguration
    static class FakeProviderConfig {

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
                    List<float[]> vectors = new ArrayList<>(texts.size());
                    texts.forEach(text -> vectors.add(TopicEmbedding.of(text)));
                    return new EmbeddingBatch(vectors, 1, texts.size());
                }
            };
        }

        @Bean
        @Primary
        FakeChatClient fakeChatClient(ChatBudget budget) {
            return new FakeChatClient(budget);
        }
    }

    /**
     * One axis per topic, so texts about the same topic are identical vectors (similarity 1.0) and
     * texts about different ones are orthogonal (0.0). Crude on purpose: the point of this test is
     * the SQL and the routing, and a deterministic ranking makes both assertable.
     */
    static final class TopicEmbedding {

        private static final List<String> TOPICS = List.of("e-47", "lichtvorhang", "dosier", "schnecke");

        static float[] of(String text) {
            float[] vector = new float[1024];
            String lower = text.toLowerCase(Locale.ROOT);
            boolean matched = false;
            for (int i = 0; i < TOPICS.size(); i++) {
                if (lower.contains(TOPICS.get(i))) {
                    vector[i] = 1.0f;
                    matched = true;
                }
            }
            if (!matched) {
                // Its own axis, so "nothing matches" is orthogonal rather than a zero vector, which
                // pgvector's cosine distance cannot rank.
                vector[TOPICS.size()] = 1.0f;
            }
            return vector;
        }
    }

    /** The chat provider, replaced by a script that answers from whatever it was given. */
    static final class FakeChatClient implements ChatClient {

        private final AtomicInteger calls = new AtomicInteger();
        private final ChatBudget budget;
        private volatile String lastUserPrompt;

        FakeChatClient(ChatBudget budget) {
            this.budget = budget;
        }

        void reset() {
            calls.set(0);
            lastUserPrompt = null;
        }

        int calls() {
            return calls.get();
        }

        String lastUserPrompt() {
            return lastUserPrompt;
        }

        @Override
        public String model() {
            return "fake-chat";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Completion complete(Prompt prompt) {
            calls.incrementAndGet();
            lastUserPrompt = prompt.user();
            // Same contract as the real client: the implementation records its own usage, as the
            // provider serves the request. A fake that skipped this would make the budget test
            // pass against behaviour production does not have.
            budget.record(1, 120L, 40L);
            String content = prompt.schemaName().equals(GroundedPrompt.SCHEMA_NAME)
                    ? """
                    {"answer_language":"de","claims":[{"text":"Die Presse kommt nicht auf Druck.","source":"P1"}]}"""
                    : """
                    {"answer_language":"de","steps":["Kein Protokoll deckt diesen Fall ab.","Sichtpruefung durchfuehren."]}""";
            return new Completion(content, 120L, 40L, 5L);
        }
    }
}
