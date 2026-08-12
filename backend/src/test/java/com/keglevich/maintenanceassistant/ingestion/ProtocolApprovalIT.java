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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The trust chain, against a real database: approve, withdraw, four eyes, and the reset on edit.
 *
 * <p><b>Why a container rather than a unit test.</b> Every claim here is about a row and about the
 * ledger beside it — that an approval carries an actor, that an edit clears one, that the audit
 * trail gains the right pair of events. A mocked JdbcClient would prove that a method was called
 * with a string; it would not catch a check constraint refusing the row, and the constraint is half
 * the design.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
@Import(ProtocolApprovalIT.ApprovalTestConfig.class)
class ProtocolApprovalIT {

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

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM moderation_event").update();
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        jdbc.sql("DELETE FROM embedding_budget").update();
    }

    // ---------------------------------------------------------------------------------------
    // A protocol arrives unreviewed, and says so
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a newly filed protocol is UNAPPROVED and carries no approver")
    void aNewProtocolIsUnapproved() {
        UUID id = upload("Presse steht");

        assertThat(approvalState(id)).isEqualTo("UNAPPROVED");
        assertThat(approvedBy(id)).isNull();
    }

    @Test
    @DisplayName("an unapproved protocol is still indexed and still retrievable — searchable is the decision")
    void anUnapprovedProtocolIsStillSearchable() {
        // The decision most likely to be "fixed" by accident later: unapproved protocols stay in
        // search, because the administrator may not review at a weekend and the factory does not
        // stop. If someone ever makes approval a gate, this test is what says they changed a
        // decision rather than fixed an oversight.
        UUID id = uploadAndIndex("Presse steht", "Symptom:\nPresse steht ohne Meldung.\n");

        assertThat(approvalState(id)).isEqualTo("UNAPPROVED");
        assertThat(chunkCount(id))
                .as("chunks are what retrieval matches; an unapproved protocol having none would "
                        + "mean approval had quietly become a gate")
                .isPositive();
    }

    // ---------------------------------------------------------------------------------------
    // Approving
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("approving records the actor and the time")
    void approvingRecordsWhoAndWhen() {
        UUID id = upload("Presse steht");

        Optional<ProtocolApprovalService.Approval> result =
                approvals.setApproval(id, true, "admin", null);

        assertThat(result).isPresent();
        assertThat(result.get().approved()).isTrue();
        assertThat(result.get().approvedBy()).isEqualTo("admin");
        assertThat(result.get().approvedAt()).isNotNull();
        assertThat(approvalState(id)).isEqualTo("APPROVED");
        assertThat(approvedBy(id)).isEqualTo("admin");
        // An approval without an actor is not an audit record — the point of the whole feature.
        assertThat(events(id)).containsExactly("APPROVE");
    }

    @Test
    @DisplayName("approving twice is idempotent and does not write a second audit row")
    void approvingTwiceIsIdempotent() {
        // A state assertion, not a transition: a double-clicked button is not an error, and a
        // ledger that grew a row for a no-op would stop being a record of changes.
        UUID id = upload("Presse steht");
        approvals.setApproval(id, true, "admin", null);

        Optional<ProtocolApprovalService.Approval> second =
                approvals.setApproval(id, true, "admin", null);

        assertThat(second).isPresent();
        assertThat(second.get().approved()).isTrue();
        assertThat(events(id)).containsExactly("APPROVE");
    }

    @Test
    @DisplayName("withdrawing approval clears the approver and demands a reason")
    void withdrawingClearsTheApproverAndNeedsAReason() {
        UUID id = upload("Presse steht");
        approvals.setApproval(id, true, "admin", null);

        assertThatThrownBy(() -> approvals.setApproval(id, false, "admin", "  "))
                .as("taking back something a named person vouched for is the direction that needs "
                        + "explaining")
                .isInstanceOf(ProtocolModerationService.InvalidModerationRequestException.class);

        approvals.setApproval(id, false, "admin", "Massnahme stimmt nicht");

        assertThat(approvalState(id)).isEqualTo("UNAPPROVED");
        assertThat(approvedBy(id))
                .as("an UNAPPROVED row carrying a stale approver would describe a state it is not in")
                .isNull();
        assertThat(events(id)).containsExactly("APPROVE", "UNAPPROVE");
    }

    @Test
    @DisplayName("approving something that does not exist is empty, not an error")
    void approvingAnUnknownProtocolIsEmpty() {
        assertThat(approvals.setApproval(UUID.randomUUID(), true, "admin", null)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Four eyes
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the author may not approve their own protocol")
    void theAuthorMayNotApproveTheirOwn() {
        UUID id = upload("Presse steht");

        assertThatThrownBy(() -> approvals.setApproval(id, true, "schichtleiter", null))
                .isInstanceOf(ProtocolModerationService.InvalidModerationRequestException.class)
                .satisfies(e -> assertThat(
                        ((ProtocolModerationService.InvalidModerationRequestException) e).code())
                        .isEqualTo(ProtocolApprovalService.FOUR_EYES_REQUIRED));

        assertThat(approvalState(id)).isEqualTo("UNAPPROVED");
    }

    @Test
    @DisplayName("the corrector may not approve what they corrected — even as an admin")
    void theCorrectorMayNotApprove() {
        // The half of four-eyes that roles cannot express. An administrator may edit and may
        // approve; what they may not do is both, to the same protocol. Checked against the ledger
        // rather than against the role, because the rule is about the same human.
        UUID id = upload("Presse steht");
        edits.edit(id, correction("Presse steht", "Symptom:\nPresse steht.\nUrsache: Sicherung.\n"),
                "admin");

        assertThatThrownBy(() -> approvals.setApproval(id, true, "admin", null))
                .isInstanceOf(ProtocolModerationService.InvalidModerationRequestException.class)
                .satisfies(e -> assertThat(
                        ((ProtocolModerationService.InvalidModerationRequestException) e).code())
                        .isEqualTo(ProtocolApprovalService.FOUR_EYES_REQUIRED));

        // A different administrator can. That is the whole point: the corpus is not blocked, it
        // just needs a second person.
        assertThat(approvals.setApproval(id, true, "admin2", null)).isPresent();
        assertThat(approvalState(id)).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------------------------------
    // THE RULE THE CHAIN RESTS ON
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("editing an APPROVED protocol resets it to unapproved")
    void editingResetsApproval() {
        // The corrected text is text nobody has reviewed. Leaving the flag set would mean the
        // approval vouches for words the approver never read, and ADR-006's whole argument is that
        // a citation must never point at silently-changed content.
        UUID id = uploadAndIndex("Presse steht", "Symptom:\nPresse steht.\nMassnahme: Sicherung.\n");
        approvals.setApproval(id, true, "admin", null);
        assertThat(approvalState(id)).isEqualTo("APPROVED");

        edits.edit(id, correction("Presse steht", """
                Symptom:
                Presse steht.
                Massnahme: Not-Halt entriegelt.
                """), "schichtleiter");

        assertThat(approvalState(id))
                .as("an edit after approval is new text nobody has reviewed")
                .isEqualTo("UNAPPROVED");
        assertThat(approvedBy(id)).isNull();
    }

    @Test
    @DisplayName("the reset is on the record: an edit of an approved protocol writes EDIT and UNAPPROVE")
    void theResetIsAudited() {
        UUID id = uploadAndIndex("Presse steht", "Symptom:\nPresse steht.\n");
        approvals.setApproval(id, true, "admin", null);

        edits.edit(id, correction("Presse steht", "Symptom:\nPresse steht kalt.\n"), "schichtleiter");

        // IN ANY ORDER, and the reason is a fact about the ledger worth knowing rather than a
        // weakened assertion. `moderation_event.created_at` defaults to `now()`, which in Postgres
        // is the TRANSACTION timestamp — so the EDIT and the UNAPPROVE it triggers carry the same
        // instant to the microsecond, because they are one atomic act. There is no earlier and
        // later between them to assert; presenting them as simultaneous is accurate. (If a reader
        // ever needs a strict sequence, that is a monotonic column on the table, not a nudged
        // timestamp here.)
        assertThat(events(id))
                .as("a reader of the ledger should not have to know the reset rule to see that the "
                        + "approval ended here")
                .containsExactlyInAnyOrder("APPROVE", "EDIT", "UNAPPROVE");
    }

    @Test
    @DisplayName("editing an UNAPPROVED protocol writes no spurious UNAPPROVE")
    void editingAnUnapprovedProtocolAddsNoNoise() {
        UUID id = uploadAndIndex("Presse steht", "Symptom:\nPresse steht.\n");

        edits.edit(id, correction("Presse steht", "Symptom:\nPresse steht warm.\n"), "schichtleiter");

        assertThat(events(id)).containsExactly("EDIT");
    }

    @Test
    @DisplayName("a protocol stays searchable through the whole cycle — approve, edit, reset")
    void theProtocolStaysSearchableThroughout() {
        UUID id = uploadAndIndex("Presse steht", "Symptom:\nPresse steht.\n");
        approvals.setApproval(id, true, "admin", null);
        assertThat(chunkCount(id)).isPositive();

        edits.edit(id, correction("Presse steht", "Symptom:\nPresse steht ganz.\n"), "schichtleiter");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo("INDEXED"));

        assertThat(approvalState(id)).isEqualTo("UNAPPROVED");
        assertThat(chunkCount(id))
                .as("losing approval must not remove a protocol from search — that is the trade-off "
                        + "the decision of 2026-08-11 accepted explicitly")
                .isPositive();
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
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo("INDEXED"));
        return id;
    }

    private ProtocolEditService.Correction correction(String title, String content) {
        return new ProtocolEditService.Correction(null, null, title, null, content, "korrigiert");
    }

    private String approvalState(UUID id) {
        return jdbc.sql("SELECT approval_state FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private String approvedBy(UUID id) {
        return jdbc.sql("SELECT approved_by FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private int chunkCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM chunk WHERE protocol_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private List<String> events(UUID id) {
        return jdbc.sql("""
                        SELECT action FROM moderation_event WHERE protocol_id = :id
                        ORDER BY created_at, id
                        """)
                .param("id", id).query(String.class).list();
    }

    @TestConfiguration
    static class ApprovalTestConfig {
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
