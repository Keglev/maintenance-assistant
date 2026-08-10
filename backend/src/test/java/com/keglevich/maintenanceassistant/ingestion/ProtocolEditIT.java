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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Correcting a protocol, against a real database and a real volume.
 *
 * <p><b>The claim worth a container is that an edit re-indexes.</b> An edit that rewrote the file
 * and left the old chunks would be strictly worse than no edit at all: the document would read
 * correctly while retrieval kept matching text it no longer contains, and a citation would point at
 * a protocol that no longer says what was matched. Nothing short of looking in the {@code chunk}
 * table afterwards can see that — a mocked indexer would prove only that a method was called.
 *
 * <p>The embedding client is the shared deterministic fake: the same text always produces the same
 * vector, so a vector that changed after an edit changed because the text did.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(ProtocolEditIT.EditTestConfig.class)
class ProtocolEditIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path filesDir;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ProtocolIntakeService intake;
    @Autowired
    ProtocolEditService edits;
    @Autowired
    ProtocolModerationService moderation;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM moderation_event").update();
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        jdbc.sql("DELETE FROM embedding_budget").update();
    }

    // ---------------------------------------------------------------------------------------
    // The re-index, which is the whole point
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("an edit rewrites the document and rebuilds the chunks from the new text")
    void anEditReIndexes() {
        UUID id = uploadAndIndex("E-47 Druckabfall", """
                Symptom:
                Presse kommt nicht auf Druck.

                Massnahme:
                Dichtsatz erneuert, Anzugsmoment 90 Nm.
                """);
        List<String> before = chunkContents(id);
        assertThat(before).isNotEmpty();
        assertThat(String.join("\n", before)).contains("90 Nm");

        edits.edit(id, correction("E-47 Druckabfall", """
                Symptom:
                Presse kommt nicht auf Druck.

                Massnahme:
                Dichtsatz erneuert, Anzugsmoment 120 Nm.
                """), "admin");

        // The status goes back before the worker touches anything: a protocol mid-correction is not
        // an INDEXED one, and saying so is what makes the 202 honest.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo("INDEXED"));

        String after = String.join("\n", chunkContents(id));
        assertThat(after)
                .as("retrieval searches the chunks; an edit that left them would keep matching the "
                        + "wrong torque figure while the document read correctly")
                .contains("120 Nm")
                .doesNotContain("90 Nm");
        // The file on the volume is the same file, rewritten in place — same path, same id, so the
        // citation, the row and the volume keep pointing at one another.
        assertThat(documentText(id)).contains("120 Nm");
    }

    @Test
    @DisplayName("the vectors are rebuilt too, not only the chunk text")
    void theVectorsAreRebuilt() {
        UUID id = uploadAndIndex("Lagerschaden", "Symptom:\nBand quietscht.\n");
        String beforeVector = firstVector(id);

        edits.edit(id, correction("Lagerschaden", "Symptom:\nBand quietscht laut und heiss.\n"), "admin");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo("INDEXED"));

        // The fake is deterministic, so a changed vector means the text was genuinely re-embedded
        // rather than the old vector being carried over with new content beside it.
        assertThat(firstVector(id)).isNotEqualTo(beforeVector);
    }

    @Test
    @DisplayName("an edit records who, when and why")
    void anEditIsRecorded() {
        UUID id = uploadAndIndex("Falsches Drehmoment", "Symptom:\nEtwas.\n");

        edits.edit(id, correction("Richtiges Drehmoment", "Symptom:\nEtwas anderes.\n"), "admin");

        List<String[]> events = jdbc.sql("""
                        SELECT actor, comment FROM moderation_event
                        WHERE protocol_id = :id AND action = 'EDIT'
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new String[]{rs.getString("actor"), rs.getString("comment")})
                .list();

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event[0]).isEqualTo("admin");
            assertThat(event[1]).isEqualTo("Drehmoment korrigiert");
        });
        assertThat(title(id)).isEqualTo("Richtiges Drehmoment");
    }

    // ---------------------------------------------------------------------------------------
    // What an edit may not do
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a correction without a stated reason is refused, and changes nothing")
    void aCorrectionNeedsAComment() {
        UUID id = uploadAndIndex("Unveraendert", "Symptom:\nOriginal.\n");

        for (String comment : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> edits.edit(id, new ProtocolEditService.Correction(
                    null, null, "Neuer Titel", null, "Symptom:\nNeu.\n", comment), "admin"))
                    .isInstanceOf(ProtocolModerationService.InvalidModerationRequestException.class)
                    .hasFieldOrPropertyWithValue("code", "MODERATION_COMMENT_REQUIRED");
        }

        // Refused before anything was written: neither the row nor the file moved.
        assertThat(title(id)).isEqualTo("Unveraendert");
        assertThat(documentText(id)).contains("Original");
    }

    @Test
    @DisplayName("an edit cannot move a protocol to another machine")
    void theMachineIsLocked() {
        UUID id = uploadAndIndex("Bleibt auf PR-03", "Symptom:\nEtwas.\n");

        // Refused rather than ignored: a client told 200 after trying to move a protocol would
        // report success, and the next person to read that screen would believe it had moved.
        assertThatThrownBy(() -> edits.edit(id, new ProtocolEditService.Correction(
                "AB-02", null, "Neuer Titel", null, "Symptom:\nNeu.\n", "verschieben"), "admin"))
                .isInstanceOf(ProtocolModerationService.InvalidModerationRequestException.class)
                .hasFieldOrPropertyWithValue("code", "PROTOCOL_IDENTITY_LOCKED");

        assertThat(title(id)).isEqualTo("Bleibt auf PR-03");
    }

    @Test
    @DisplayName("an edit cannot reclassify a protocol")
    void theTypeIsLocked() {
        UUID id = uploadAndIndex("Bleibt eine Stoerung", "Symptom:\nEtwas.\n");

        assertThatThrownBy(() -> edits.edit(id, new ProtocolEditService.Correction(
                null, "WARTUNG", "Neuer Titel", null, "Symptom:\nNeu.\n", "umbuchen"), "admin"))
                .isInstanceOf(ProtocolModerationService.InvalidModerationRequestException.class)
                .hasFieldOrPropertyWithValue("code", "PROTOCOL_IDENTITY_LOCKED");
    }

    @Test
    @DisplayName("echoing back the unchanged machine and type is accepted")
    void echoingTheIdentityIsFine() {
        UUID id = uploadAndIndex("Titel", "Symptom:\nEtwas.\n");

        // The edit dialog shows machine and type read-only and submits the whole form. Refusing a
        // request that changes nothing would make the lock a trap rather than a rule.
        edits.edit(id, new ProtocolEditService.Correction(
                "PR-03", "STOERUNG", "Neuer Titel", "E-47", "Symptom:\nNeu.\n", "korrigiert"), "admin");

        assertThat(title(id)).isEqualTo("Neuer Titel");
    }

    @Test
    @DisplayName("an archived protocol cannot be edited — archived is final")
    void anArchivedProtocolIsNotEditable() {
        UUID id = uploadAndIndex("Wird entfernt", "Symptom:\nEtwas.\n");
        moderation.delete(id, "admin", "unbrauchbar");

        // 409 territory, not 404: the same administrator can read this in the archive one tab over.
        // And the deeper reason is mechanical — an edit re-indexes, and re-indexing an archived
        // protocol would put it back into search, which is the undo that does not exist.
        assertThatThrownBy(() -> edits.edit(id, correction("Doch nicht", "Symptom:\nNeu.\n"), "admin"))
                .isInstanceOf(ProtocolModerationService.InvalidModerationRequestException.class)
                .hasFieldOrPropertyWithValue("code", "PROTOCOL_ARCHIVED");

        assertThat(chunkContents(id)).as("and no chunks came back").isEmpty();
    }

    @Test
    @DisplayName("an unknown protocol is reported as missing rather than created")
    void anUnknownProtocolIsEmpty() {
        assertThat(edits.edit(UUID.randomUUID(), correction("Neu", "Symptom:\nNeu.\n"), "admin"))
                .isEmpty();
    }

    @Test
    @DisplayName("the corrected text is held to the same size cap as an upload")
    void theSizeCapStillApplies() {
        UUID id = uploadAndIndex("Kurz", "Symptom:\nEtwas.\n");

        // Otherwise the edit endpoint would be a way around #36's upload guard: submit something
        // small, then correct it into something enormous.
        assertThatThrownBy(() -> edits.edit(id, correction("Kurz", "x".repeat(2_000_000)), "admin"))
                .isInstanceOf(ProtocolIntakeService.InvalidProtocolException.class);
    }

    @Test
    @DisplayName("an empty correction is refused; deleting is what removes a protocol")
    void anEmptyCorrectionIsRefused() {
        UUID id = uploadAndIndex("Kurz", "Symptom:\nEtwas.\n");

        assertThatThrownBy(() -> edits.edit(id, correction("Kurz", "   "), "admin"))
                .isInstanceOf(ProtocolIntakeService.InvalidProtocolException.class);
    }

    // ---------------------------------------------------------------------------------------

    private static ProtocolEditService.Correction correction(String title, String content) {
        return new ProtocolEditService.Correction(null, null, title, "E-47", content,
                "Drehmoment korrigiert");
    }

    /**
     * Uploads through the real intake path and waits for the worker, so the fixture is a protocol
     * that got here the way every protocol does.
     *
     * <p>It <b>waits</b> rather than calling {@code indexer.index(id)} itself, and that is not a
     * style preference. Intake publishes its event AFTER_COMMIT, so the worker is already indexing
     * this protocol by the time the fixture returns; a second, explicit run raced the first through
     * the indexer's delete-then-insert and lost often enough to fail four tests at once. The
     * production callers never do that — the event fires once per upload — so the fixture should
     * not either.
     */
    private UUID uploadAndIndex(String title, String content) {
        UUID id = intake.accept(new ProtocolIntakeService.NewProtocol(
                "PR-03", "STOERUNG", "E-47", title, "de", content, "schichtleiter"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo("INDEXED"));
        return id;
    }

    private List<String> chunkContents(UUID protocolId) {
        return jdbc.sql("SELECT content FROM chunk WHERE protocol_id = :id ORDER BY chunk_index")
                .param("id", protocolId).query(String.class).list();
    }

    private String firstVector(UUID protocolId) {
        return jdbc.sql("""
                        SELECT CAST(embedding AS text) FROM chunk WHERE protocol_id = :id
                        ORDER BY chunk_index LIMIT 1
                        """)
                .param("id", protocolId).query(String.class).single();
    }

    private String status(UUID protocolId) {
        return jdbc.sql("SELECT status FROM protocol WHERE id = :id")
                .param("id", protocolId).query(String.class).single();
    }

    private String title(UUID protocolId) {
        return jdbc.sql("SELECT title FROM protocol WHERE id = :id")
                .param("id", protocolId).query(String.class).single();
    }

    private String documentText(UUID protocolId) {
        String relative = jdbc.sql("SELECT source_file FROM protocol WHERE id = :id")
                .param("id", protocolId).query(String.class).single();
        try {
            return Files.readString(filesDir.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class EditTestConfig {
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
