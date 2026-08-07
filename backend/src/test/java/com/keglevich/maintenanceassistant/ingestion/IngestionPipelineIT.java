package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The ingestion pipeline against a real database.
 *
 * <p>Testcontainers rather than an in-memory database, and the same pgvector image production runs.
 * The things most worth testing here — that {@code vector(1024)} accepts what the client produces,
 * that the HNSW index survives a delete-and-reinsert, that Flyway V1 and V2 apply in order — do not
 * exist on H2. The alternative to a container is not a cheaper test, it is no test of the part that
 * can actually break.
 *
 * <p>The embedding client is a fake producing deterministic vectors. No call leaves the machine:
 * tests must not depend on a provider being up, on a key being present, or spend money to run. What
 * the fake cannot cover — that IONOS accepts our request shape — is covered by
 * {@code spike/spring-ai-boot4/} and by the manual corpus run recorded in the pull request.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(IngestionPipelineIT.FakeEmbeddingConfig.class)
class IngestionPipelineIT {

    /** The production image, not a plain postgres one: the schema needs the vector extension. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path filesDir;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ProtocolIndexer indexer;
    @Autowired
    IngestionBacklogService backlog;
    @Autowired
    FakeEmbeddingClient embeddingClient;

    private UUID machineId;

    @BeforeEach
    void reset() {
        embeddingClient.reset();
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        jdbc.sql("DELETE FROM embedding_budget").update();
        machineId = jdbc.sql("SELECT id FROM machine WHERE machine_no = 'PR-03'")
                .query(UUID.class).single();
    }

    // ---------------------------------------------------------------------
    // The happy path
    // ---------------------------------------------------------------------

    @Test
    void indexesAProtocolIntoChunksAndMarksItIndexed() throws IOException {
        UUID id = seedProtocol("E-47 Druckabfall im Presshub", "E-47", """
                WARTUNGSPROTOKOLL

                Maschine: PR-03
                Fehlercode: E-47

                Symptom:
                Presse kommt nicht auf Druck, Manometer zeigt 180 statt 250 bar.

                Ursache:
                Innere Leckage am Hauptzylinder, Kolbendichtung verschlissen.

                Massnahme:
                Dichtsatz erneuert, entlüftet, Haltedruck über 5 Zyklen geprüft.
                """);

        assertThat(indexer.index(id)).isTrue();

        assertThat(status(id)).isEqualTo("INDEXED");
        assertThat(failureReason(id)).isNull();
        assertThat(indexedAt(id)).isNotNull();
        assertThat(chunkCount(id)).isPositive();
    }

    @Test
    void everyChunkStoresA1024DimensionVector() throws IOException {
        UUID id = seedProtocol("Ölleckage", null, "Symptom:\nÖllache unter dem Zylinder.\n");
        indexer.index(id);

        // Read the width back from the vector itself rather than trusting the column type: a
        // client that returned the wrong number of values would fail the insert, and a client
        // that returned the right count of garbage would not — this checks what was stored.
        List<Integer> widths = jdbc.sql("SELECT vector_dims(embedding) AS d FROM chunk WHERE protocol_id = :id")
                .param("id", id)
                .query(Integer.class)
                .list();
        assertThat(widths).isNotEmpty().allMatch(d -> d == 1024);
    }

    @Test
    void everyChunkCarriesTheDenormalizedMachineAndErrorCode() throws IOException {
        UUID id = seedProtocol("E-47 sporadisch", " e-47 ", "Symptom:\nDruck bricht kurz ein.\n");
        indexer.index(id);

        List<String> codes = jdbc.sql("SELECT error_code FROM chunk WHERE protocol_id = :id")
                .param("id", id).query(String.class).list();
        List<UUID> machines = jdbc.sql("SELECT machine_id FROM chunk WHERE protocol_id = :id")
                .param("id", id).query(UUID.class).list();

        // The filter half of the ADR-004 one-query retrieval lives on the chunk, so it has to be
        // written by ingestion or the Phase 3 query returns nothing.
        assertThat(machines).isNotEmpty().allMatch(machineId::equals);
        assertThat(codes).isNotEmpty().allMatch(" e-47 "::equals);
    }

    @Test
    void chunkTextCarriesTheProtocolContextSoItStandsAlone() throws IOException {
        UUID id = seedProtocol("E-47 Druckabfall im Presshub", "E-47", "Symptom:\nKommt nicht auf Druck.\n");
        indexer.index(id);

        List<String> contents = jdbc.sql("SELECT content FROM chunk WHERE protocol_id = :id ORDER BY chunk_index")
                .param("id", id).query(String.class).list();

        assertThat(contents).isNotEmpty();
        assertThat(contents).allSatisfy(content -> assertThat(content)
                .as("a retrieved chunk must still say which machine and fault it belongs to")
                .contains("PR-03")
                .contains("E-47"));
    }

    // ---------------------------------------------------------------------
    // Idempotence — the property the catch-up path depends on
    // ---------------------------------------------------------------------

    @Test
    void reprocessingReplacesChunksInsteadOfDuplicatingThem() throws IOException {
        UUID id = seedProtocol("Wiederholung", null, "Symptom:\nBand quietscht.\n\nUrsache:\nLager fest.\n");

        indexer.index(id);
        long afterFirst = chunkCount(id);
        List<UUID> firstIds = chunkIds(id);

        indexer.index(id);
        indexer.index(id);

        assertThat(chunkCount(id))
                .as("three runs of the same protocol must leave one set of chunks")
                .isEqualTo(afterFirst);
        assertThat(chunkIds(id))
                .as("delete-then-insert, so the rows are genuinely rewritten rather than left in place")
                .doesNotContainAnyElementsOf(firstIds);
        assertThat(status(id)).isEqualTo("INDEXED");
    }

    @Test
    void theBacklogPicksUpSeededProtocolsThatNeverProducedAnEvent() throws IOException {
        // Exactly the situation of the 150 corpus protocols: rows written straight to the
        // database, sitting at RECEIVED with no listener coming for them.
        UUID first = seedProtocol("Erstes", null, "Symptom:\nEins.\n");
        UUID second = seedProtocol("Zweites", null, "Symptom:\nZwei.\n");
        UUID third = seedProtocol("Drittes", null, "Symptom:\nDrei.\n");

        int enqueued = backlog.enqueue(List.of("RECEIVED"), null);
        assertThat(enqueued).isEqualTo(3);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(List.of(status(first), status(second), status(third)))
                        .containsOnly("INDEXED"));

        assertThat(chunkCount(first)).isPositive();
        assertThat(backlog.enqueue(List.of("RECEIVED"), null))
                .as("nothing is left at RECEIVED once the run finished")
                .isZero();
    }

    // ---------------------------------------------------------------------
    // Failure is a recorded state, not a lost protocol
    // ---------------------------------------------------------------------

    @Test
    void aMissingDocumentFailsTheProtocolWithAReasonAndKeepsTheRow() {
        UUID id = UUID.randomUUID();
        insertRow(id, "Datei fehlt", null, "PR-03/does-not-exist.txt");

        assertThat(indexer.index(id)).isFalse();

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(failureReason(id))
                .as("a FAILED protocol with no reason forces someone to reproduce the failure")
                .contains("source file missing");
        assertThat(jdbc.sql("SELECT count(*) FROM protocol WHERE id = :id").param("id", id)
                .query(Long.class).single())
                .as("the row is kept so the protocol can be retried")
                .isEqualTo(1L);
    }

    @Test
    void aProviderFailureLeavesNoPartialChunks() throws IOException {
        UUID id = seedProtocol("Provider weg", null, "Symptom:\nEtwas.\n\nUrsache:\nAnderes.\n");
        embeddingClient.failNext("provider returned 503");

        assertThat(indexer.index(id)).isFalse();

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(failureReason(id)).contains("503");
        assertThat(chunkCount(id))
                .as("a protocol is either fully indexed or not indexed at all")
                .isZero();
    }

    @Test
    void aFailedProtocolIsPickedUpAgainAndSucceeds() throws IOException {
        UUID id = seedProtocol("Zweiter Versuch", null, "Symptom:\nEtwas.\n");
        embeddingClient.failNext("transient");
        indexer.index(id);
        assertThat(status(id)).isEqualTo("FAILED");

        backlog.enqueue(List.of("FAILED"), null);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo("INDEXED"));
        assertThat(failureReason(id))
                .as("a successful retry must clear the previous reason, not leave it to confuse the next reader")
                .isNull();
    }

    // ---------------------------------------------------------------------
    // NFR-7
    // ---------------------------------------------------------------------

    @Test
    void usageIsCountedAndTheDailyBudgetStopsIngestionGracefully() throws IOException {
        UUID first = seedProtocol("Erstes", null, "Symptom:\nEins.\n");
        indexer.index(first);

        Integer calls = jdbc.sql("SELECT calls FROM embedding_budget WHERE usage_date = :d")
                .param("d", LocalDate.now()).query(Integer.class).single();
        Long tokens = jdbc.sql("SELECT prompt_tokens FROM embedding_budget WHERE usage_date = :d")
                .param("d", LocalDate.now()).query(Long.class).single();
        assertThat(calls).isPositive();
        assertThat(tokens).isPositive();

        // Push the counter past the test profile's ceiling and confirm the next protocol stops
        // rather than spending: a graceful stop keeps the row and says why.
        jdbc.sql("UPDATE embedding_budget SET calls = 9999 WHERE usage_date = :d")
                .param("d", LocalDate.now()).update();

        UUID second = seedProtocol("Zweites", null, "Symptom:\nZwei.\n");
        assertThat(indexer.index(second)).isFalse();
        assertThat(status(second)).isEqualTo("FAILED");
        assertThat(failureReason(second)).contains("daily embedding budget");
        assertThat(chunkCount(second)).isZero();
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private UUID seedProtocol(String title, String errorCode, String documentText) throws IOException {
        UUID id = UUID.randomUUID();
        String relative = "PR-03/%s.txt".formatted(id);
        Path path = filesDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, documentText, StandardCharsets.UTF_8);
        insertRow(id, title, errorCode, relative);
        return id;
    }

    private void insertRow(UUID id, String title, String errorCode, String sourceFile) {
        jdbc.sql("""
                        INSERT INTO protocol (id, machine_id, incident_date, protocol_type, error_code,
                                              title, language, source_file, status, uploaded_by)
                        VALUES (:id, :machineId, :date, 'STOERUNG', :errorCode,
                                :title, 'de', :sourceFile, 'RECEIVED', 'schichtleiter')
                        """)
                .param("id", id)
                .param("machineId", machineId)
                .param("date", LocalDate.now())
                .param("errorCode", errorCode, java.sql.Types.VARCHAR)
                .param("title", title)
                .param("sourceFile", sourceFile)
                .update();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM protocol WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private String failureReason(UUID id) {
        return jdbc.sql("SELECT failure_reason FROM protocol WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private java.time.OffsetDateTime indexedAt(UUID id) {
        return jdbc.sql("SELECT indexed_at FROM protocol WHERE id = :id").param("id", id)
                .query(java.time.OffsetDateTime.class).optional().orElse(null);
    }

    private long chunkCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM chunk WHERE protocol_id = :id").param("id", id)
                .query(Long.class).single();
    }

    private List<UUID> chunkIds(UUID id) {
        return jdbc.sql("SELECT id FROM chunk WHERE protocol_id = :id").param("id", id)
                .query(UUID.class).list();
    }

    // ---------------------------------------------------------------------
    // The fake provider
    // ---------------------------------------------------------------------

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient(EmbeddingBudget budget) {
            return new FakeEmbeddingClient(budget);
        }

        /** Points the file volume at the test temp directory. */
        @Bean
        DynamicPropertyRegistrar protocolFilesPath() {
            return registry -> registry.add("maintenance.files.base-path", () -> filesDir.toString());
        }
    }

    /**
     * Deterministic vectors derived from the text's hash. Not meaningful as embeddings — this test
     * is about the pipeline and the schema, not about retrieval quality, which is measured against
     * the real provider in the manual corpus run.
     */
    static class FakeEmbeddingClient implements EmbeddingClient {

        private final AtomicInteger calls = new AtomicInteger();
        private final EmbeddingBudget budget;
        private volatile String nextFailure;

        FakeEmbeddingClient(EmbeddingBudget budget) {
            this.budget = budget;
        }

        void reset() {
            calls.set(0);
            nextFailure = null;
        }

        void failNext(String message) {
            nextFailure = message;
        }

        @Override
        public int dimensions() {
            return 1024;
        }

        @Override
        public EmbeddingBatch embed(List<String> texts) {
            String failure = nextFailure;
            if (failure != null) {
                nextFailure = null;
                throw new EmbeddingException(failure);
            }
            calls.incrementAndGet();
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                float[] vector = new float[1024];
                int seed = text.hashCode();
                for (int i = 0; i < vector.length; i++) {
                    seed = seed * 1_103_515_245 + 12_345;
                    vector[i] = (seed >>> 8) / (float) (1 << 24) - 0.5f;
                }
                vectors.add(vector);
            }
            // Roughly a token per four characters, so the budget assertions have something real to
            // count without pretending this is the provider's own accounting.
            long tokens = texts.stream().mapToLong(t -> Math.max(1, t.length() / 4)).sum();
            // Same contract as the real client: the implementation records its own usage.
            budget.record(1, tokens);
            return new EmbeddingBatch(vectors, 1, tokens);
        }
    }
}
