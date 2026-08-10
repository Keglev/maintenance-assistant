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
 * Removing a protocol, against a real database and a real volume.
 *
 * <p>The claim under test is the one a mocked repository cannot make: that a delete leaves <b>no
 * retrievable orphan</b>. Chunks are what retrieval searches, so a chunk outliving its protocol is
 * not a tidiness problem — it can still be ranked, returned and cited, pointing at a row that no
 * longer exists. That is the failure this ordering exists to prevent, and the only way to see it is
 * to look in the tables afterwards.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(ProtocolModerationIT.FilesConfig.class)
class ProtocolModerationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path filesDir;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ProtocolModerationService moderation;
    @Autowired
    ProtocolDocumentService documents;

    private UUID machineId;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        machineId = jdbc.sql("SELECT id FROM machine WHERE machine_no = 'PR-03'")
                .query(UUID.class).single();
    }

    @Test
    @DisplayName("a delete removes the chunks, the row and the file")
    void deleteLeavesNoOrphan() throws IOException {
        UUID id = seedProtocol("E-47 Druckabfall", "Symptom:\nKein Druck.\n");
        seedChunk(id, "Symptom: kein Druck");
        seedChunk(id, "Massnahme: Dichtsatz getauscht");
        Path file = filesDir.resolve("PR-03/%s.txt".formatted(id));
        assertThat(file).exists();

        boolean removed = moderation.delete(id, "admin");

        assertThat(removed).isTrue();
        assertThat(countChunks(id))
                .as("a chunk outliving its protocol is retrievable garbage: still rankable, still "
                        + "citable, pointing at a row that is gone")
                .isZero();
        assertThat(countProtocols(id)).isZero();
        assertThat(file).as("the file is inert once the row is gone, but it is still removed").doesNotExist();
    }

    @Test
    @DisplayName("a deleted protocol has no document afterwards — its old citation 404s")
    void aDeletedProtocolIsUnreadable() throws IOException {
        UUID id = seedProtocol("E-47 Druckabfall", "Symptom:\nKein Druck.\n");
        assertThat(documents.find(id)).isPresent();

        moderation.delete(id, "admin");

        // This is what a stale citation in an old answer hits, and 404 is the honest reply.
        assertThat(documents.find(id)).isEmpty();
    }

    @Test
    @DisplayName("deleting an unknown protocol reports it rather than pretending")
    void deletingWhatIsNotThereReturnsFalse() {
        assertThat(moderation.delete(UUID.randomUUID(), "admin")).isFalse();
    }

    @Test
    @DisplayName("a second delete completes what a half-failed first one left behind")
    void deleteIsSafeToRetry() throws IOException {
        UUID id = seedProtocol("Halb entfernt", "Symptom:\nEtwas.\n");
        seedChunk(id, "ein Chunk");
        // The shape a crash between steps leaves: the file is already gone, the row is not. A
        // retry has to finish the job rather than fail on the part that is already done.
        Files.delete(filesDir.resolve("PR-03/%s.txt".formatted(id)));

        assertThat(moderation.delete(id, "admin")).isTrue();
        assertThat(countChunks(id)).isZero();
        assertThat(countProtocols(id)).isZero();
        // And calling it once more is simply false — nothing throws, nothing is half-done.
        assertThat(moderation.delete(id, "admin")).isFalse();
    }

    @Test
    @DisplayName("the corpus lists newest first, paged, with the author and the chunk count")
    void theCorpusIsPaged() throws IOException {
        UUID older = seedProtocol("Aelteres", "Symptom:\nEins.\n");
        seedChunk(older, "ein Chunk");
        UUID newer = seedProtocol("Neueres", "Symptom:\nZwei.\n");
        jdbc.sql("UPDATE protocol SET created_at = now() + interval '1 minute' WHERE id = :id")
                .param("id", newer).update();

        ProtocolModerationService.ProtocolPage first = moderation.list(0, 1);

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).hasSize(1);
        assertThat(first.items().get(0).title()).isEqualTo("Neueres");
        assertThat(first.items().get(0).uploadedBy()).isEqualTo("schichtleiter");

        ProtocolModerationService.ProtocolPage second = moderation.list(1, 1);
        assertThat(second.items().get(0).title()).isEqualTo("Aelteres");
        assertThat(second.items().get(0).chunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the page size is clamped, so a client cannot ask for the whole corpus at once")
    void thePageSizeIsClamped() throws IOException {
        for (int i = 0; i < 3; i++) {
            seedProtocol("Protokoll " + i, "Symptom:\nEtwas.\n");
        }

        // Asked for 5000; the cap is what decides. Without it, "size" is a denial-of-service knob
        // on a table that grows every time a protocol is uploaded.
        assertThat(moderation.list(0, 5_000).size())
                .isEqualTo(ProtocolModerationService.MAX_PAGE_SIZE);
        // And a nonsensical page or size does not throw.
        assertThat(moderation.list(-1, 0).items()).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------

    private long countChunks(UUID protocolId) {
        return jdbc.sql("SELECT count(*) FROM chunk WHERE protocol_id = :id")
                .param("id", protocolId).query(Long.class).single();
    }

    private long countProtocols(UUID protocolId) {
        return jdbc.sql("SELECT count(*) FROM protocol WHERE id = :id")
                .param("id", protocolId).query(Long.class).single();
    }

    private UUID seedProtocol(String title, String documentText) throws IOException {
        UUID id = UUID.randomUUID();
        String relative = "PR-03/%s.txt".formatted(id);
        Path path = filesDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, documentText, StandardCharsets.UTF_8);
        jdbc.sql("""
                        INSERT INTO protocol (id, machine_id, incident_date, protocol_type, title,
                                              language, source_file, status, uploaded_by)
                        VALUES (:id, :machineId, :date, 'STOERUNG', :title, 'de', :sourceFile,
                                'INDEXED', 'schichtleiter')
                        """)
                .param("id", id)
                .param("machineId", machineId)
                .param("date", LocalDate.now())
                .param("title", title)
                .param("sourceFile", relative)
                .update();
        return id;
    }

    private void seedChunk(UUID protocolId, String content) {
        jdbc.sql("""
                        INSERT INTO chunk (id, protocol_id, chunk_index, content, language,
                                           machine_id, error_code)
                        VALUES (:id, :protocolId,
                                (SELECT coalesce(max(chunk_index) + 1, 0) FROM chunk WHERE protocol_id = :protocolId),
                                :content, 'de', :machineId, NULL)
                        """)
                .param("id", UUID.randomUUID())
                .param("protocolId", protocolId)
                .param("content", content)
                .param("machineId", machineId)
                .update();
    }

    @TestConfiguration
    static class FilesConfig {
        @Bean
        DynamicPropertyRegistrar protocolFilesPath() {
            return registry -> registry.add("maintenance.files.base-path", () -> filesDir.toString());
        }
    }
}
