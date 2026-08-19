package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detector, tested against a planted defect.
 *
 * <p>This one runs in CI: Testcontainers and the deterministic fake client, so no key and no money.
 * That is the point of testing it here rather than only in the key-gated baseline — <b>a detector
 * nobody can check is a claim</b>, and this one exists because a whole class of corruption had gone
 * unnoticed for a week.
 *
 * <p>The fake is what makes the test possible: it is deterministic, so re-embedding a chunk's own
 * text reproduces its stored vector exactly, which is the same property the real provider has and
 * the property the verifier rests on. The planted defect is a chunk whose stored vector is the
 * embedding of <em>different</em> text — precisely what a foreign model leaves behind, and precisely
 * what {@code status = 'INDEXED'}, the vector width and the norm all fail to reveal.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(EmbeddingProvenanceVerifierIT.FakeEmbeddingConfig.class)
class EmbeddingProvenanceVerifierIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path filesDir;

    @Autowired JdbcClient jdbc;
    @Autowired ProtocolIndexer indexer;
    @Autowired FakeEmbeddingClient embeddingClient;
    @Autowired EmbeddingProvenanceVerifier verifier;

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

    @Test
    @DisplayName("an index written by the configured client reports clean")
    void agreesWithItsOwnClient() throws IOException {
        indexOne("E-47 Druckabfall im Presshub");

        EmbeddingProvenanceVerifier.Report report = verifier.verify(0);

        assertThat(report.probes()).isNotEmpty();
        assertThat(report.clean()).as(report.describe()).isTrue();
        assertThat(report.probes()).allSatisfy(probe ->
                assertThat(probe.agreement()).isGreaterThan(EmbeddingProvenanceVerifier.AGREEMENT_FLOOR));
    }

    @Test
    @DisplayName("a chunk whose vector came from elsewhere is named, and the healthy ones are not")
    void findsThePlantedForeignVector() throws IOException {
        UUID healthy = indexOne("E-47 Druckabfall im Presshub");
        UUID corrupted = indexOne("Öl unter der Presse");

        // The defect, planted exactly as it occurs in the wild: a well-formed, right-width,
        // unit-length vector that is simply not this text's. The row stays INDEXED and nothing else
        // about it changes.
        overwriteVectorWithAnotherTexts(corrupted);

        EmbeddingProvenanceVerifier.Report report = verifier.verify(0);

        assertThat(report.clean()).isFalse();
        assertThat(report.foreign())
                .as("only the planted chunk should be named:\n%s", report.describe())
                .singleElement()
                .satisfies(probe -> assertThat(probe.protocolId()).isEqualTo(corrupted));

        // And the healthy neighbour is still reported as healthy — a detector that flags everything
        // once anything is wrong would be useless for deciding what to re-index.
        assertThat(report.probes())
                .filteredOn(probe -> probe.protocolId().equals(healthy))
                .singleElement()
                .satisfies(probe -> assertThat(probe.foreign()).isFalse());

        assertThat(status(corrupted))
                .as("the point of the check: the row looks perfectly healthy from the database")
                .isEqualTo("INDEXED");
    }

    @Test
    @DisplayName("re-indexing repairs it — the same path the runbook and PR #62's repair use")
    void reindexingClearsTheFinding() throws IOException {
        UUID corrupted = indexOne("Öl unter der Presse");
        overwriteVectorWithAnotherTexts(corrupted);
        assertThat(verifier.verify(0).clean()).isFalse();

        assertThat(indexer.index(corrupted)).isTrue();

        assertThat(verifier.verify(0).clean())
                .as("delete-then-write replaces the vector rather than adding beside it")
                .isTrue();
    }

    // -----------------------------------------------------------------------------------------

    /** Gives the chunk the embedding of a different text: the shape of a foreign-model vector. */
    private void overwriteVectorWithAnotherTexts(UUID protocolId) {
        float[] wrong = embeddingClient.embed(java.util.List.of("something else entirely"))
                .vectors().get(0);
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < wrong.length; i++) {
            literal.append(i > 0 ? "," : "").append(wrong[i]);
        }
        int updated = jdbc.sql("""
                        UPDATE chunk SET embedding = CAST(:v AS vector) WHERE protocol_id = :id
                        """)
                .param("v", literal.append(']').toString())
                .param("id", protocolId)
                .update();
        assertThat(updated).as("the fixture must actually plant something").isPositive();
    }

    private UUID indexOne(String title) throws IOException {
        UUID id = UUID.randomUUID();
        String relative = "PR-03/%s.txt".formatted(id);
        Path path = filesDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                WARTUNGSPROTOKOLL

                Maschine: PR-03

                Symptom:
                %s — Beschreibung fuer den Provenienz-Test.
                """.formatted(title), StandardCharsets.UTF_8);

        jdbc.sql("""
                        INSERT INTO protocol (id, machine_id, incident_date, protocol_type, error_code,
                                              title, language, source_file, status, uploaded_by)
                        VALUES (:id, :machineId, :date, 'STOERUNG', NULL,
                                :title, 'de', :sourceFile, 'RECEIVED', 'schichtleiter')
                        """)
                .param("id", id)
                .param("machineId", machineId)
                .param("date", LocalDate.now())
                .param("title", title)
                .param("sourceFile", relative)
                .update();

        assertThat(indexer.index(id)).isTrue();
        return id;
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient(EmbeddingBudget budget) {
            return new FakeEmbeddingClient(budget);
        }

        @Bean
        DynamicPropertyRegistrar protocolFilesPath() {
            return registry -> registry.add("maintenance.files.base-path", () -> filesDir.toString());
        }
    }
}
