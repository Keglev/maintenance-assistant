package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.query.MachineCatalog;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a source document off the volume, and the machine list behind the picker — both against a
 * real database and a real directory.
 *
 * <p>The parts worth testing here are the ones a mocked service cannot have: that {@code source_file}
 * resolves against the configured base path, that a row pointing at a file which is not there
 * degrades to "not found" rather than to an exception, and that a path escaping the volume is
 * refused even though it came out of our own database.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(ProtocolDocumentIT.FilesConfig.class)
class ProtocolDocumentIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path filesDir;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ProtocolDocumentService documents;
    @Autowired
    ProtocolStatusService statuses;
    @Autowired
    MachineCatalog machines;

    private UUID machineId;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        machineId = jdbc.sql("SELECT id FROM machine WHERE machine_no = 'PR-03'")
                .query(UUID.class).single();
    }

    // ---------------------------------------------------------------------------------------
    // The document behind a citation
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the stored document is returned as readable UTF-8 text")
    void theDocumentComesBackOffTheVolume() throws IOException {
        String text = "Symptom:\nPresse kommt nicht auf Druck, Manometer zeigt 180 statt 250 bar.\n";
        UUID id = seedProtocol("E-47 Druckabfall im Presshub", text);

        ProtocolDocumentService.ProtocolDocument document = documents.find(id).orElseThrow();

        assertThat(document.contentType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(document.sizeBytes()).isPositive();
        try (var stream = document.resource().getInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .as("the corpus is German; a charset the browser has to guess produces mojibake")
                    .isEqualTo(text);
        }
    }

    @Test
    @DisplayName("the download name says which machine and fault, not which UUID")
    void theDownloadNameIsReadable() throws IOException {
        UUID id = seedProtocol("E-47 Druckabfall im Presshub", "Symptom:\nEtwas.\n");

        String name = documents.find(id).orElseThrow().downloadName();

        // A technician who saves three sources gets three files they can tell apart.
        assertThat(name).isEqualTo("PR-03-E-47-Druckabfall-im-Presshub.txt");
    }

    @Test
    @DisplayName("umlauts are reduced rather than carried into the filename")
    void theDownloadNameIsAscii() throws IOException {
        UUID id = seedProtocol("Ölleckage an der Größe, außen", "Symptom:\nÖl.\n");

        String name = documents.find(id).orElseThrow().downloadName();

        assertThat(name)
                .as("a Content-Disposition filename crossing an umlaut is a known mojibake source")
                .isEqualTo("PR-03-Olleckage-an-der-Grosse-aussen.txt")
                .matches("[\\x20-\\x7E]+");
    }

    @Test
    @DisplayName("an unknown protocol has no document")
    void anUnknownProtocolIsEmpty() {
        assertThat(documents.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a row whose file was never written degrades to empty rather than throwing")
    void aMissingFileIsEmpty() {
        UUID id = UUID.randomUUID();
        insertRow(id, "Datei fehlt", "PR-03/does-not-exist.txt");

        assertThat(documents.find(id))
                .as("the volume and the database can disagree — a restored dump without its "
                        + "archive is exactly that, and it must not be a 500")
                .isEmpty();
    }

    @Test
    @DisplayName("a row with no source_file at all is empty too")
    void aNullSourceFileIsEmpty() {
        UUID id = UUID.randomUUID();
        insertRow(id, "Kein Pfad", null);

        assertThat(documents.find(id)).isEmpty();
    }

    @Test
    @DisplayName("a stored path pointing outside the volume is refused")
    void aPathEscapingTheVolumeIsRefused() throws IOException {
        // The path is ours and is still checked: a column is only as trustworthy as everything
        // that has ever written to it, including a future import or a manual fix.
        Path outside = filesDir.getParent().resolve("outside-the-volume.txt");
        Files.writeString(outside, "not part of the corpus", StandardCharsets.UTF_8);

        UUID id = UUID.randomUUID();
        insertRow(id, "Ausbruch", "../" + outside.getFileName());

        assertThat(documents.find(id)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Own uploads
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("own uploads list newest first, with the failure reason when there is one")
    void ownUploadsCarryTheirStatus() throws IOException {
        seedProtocol("Aelteres", "Symptom:\nEins.\n");
        UUID failed = seedProtocol("Neueres", "Symptom:\nZwei.\n");
        jdbc.sql("""
                        UPDATE protocol SET status = 'FAILED', failure_reason = 'provider returned 503',
                                            created_at = now() + interval '1 minute'
                        WHERE id = :id
                        """)
                .param("id", failed).update();

        List<ProtocolStatusService.UploadStatus> mine = statuses.findRecentUploadsOf("schichtleiter");

        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).title()).isEqualTo("Neueres");
        assertThat(mine.get(0).status()).isEqualTo("FAILED");
        assertThat(mine.get(0).failureReason()).contains("503");
        assertThat(mine.get(0).machineNo()).isEqualTo("PR-03");
    }

    @Test
    @DisplayName("one uploader never sees another's uploads")
    void ownUploadsAreScopedToTheUploader() throws IOException {
        seedProtocol("Von schichtleiter", "Symptom:\nEins.\n");

        assertThat(statuses.findRecentUploadsOf("jemand-anders"))
                .as("the endpoint takes the username from the token precisely so this cannot be asked")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // The machine picker
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the machine list is the seeded plant, ordered by plant identifier")
    void machinesAreListedForThePicker() {
        List<MachineCatalog.Machine> all = machines.findAll();

        assertThat(all).hasSize(10);
        assertThat(all).extracting(MachineCatalog.Machine::machineNo).isSorted();
        Optional<MachineCatalog.Machine> presse = all.stream()
                .filter(machine -> machine.machineNo().equals("PR-03")).findFirst();
        assertThat(presse).isPresent();
        assertThat(presse.get().name()).isEqualTo("Presse 3");
        assertThat(presse.get().id())
                .as("the id is what POST /api/query takes; without it the picker is decorative")
                .isEqualTo(machineId);
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private UUID seedProtocol(String title, String documentText) throws IOException {
        UUID id = UUID.randomUUID();
        String relative = "PR-03/%s.txt".formatted(id);
        Path path = filesDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, documentText, StandardCharsets.UTF_8);
        insertRow(id, title, relative);
        return id;
    }

    private void insertRow(UUID id, String title, String sourceFile) {
        jdbc.sql("""
                        INSERT INTO protocol (id, machine_id, incident_date, protocol_type, title,
                                              language, source_file, status, uploaded_by)
                        VALUES (:id, :machineId, :date, 'STOERUNG', :title, 'de', :sourceFile,
                                'RECEIVED', 'schichtleiter')
                        """)
                .param("id", id)
                .param("machineId", machineId)
                .param("date", LocalDate.now())
                .param("title", title)
                .param("sourceFile", sourceFile, java.sql.Types.VARCHAR)
                .update();
    }

    @TestConfiguration
    static class FilesConfig {
        /** Points the volume at the test temp directory, as the ingestion suite does. */
        @Bean
        DynamicPropertyRegistrar protocolFilesPath() {
            return registry -> registry.add("maintenance.files.base-path", () -> filesDir.toString());
        }
    }
}
