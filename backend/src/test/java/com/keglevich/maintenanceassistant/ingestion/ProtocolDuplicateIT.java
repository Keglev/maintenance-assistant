package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duplicate detection against a real pgvector, with the geometry chosen rather than embedded.
 *
 * <h2>Why the vectors are hand-built</h2>
 *
 * <p>Every chunk here gets a vector of the form {@code (cos θ, sin θ, 0, 0, …)} in 1024 dimensions,
 * so the cosine similarity between two protocols is exactly {@code cos(θ₁ − θ₂)} — a number this
 * test states rather than hopes for. {@link FakeEmbeddingClient}'s hash vectors cannot do this job:
 * they are deliberately meaningless, so two protocols that say the same thing score around zero
 * against each other and a duplicate-detection test built on them would pass for the wrong reason.
 *
 * <p><b>This suite and {@code DuplicateSimilarityCalibrationIT} answer different questions, and
 * neither substitutes for the other.</b> That one asks whether the number in the configuration is
 * the right number for {@code bge-m3} and this corpus — it needs the real provider and it costs
 * money. This one asks whether the code around the number behaves: that the machine scope holds,
 * that archived protocols are gone, that the ledger records what happened, and that a pair scoring
 * what the E-47 four actually score is <em>not</em> reported. It runs on every push and needs no key.
 *
 * <p>The angles are taken from the calibration run of 2026-08-14, so if the measurement changes the
 * two suites disagree loudly instead of drifting apart quietly.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
class ProtocolDuplicateIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17").asCompatibleSubstituteFor("postgres"));

    /**
     * The highest measured similarity between any two of the four E-47 protocols (2026-08-14).
     *
     * <p><b>The feature's whole premise lives in this constant.</b> Four protocols, one fault code,
     * four different root causes — a worn piston seal, a sticking pressure-relief valve, a programme
     * change and a slow build-up — every one of them legitimate, and all four correctly cited
     * together in the demo answer. A pair at this distance must not be reported as a duplicate. If
     * the threshold ever drops below it, this test fails, and it should: that would be the system
     * telling an administrator that the best-answered question in the corpus is a pile of copies.
     */
    private static final double E47_WORST_LEGITIMATE_PAIR = 0.8329;

    /**
     * The highest measured similarity between any two legitimate protocols anywhere in the corpus:
     * PR-07's 4000-hour and 8000-hour services. Not an E-47 pair — scheduled maintenance shares a
     * template, a technician and a vocabulary, and runs closer than any two fault reports do. This
     * is the number that actually set the threshold.
     */
    private static final double LEGITIMATE_CEILING = 0.9151;

    /** A re-narration of one incident by a second person — what a real duplicate scores. */
    private static final double REALISTIC_DUPLICATE = 0.9305;

    /** The protocol every other one is positioned against: itself, so an angle of zero. */
    private static final double SUBJECT = 1.0;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired
    ProtocolSimilarityService similarity;
    @Autowired
    ProtocolApprovalService approvals;
    @Autowired
    DuplicateProperties properties;

    private UUID pressA;
    private UUID pressB;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM moderation_event").update();
        jdbc.sql("DELETE FROM chunk").update();
        jdbc.sql("DELETE FROM protocol").update();
        pressA = machine("PR-03");
        pressB = machine("PR-07");
    }

    // -------------------------------------------------------------------------------------------
    // The premise
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("THE PREMISE: two protocols as similar as the E-47 four are NOT reported")
    void theE47FourAreNotReported() {
        UUID subject = protocol(pressA, "E-47 Druckabfall im Presshub", SUBJECT);
        UUID sibling = protocol(pressA, "E-47 sporadisch, Ventil klemmt", E47_WORST_LEGITIMATE_PAIR);

        ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(subject);

        assertThat(properties.similarityThreshold())
                .as("the configured threshold must clear the E-47 spread; it is the reason this "
                        + "feature warns instead of blocking")
                .isGreaterThan(E47_WORST_LEGITIMATE_PAIR);
        assertThat(report.any()).isFalse();
        assertThat(report.candidates()).extracting(ProtocolSimilarityService.SimilarProtocol::id)
                .doesNotContain(sibling);
    }

    @Test
    @DisplayName("the threshold clears the whole corpus's legitimate ceiling, not only the E-47 four")
    void theThresholdClearsScheduledMaintenanceToo() {
        UUID subject = protocol(pressA, "Jahreswartung", SUBJECT);
        protocol(pressA, "Halbjahreswartung", LEGITIMATE_CEILING);

        assertThat(properties.similarityThreshold()).isGreaterThan(LEGITIMATE_CEILING);
        assertThat(similarity.findSimilar(subject).any()).isFalse();
    }

    @Test
    @DisplayName("a genuine near-duplicate IS reported, with its similarity and its own approval state")
    void aNearDuplicateIsReported() {
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        UUID original = protocol(pressA, "E-47 Druckabfall im Presshub", REALISTIC_DUPLICATE);
        approve(original, "admin");

        ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(subject);

        assertThat(report.comparable()).isTrue();
        assertThat(report.total()).isEqualTo(1);
        assertThat(report.threshold()).isEqualTo(properties.similarityThreshold());
        assertThat(report.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.id()).isEqualTo(original);
            assertThat(candidate.title()).isEqualTo("E-47 Druckabfall im Presshub");
            assertThat(candidate.similarity()).isCloseTo(REALISTIC_DUPLICATE, within(0.0005));
            // The candidate's OWN state, which is the whole point of the card: "nearly the same as
            // something an administrator already vouched for" is a merge-or-reject question, and
            // "nearly the same as something nobody reviewed" may be two honest accounts of one
            // fault. The interface cannot tell those apart without this field.
            assertThat(candidate.approval().approved()).isTrue();
            assertThat(candidate.approval().approvedBy()).isEqualTo("admin");
        });
    }

    // -------------------------------------------------------------------------------------------
    // What is excluded
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an ARCHIVED protocol is never offered as a duplicate")
    void archivedProtocolsAreExcluded() {
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        UUID archived = protocol(pressA, "Alter Doppeleintrag", 0.999);
        // Soft-deleted by hand rather than through the service: this test is about the read, and
        // going through delete() would also remove the chunks and prove the exclusion by accident.
        // A protocol whose chunks somehow survived is exactly the case the filter is here for.
        jdbc.sql("UPDATE protocol SET deleted_at = now() WHERE id = :id").param("id", archived).update();

        ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(subject);

        assertThat(report.any())
                .as("the archive holds protocols somebody decided were unfit to be read; offering "
                        + "one as 'the protocol this duplicates' would send an approver to compare "
                        + "against something no answer can cite")
                .isFalse();
    }

    @Test
    @DisplayName("a near-identical protocol on ANOTHER machine is not a duplicate")
    void otherMachinesAreExcluded() {
        UUID subject = protocol(pressA, "Dichtsatz Hauptzylinder erneuert", SUBJECT);
        protocol(pressB, "Dichtsatz Hauptzylinder erneuert", 0.999);

        // A domain rule, not an optimisation: the same job done on two machines is two maintenance
        // events, not one written twice.
        assertThat(similarity.findSimilar(subject).any()).isFalse();
    }

    @Test
    @DisplayName("a protocol with no vectors yet is 'not comparable', which is not 'nothing similar'")
    void anUnindexedProtocolSaysSo() {
        UUID subject = protocolWithoutChunks(pressA, "Gerade erst eingereicht");
        protocol(pressA, "E-47 Druckabfall im Presshub", SUBJECT);

        ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(subject);

        assertThat(report.comparable()).isFalse();
        assertThat(report.any()).isFalse();
    }

    @Test
    @DisplayName("more candidates than fit are ranked and cut, and the count still tells the truth")
    void theTailIsCountedRatherThanDropped() {
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        protocol(pressA, "Kopie 1", 0.99);
        protocol(pressA, "Kopie 2", 0.98);
        protocol(pressA, "Kopie 3", 0.97);
        protocol(pressA, "Kopie 4", 0.96);

        ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(subject);

        assertThat(report.candidates()).hasSize(properties.maxCandidates());
        assertThat(report.candidates()).extracting(ProtocolSimilarityService.SimilarProtocol::title)
                .containsExactly("Kopie 1", "Kopie 2", "Kopie 3");
        assertThat(report.total())
                .as("three links are a prompt to compare; the count is what stops the fourth from "
                        + "disappearing silently")
                .isEqualTo(4);
        assertThat(report.allIds()).hasSize(4);
    }

    // -------------------------------------------------------------------------------------------
    // The ledger
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("approving despite similar protocols records the fact and their ids")
    void theLedgerRecordsAnInformedApproval() {
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        UUID original = protocol(pressA, "E-47 Druckabfall im Presshub", REALISTIC_DUPLICATE);

        approvals.setApproval(subject, true, "admin", null);

        String comment = ledgerComment(subject, "APPROVE");
        assertThat(comment)
                .as("an auditor asking 'did anybody notice these two say the same thing' should "
                        + "find the answer here rather than in somebody's memory of a screen")
                .startsWith(ProtocolApprovalService.APPROVED_DESPITE)
                .contains("1 similar protocol(s)")
                .contains(original.toString());
    }

    @Test
    @DisplayName("the similarity is recomputed by the approval, not taken from the caller")
    void theRecordDoesNotDependOnTheClientHavingAsked() {
        // Nothing in setApproval's signature carries a similarity result, and this is what that
        // buys: an approval made by curl, or by a client that never called /similar, records the
        // same fact as one made through the dialog. "Nothing similar" is the one answer the ledger
        // must never give wrongly.
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        protocol(pressA, "E-47 Druckabfall im Presshub", REALISTIC_DUPLICATE);

        approvals.setApproval(subject, true, "admin", null);

        assertThat(ledgerComment(subject, "APPROVE"))
                .contains(ProtocolApprovalService.APPROVED_DESPITE);
    }

    @Test
    @DisplayName("an ordinary approval keeps its ordinary comment — no noise when nothing is similar")
    void anUnremarkableApprovalIsUnremarkable() {
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        protocol(pressA, "E-47 sporadisch", E47_WORST_LEGITIMATE_PAIR);

        approvals.setApproval(subject, true, "admin", null);

        assertThat(ledgerComment(subject, "APPROVE"))
                .isEqualTo("approved without further comment")
                .doesNotContain(ProtocolApprovalService.APPROVED_DESPITE);
    }

    @Test
    @DisplayName("a stated comment keeps its place, with the observation after it")
    void theHumansWordsComeFirst() {
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        protocol(pressA, "E-47 Druckabfall im Presshub", REALISTIC_DUPLICATE);

        approvals.setApproval(subject, true, "admin", "mit Meister besprochen");

        assertThat(ledgerComment(subject, "APPROVE"))
                .startsWith("mit Meister besprochen — " + ProtocolApprovalService.APPROVED_DESPITE);
    }

    @Test
    @DisplayName("APPROVING IS NEVER REFUSED on a similarity score, however close the match")
    void similarityNeverBlocks() {
        // The governing rule, as a test. A verbatim copy is the strongest signal this feature can
        // produce, and it still approves. If anyone ever adds the "obvious" guard, this fails.
        UUID subject = protocol(pressA, "Presse baut keinen Druck auf", SUBJECT);
        protocol(pressA, "E-47 Druckabfall im Presshub", 0.9999);

        assertThat(approvals.setApproval(subject, true, "admin", null))
                .isPresent()
                .get()
                .satisfies(approval -> assertThat(approval.approved()).isTrue());
        assertThat(jdbc.sql("SELECT approval_state FROM protocol WHERE id = :id")
                .param("id", subject).query(String.class).single()).isEqualTo("APPROVED");
    }

    // -------------------------------------------------------------------------------------------
    // Fixtures — geometry, not embeddings
    // -------------------------------------------------------------------------------------------

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    private UUID machine(String machineNo) {
        return jdbc.sql("SELECT id FROM machine WHERE machine_no = :no")
                .param("no", machineNo).query(UUID.class).single();
    }

    /** A protocol with one chunk at the given cosine similarity to the {@link #SUBJECT}. */
    private UUID protocol(UUID machineId, String title, double cosineToReference) {
        UUID id = protocolWithoutChunks(machineId, title);
        jdbc.sql("""
                        INSERT INTO chunk (id, protocol_id, chunk_index, content, embedding,
                                           language, machine_id)
                        VALUES (:id, :protocolId, 0, :content, CAST(:embedding AS vector), 'de',
                                :machineId)
                        """)
                .param("id", UUID.randomUUID())
                .param("protocolId", id)
                .param("content", title)
                .param("embedding", unitVectorAtAngle(Math.acos(cosineToReference)))
                .param("machineId", machineId)
                .update();
        return id;
    }

    private UUID protocolWithoutChunks(UUID machineId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO protocol (id, machine_id, incident_date, protocol_type, title,
                                              language, status, uploaded_by, source_file)
                        VALUES (:id, :machineId, :incidentDate, 'STOERUNG', :title, 'de', 'INDEXED',
                                'schichtleiter', :sourceFile)
                        """)
                .param("id", id)
                .param("machineId", machineId)
                .param("incidentDate", LocalDate.of(2026, 8, 14))
                .param("title", title)
                .param("sourceFile", "PR-03/" + id + ".txt")
                .update();
        return id;
    }

    /**
     * {@code (cos θ, sin θ, 0, …)} — a unit vector in the first two of 1024 dimensions.
     *
     * <p>Everything else is zero, so the cosine similarity between two of these is exactly the
     * cosine of the angle between them. That is what lets a test say "as similar as the E-47 four
     * are" and mean a number rather than a hope.
     */
    private static String unitVectorAtAngle(double radians) {
        StringBuilder out = new StringBuilder(1024 * 4).append('[')
                .append(Math.cos(radians)).append(',').append(Math.sin(radians));
        out.append(",0".repeat(1022));
        return out.append(']').toString();
    }

    private void approve(UUID protocolId, String actor) {
        jdbc.sql("""
                        UPDATE protocol SET approval_state = 'APPROVED', approved_by = :actor,
                                            approved_at = :now
                        WHERE id = :id
                        """)
                .param("id", protocolId).param("actor", actor).param("now", OffsetDateTime.now())
                .update();
    }

    private String ledgerComment(UUID protocolId, String action) {
        List<String> comments = jdbc.sql("""
                        SELECT comment FROM moderation_event
                        WHERE protocol_id = :id AND action = :action
                        ORDER BY created_at, id
                        """)
                .param("id", protocolId).param("action", action).query(String.class).list();
        assertThat(comments).as("exactly one %s row is expected", action).hasSize(1);
        return comments.get(0);
    }
}
