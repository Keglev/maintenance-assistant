package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The moderation ledger as the protocol viewer reads it: what happened, who did it, when, and why.
 *
 * <h2>What this suite settles first, because a prompt for this work assumed otherwise</h2>
 *
 * <p><b>The ledger has been able to spell four verbs since #53, not two.</b> V4 created
 * {@code ck_moderation_event_action} as {@code ('EDIT','DELETE')} and V5 replaced it with
 * {@code ('EDIT','DELETE','APPROVE','UNAPPROVE')} in the same migration that added the approval
 * columns. Approvals and withdrawals have been writing proper rows — actor, timestamp, non-blank
 * comment — ever since. No migration was needed for this pull request and none was added; the tests
 * below assert the vocabulary in force so that claim is checked rather than remembered.
 *
 * <p>The stored verb is {@code UNAPPROVE}, not {@code WITHDRAW}. The interface says "Freigabe
 * zurückgezogen"; renaming the stored value would be a data migration and a sweep of three services
 * to change a word no user ever reads.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(ProtocolHistoryIT.HistoryTestConfig.class)
class ProtocolHistoryIT {

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
    ProtocolApprovalService approvals;
    @Autowired
    ProtocolModerationService moderation;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM moderation_event").update();
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
    }

    // ---------------------------------------------------------------------------------------
    // The schema, on a database Flyway built from scratch
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "the ledger accepts {0}")
    @ValueSource(strings = {"EDIT", "DELETE", "APPROVE", "UNAPPROVE"})
    @DisplayName("the four verbs the ledger can spell — V5 widened V4's two, and this is the check")
    void theLedgerVocabulary(String action) {
        UUID id = upload("Presse steht");

        moderation.recordEvent(id, action, "admin", "e2e vocabulary check");

        assertThat(jdbc.sql("SELECT count(*) FROM moderation_event WHERE protocol_id = :id AND action = :a")
                .param("id", id).param("a", action).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an invented verb is refused by the database, not merely by convention")
    void anUnknownVerbIsRefused() {
        // WITHDRAW among them: it is the word an outside reader would reach for, and the ledger does
        // not know it. If the vocabulary is ever extended, this failing is how a reader finds out
        // that the constraint is the place to do it.
        UUID id = upload("Presse steht");

        assertThatThrownBy(() -> moderation.recordEvent(id, "WITHDRAW", "admin", "nope"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("every row carries a reason — the constraint, not a habit")
    void everyRowCarriesAReason() {
        UUID id = upload("Presse steht");

        assertThatThrownBy(() -> moderation.recordEvent(id, "EDIT", "schichtleiter", "   "))
                .as("the viewer renders a comment on every entry and never has to handle a blank one")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Approving and withdrawing write rows
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("approving writes a ledger row with actor, time and a reason")
    void approvingWritesARow() {
        UUID id = upload("Presse steht");

        approvals.setApproval(id, true, "admin", null);

        ProtocolModerationService.ProtocolHistory history = moderation.history(id);
        assertThat(history.events()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("APPROVE");
            assertThat(event.actor()).isEqualTo("admin");
            assertThat(event.at()).isNotNull();
            // Not blank even when the approver said nothing: the constraint forbids it, and the
            // viewer renders a reason on every line.
            assertThat(event.comment()).isNotBlank();
        });
    }

    @Test
    @DisplayName("withdrawing writes a row carrying the reason the endpoint demanded")
    void withdrawingWritesItsReason() {
        UUID id = upload("Presse steht");
        approvals.setApproval(id, true, "admin", null);

        approvals.setApproval(id, false, "admin", "Massnahme passt nicht zur Ursache");

        assertThat(moderation.history(id).events())
                .first()
                .satisfies(event -> {
                    // NEWEST FIRST. A history that opened with the oldest act would make a reader
                    // scroll to find the one that matters — the most recent.
                    assertThat(event.action()).isEqualTo("UNAPPROVE");
                    assertThat(event.comment()).isEqualTo("Massnahme passt nicht zur Ursache");
                });
    }

    @Test
    @DisplayName("a correction of an approved protocol leaves both rows, and the reset explains itself")
    void aCorrectionLeavesBothRows() {
        UUID id = uploadAndIndex("Presse steht", "Symptom:\nPresse steht.\n");
        approvals.setApproval(id, true, "admin", null);

        edits.edit(id, new ProtocolEditService.Correction(
                null, null, "Presse steht", null, "Symptom:\nPresse steht kalt.\n",
                "Ursache war ein Defekt"), "schichtleiter");

        ProtocolModerationService.ProtocolHistory history = moderation.history(id);
        assertThat(history.total()).isEqualTo(3);
        assertThat(history.events())
                .extracting(ProtocolModerationService.ModerationEvent::action)
                .containsExactlyInAnyOrder("EDIT", "UNAPPROVE", "APPROVE");
        // A reader of the viewer should not have to know the reset rule to see that the approval
        // ended here and why.
        assertThat(history.events())
                .filteredOn(event -> "UNAPPROVE".equals(event.action()))
                .singleElement()
                .satisfies(event -> assertThat(event.comment()).contains("corrected"));
    }

    // ---------------------------------------------------------------------------------------
    // What the viewer needs
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the history is capped SERVER-side, and reports how many it did not send")
    void theHistoryIsCappedAndSaysSo() {
        UUID id = upload("Presse steht");
        for (int i = 1; i <= 6; i++) {
            moderation.recordEvent(id, "EDIT", "schichtleiter", "Korrektur " + i);
        }

        ProtocolModerationService.ProtocolHistory history = moderation.history(id);

        assertThat(history.events()).hasSize(ProtocolModerationService.HISTORY_LIMIT);
        assertThat(history.limit()).isEqualTo(ProtocolModerationService.HISTORY_LIMIT);
        // THE COUNT IS THE POINT. A protocol edited weekly for a year has fifty rows; sending all
        // of them so a dialog can drop forty-seven is a payload that grows with the corpus's age,
        // and sending three without saying so is a truncation nobody is told about.
        assertThat(history.total()).isEqualTo(6);
    }

    @Test
    @DisplayName("newest first, and two acts of one transaction still have a stable order")
    void newestFirstAndStable() {
        UUID id = upload("Presse steht");
        moderation.recordEvent(id, "EDIT", "schichtleiter", "erste Korrektur");
        moderation.recordEvent(id, "EDIT", "schichtleiter", "zweite Korrektur");
        moderation.recordEvent(id, "APPROVE", "admin", "geprüft");

        assertThat(moderation.history(id).events())
                .extracting(ProtocolModerationService.ModerationEvent::comment)
                .startsWith("geprüft");

        // Two reads agree. created_at defaults to the TRANSACTION timestamp, so an edit and the
        // UNAPPROVE it triggers share an instant to the microsecond; the id tie-break is what stops
        // their order swapping between two openings of one dialog.
        assertThat(moderation.history(id).events())
                .isEqualTo(moderation.history(id).events());
    }

    @Test
    @DisplayName("an ARCHIVED protocol keeps its history — that is what the archive is for")
    void archivedProtocolsKeepTheirHistory() {
        UUID id = uploadAndIndex("Presse steht", "Symptom:\nPresse steht.\n");
        approvals.setApproval(id, true, "admin", null);

        moderation.delete(id, "admin", "Massnahme war falsch");

        ProtocolModerationService.ProtocolHistory history = moderation.history(id);
        assertThat(history.events())
                .as("removing garbage must not destroy the record of who produced it (ADR-006)")
                .extracting(ProtocolModerationService.ModerationEvent::action)
                .containsExactly("DELETE", "APPROVE");
    }

    @Test
    @DisplayName("an approval set by a MIGRATION has no history — the seeded case, answered honestly")
    void aMigrationApprovalHasNoHistory() {
        // THE SHAPE THE 150 SEEDED PROTOCOLS ARE IN. V5 approved them with an UPDATE and wrote no
        // event, deliberately: no human act produced that approval, and inventing a ledger row for
        // it would fabricate exactly the unearned trust the flag exists to make visible.
        //
        // So the viewer must not treat "no rows" as "nothing happened". It falls back to the
        // approval columns and names the actor — system:corpus-seed, which says outright that this
        // protocol was born approved.
        UUID id = upload("Presse steht");
        jdbc.sql("""
                        UPDATE protocol
                        SET approval_state = 'APPROVED', approved_by = 'system:corpus-seed',
                            approved_at = :now
                        WHERE id = :id
                        """)
                .param("id", id).param("now", OffsetDateTime.now()).update();

        ProtocolModerationService.ProtocolHistory history = moderation.history(id);

        assertThat(history.events()).isEmpty();
        assertThat(history.total()).isZero();
        assertThat(approvals.find(id))
                .get()
                .satisfies(approval -> assertThat(approval.approvedBy()).isEqualTo("system:corpus-seed"));
    }

    @Test
    @DisplayName("an unknown protocol answers an empty history, not an error")
    void anUnknownProtocolIsEmpty() {
        // The ledger has no foreign key and outlives its subject by design, so "no rows for this id"
        // is a true answer here rather than a missing resource.
        ProtocolModerationService.ProtocolHistory history = moderation.history(UUID.randomUUID());

        assertThat(history.events()).isEmpty();
        assertThat(history.total()).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private UUID upload(String title) {
        return intake.accept(new ProtocolIntakeService.NewProtocol(
                "PR-03", "STOERUNG", null, title, "de", "Symptom:\n" + title + ".\n",
                "schichtleiter"));
    }

    private UUID uploadAndIndex(String title, String content) {
        UUID id = intake.accept(new ProtocolIntakeService.NewProtocol(
                "PR-03", "STOERUNG", null, title, "de", content, "schichtleiter"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT status FROM protocol WHERE id = :id")
                        .param("id", id).query(String.class).single()).isEqualTo("INDEXED"));
        return id;
    }

    @TestConfiguration
    static class HistoryTestConfig {
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
