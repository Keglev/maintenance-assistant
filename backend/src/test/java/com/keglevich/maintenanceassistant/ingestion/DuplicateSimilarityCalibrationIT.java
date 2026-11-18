package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.support.LiveProviderTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Where {@code maintenance.duplicates.similarity-threshold} and {@code
 * maintenance.duplicates.maintenance-similarity-threshold} come from — measured, not chosen.
 *
 * <p>A {@link LiveProviderTest}, exactly like {@link
 * com.keglevich.maintenanceassistant.query.QueryDemoVerificationIT}: no normal build runs it, so it
 * never spends money by accident. It exists for the same reason that one does: the numbers this
 * project quotes are measurements, and a measurement that cannot be re-taken is a claim. Re-run it
 * after any change to the embedding model, to the chunker, or to the corpus.
 *
 * <p><b>It measures two windows, one per protocol type, and prints every number:</b>
 *
 * <ol>
 *   <li>the four E-47 protocols against each other — the proof case, four different root causes
 *       behind one fault code, every one of them legitimate;</li>
 *   <li>the closest legitimate pair that includes a fault report, which the fault threshold has to
 *       clear, and the closest one that includes a maintenance protocol, which the maintenance
 *       threshold has to clear. The second is the corpus-wide ceiling and the reason there are two
 *       thresholds (ADR-006, revision of 2026-11-18);</li>
 *   <li>per type, a genuine near-duplicate: the same event written up a second time by a second
 *       person, in their own words — what a real duplicate looks like in a plant;</li>
 *   <li>per type, a verbatim re-file under a different title — the naive duplicate, the upper
 *       bound.</li>
 * </ol>
 *
 * <p>The synthetic protocols are filed through the real intake and the real indexer, measured, and
 * <b>removed row, chunks and file</b> in a finally block. Nothing it creates survives the run.
 *
 * <p>Reproduce (PowerShell, from backend/), against the local development stack with the corpus
 * already indexed:
 * <pre>
 *   $env:LLM_API_KEY = (Select-String -Path ..\docs\evidence\adr-002\.env -Pattern '^IONOS_API_KEY=').Line.Split('=')[1]
 *   mvn verify -P llm-live -Dit.test=DuplicateSimilarityCalibrationIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("demo")
@LiveProviderTest
class DuplicateSimilarityCalibrationIT {

    /** E-47 Druckabfall im Presshub — the incident both synthetic protocols are about. */
    private static final UUID E47_SEAL_FAILURE = UUID.fromString("0f9c5b02-0000-4000-8000-000000000001");
    private static final String MACHINE = "PR-03";

    /** Jahreswartung Presse 7 — the closest legitimate pair's annual half, and the service re-told. */
    private static final UUID PR07_ANNUAL_SERVICE = UUID.fromString("0f9c5b02-0000-4000-8000-000000000038");
    private static final String MAINTENANCE_MACHINE = "PR-07";

    /**
     * The same incident as {@link #E47_SEAL_FAILURE}, written up independently by a second person.
     *
     * <p>This — not a copy-paste — is what a duplicate looks like in a plant: two people file a
     * protocol about one fault because neither knew the other had. The words differ, the facts do
     * not. A threshold that only catches the verbatim case catches nothing that actually happens.
     */
    private static final String RE_NARRATION = """
            WARTUNGSPROTOKOLL
            =================

            Maschine: PR-03
            Datum: 09.10.2024
            Art: STOERUNG
            Fehlercode: E-47
            Techniker: RS
            Stillstand: 60 Minuten

            Presse baut keinen Druck auf, E-47

            Symptom:
            Presse zieht im Presshub nicht durch, die Steuerung meldet E-47. Am Manometer stehen \
            statt der geforderten 250 bar nur noch rund 180 bar an. Hält man den Druck, fällt er \
            innerhalb von etwa 20 Sekunden sichtbar ab und der Hub läuft nicht sauber aus.

            Ursache:
            Interne Leckage im Hauptzylinder. Die Kolbendichtung war verschlissen, die Dichtlippe \
            hart und eingelaufen. Öl sauber, Pumpe fördert normal — der Druck ging innen am Kolben \
            vorbei.

            Massnahme:
            Zylinder ausgebaut, kompletten Dichtsatz getauscht, Kolbenstange auf Riefen kontrolliert \
            (ohne Befund). Wieder eingebaut, entlüftet, auf 250 bar eingestellt und über fünf Zyklen \
            mit Haltedruck kontrolliert. Haltedruck bleibt stehen.

            Ersatzteile:
            Dichtsatz Hauptzylinder 200/140, Hydrauliköl HLP 46 ca. 15 l
            """;

    /**
     * The same annual service as {@link #PR07_ANNUAL_SERVICE}, written up by a second technician.
     *
     * <p>The maintenance counterpart of {@link #RE_NARRATION}: one service, two write-ups, the work
     * listed in different words and a different order. Two people documenting one planned service
     * is the duplicate a maintenance protocol can actually have.
     */
    private static final String MAINTENANCE_RE_NARRATION = """
            WARTUNGSPROTOKOLL
            =================

            Maschine: PR-07
            Datum: 21.04.2026
            Art: WARTUNG
            Techniker: MK
            Stillstand: 540 Minuten

            Jährliche Wartung Presse 7

            Symptom:
            Planmäßige Wartung nach Herstellervorgabe bei 8000 Betriebsstunden.

            Massnahme:
            Hydrauliköl komplett abgelassen und neu befüllt, sämtliche Filter getauscht. Vorspanndruck \
            der Speicher kontrolliert und Stickstoff nachgefüllt. Jede Sicherheitsfunktion einzeln \
            getestet und ins Prüfprotokoll eingetragen. Lager der Antriebe abgehört, keine \
            Auffälligkeiten. Schaltschrank gereinigt, Filtermatten erneuert. Parameter neu gesichert.

            Ersatzteile:
            HLP 46 ca. 220 l, kompletter Filtersatz, zwei Filtermatten für den Schaltschrank
            """;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ProtocolIntakeService intake;
    @Autowired
    ProtocolSimilarityService similarity;
    @Autowired
    DuplicateProperties properties;
    @Autowired
    FileStorageProperties files;

    // -------------------------------------------------------------------------------------------
    // 1 + 2 — what the corpus already contains, and is entitled to
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the E-47 four are legitimate and must NOT be flagged at the configured threshold")
    void theE47FourAreNotDuplicates() {
        List<Pair> e47 = pairsAmongE47();

        System.out.printf("%n=== the E-47 four, protocol centroids | threshold = %.3f%n",
                properties.similarityThreshold());
        e47.forEach(p -> System.out.printf("  %.4f  %-34s  %-34s%n", p.similarity(), p.a(), p.b()));

        double worst = e47.stream().mapToDouble(Pair::similarity).max().orElseThrow();
        System.out.printf("  E-47 spread: %.4f .. %.4f%n",
                e47.stream().mapToDouble(Pair::similarity).min().orElseThrow(), worst);

        // THE FEATURE'S WHOLE PREMISE, as an assertion. Four protocols, one fault code, four
        // different root causes, all four cited together in the demo answer. If the threshold ever
        // drops under this spread, duplicate detection starts telling an administrator that the
        // best-answered question in the corpus is a pile of copies.
        assertThat(worst)
                .as("the E-47 four are four root causes behind one code, not four copies")
                .isLessThan(properties.similarityThreshold());
    }

    @Test
    @DisplayName("the corpus ceiling is a maintenance pair, and each threshold clears its own ceiling")
    void eachThresholdClearsItsOwnCeiling() {
        List<Pair> fault = topLegitimatePairs(8, "STOERUNG");
        List<Pair> maintenance = topLegitimatePairs(8, "WARTUNG");

        System.out.printf("%n=== closest LEGITIMATE pairs including a fault report, same machine%n");
        fault.forEach(p -> System.out.printf("  %.4f  [%s] %-32s  %-32s%n",
                p.similarity(), p.machineNo(), p.a(), p.b()));
        System.out.printf("%n=== closest LEGITIMATE pairs including a maintenance protocol, same machine%n");
        maintenance.forEach(p -> System.out.printf("  %.4f  [%s] %-32s  %-32s%n",
                p.similarity(), p.machineNo(), p.a(), p.b()));

        double faultCeiling = fault.get(0).similarity();
        double maintenanceCeiling = maintenance.get(0).similarity();
        System.out.printf("  fault ceiling %.4f | threshold %.3f | margin %.4f%n", faultCeiling,
                properties.similarityThreshold(), properties.similarityThreshold() - faultCeiling);
        System.out.printf("  maintenance ceiling %.4f | threshold %.3f | margin %.4f%n",
                maintenanceCeiling, properties.maintenanceSimilarityThreshold(),
                properties.maintenanceSimilarityThreshold() - maintenanceCeiling);

        // THE FINDING behind two thresholds. The E-47 four are the tightest FAULT cluster and not
        // the corpus's ceiling: scheduled-maintenance protocols on one machine share a template, a
        // technician and a vocabulary, and two different services score higher against each other
        // than any two fault reports do. If that stops being true, one threshold would do again.
        assertThat(maintenanceCeiling)
                .as("if maintenance no longer sets the ceiling, ADR-006's two-threshold revision is stale")
                .isGreaterThan(faultCeiling);
        assertThat(faultCeiling)
                .as("the fault threshold must clear every legitimate pair with a fault report in it")
                .isLessThan(properties.similarityThreshold());
        assertThat(maintenanceCeiling)
                .as("the maintenance threshold must clear every legitimate pair with a maintenance protocol")
                .isLessThan(properties.maintenanceSimilarityThreshold());
    }

    // -------------------------------------------------------------------------------------------
    // 3 + 4 — what a real duplicate scores
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a re-narration and a verbatim re-file both clear the threshold, with margin")
    void aGenuineDuplicateIsFlagged() {
        String original = documentOf(E47_SEAL_FAILURE);
        UUID reNarration = null;
        UUID verbatim = null;
        try {
            reNarration = fileAndIndex(MACHINE, "STOERUNG", "E-47",
                    "Presse baut keinen Druck auf, E-47", RE_NARRATION);
            verbatim = fileAndIndex(MACHINE, "STOERUNG", "E-47",
                    "Kein Druck im Presshub, Fehler E-47", original);

            double reNarrated = similarityBetween(reNarration, E47_SEAL_FAILURE);
            double copied = similarityBetween(verbatim, E47_SEAL_FAILURE);

            System.out.printf("%n=== synthetic near-duplicates of '%s'%n", titleOf(E47_SEAL_FAILURE));
            System.out.printf("  %.4f  re-narration by a second person (the realistic duplicate)%n",
                    reNarrated);
            System.out.printf("  %.4f  verbatim re-file under a new title (the upper bound)%n", copied);
            System.out.printf("  threshold %.3f | margin below the re-narration %.4f%n",
                    properties.similarityThreshold(), reNarrated - properties.similarityThreshold());

            assertThat(reNarrated)
                    .as("the same incident written twice is what this feature exists to catch")
                    .isGreaterThanOrEqualTo(properties.similarityThreshold());
            assertThat(copied).isGreaterThanOrEqualTo(reNarrated);

            // And the service says so, end to end, with the original among the candidates.
            ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(reNarration);
            assertThat(report.comparable()).isTrue();
            assertThat(report.candidates())
                    .extracting(ProtocolSimilarityService.SimilarProtocol::id)
                    .contains(E47_SEAL_FAILURE);
        } finally {
            // Row, chunks and file. This runs against a real development database, and a
            // calibration run that left two protocols behind would quietly change the corpus every
            // number above is measured against.
            purge(verbatim);
            purge(reNarration);
        }
    }

    @Test
    @DisplayName("a re-narrated and a verbatim maintenance protocol both clear the maintenance threshold")
    void aGenuineMaintenanceDuplicateIsFlagged() {
        String original = documentOf(PR07_ANNUAL_SERVICE);
        UUID reNarration = null;
        UUID verbatim = null;
        try {
            reNarration = fileAndIndex(MAINTENANCE_MACHINE, "WARTUNG", null,
                    "Jährliche Wartung Presse 7", MAINTENANCE_RE_NARRATION);
            verbatim = fileAndIndex(MAINTENANCE_MACHINE, "WARTUNG", null,
                    "Wartung 8000 h Presse 7", original);

            double reNarrated = similarityBetween(reNarration, PR07_ANNUAL_SERVICE);
            double copied = similarityBetween(verbatim, PR07_ANNUAL_SERVICE);

            // Printed before any assertion: the run that sets the maintenance threshold is the
            // first one, and its numbers are needed even when the provisional value misses.
            System.out.printf("%n=== synthetic near-duplicates of '%s'%n", titleOf(PR07_ANNUAL_SERVICE));
            System.out.printf("  %.4f  re-narration by a second technician (the realistic duplicate)%n",
                    reNarrated);
            System.out.printf("  %.4f  verbatim re-file under a new title (the upper bound)%n", copied);
            System.out.printf("  maintenance threshold %.3f | margin below the re-narration %.4f%n",
                    properties.maintenanceSimilarityThreshold(),
                    reNarrated - properties.maintenanceSimilarityThreshold());

            assertThat(reNarrated)
                    .as("one service written up twice is what the maintenance threshold exists to catch")
                    .isGreaterThanOrEqualTo(properties.maintenanceSimilarityThreshold());
            assertThat(copied).isGreaterThanOrEqualTo(reNarrated);

            ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(reNarration);
            assertThat(report.threshold()).isEqualTo(properties.maintenanceSimilarityThreshold());
            assertThat(report.candidates())
                    .extracting(ProtocolSimilarityService.SimilarProtocol::id)
                    .contains(PR07_ANNUAL_SERVICE);
        } finally {
            purge(verbatim);
            purge(reNarration);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------

    private UUID fileAndIndex(String machineNo, String type, String errorCode, String title,
                              String content) {
        UUID id = intake.accept(new ProtocolIntakeService.NewProtocol(
                machineNo, type, errorCode, title, "de", content, "calibration"));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT status FROM protocol WHERE id = :id")
                        .param("id", id).query(String.class).single()).isEqualTo("INDEXED"));
        return id;
    }

    private double similarityBetween(UUID a, UUID b) {
        return jdbc.sql("""
                        WITH ca AS (SELECT avg(embedding) v FROM chunk WHERE protocol_id = :a),
                             cb AS (SELECT avg(embedding) v FROM chunk WHERE protocol_id = :b)
                        SELECT 1 - (ca.v <=> cb.v) FROM ca CROSS JOIN cb
                        """)
                .param("a", a).param("b", b).query(Double.class).single();
    }

    private List<Pair> pairsAmongE47() {
        return jdbc.sql("""
                        WITH cent AS (
                            SELECT p.id, p.title, avg(c.embedding) v
                            FROM protocol p JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.error_code = 'E-47' AND p.deleted_at IS NULL
                            GROUP BY p.id
                        )
                        SELECT 'PR-03' AS machine_no, a.title AS ta, b.title AS tb,
                               1 - (a.v <=> b.v) AS similarity
                        FROM cent a JOIN cent b ON b.id > a.id
                        ORDER BY similarity DESC
                        """)
                .query((rs, i) -> new Pair(rs.getString("machine_no"), abbreviate(rs.getString("ta")),
                        abbreviate(rs.getString("tb")), rs.getDouble("similarity")))
                .list();
    }

    /**
     * The closest legitimate same-machine pairs in which at least one protocol has this type.
     *
     * <p>"At least one", because a protocol of that type is checked against every other protocol on
     * its machine at its type's threshold, whatever the other one's type is.
     */
    private List<Pair> topLegitimatePairs(int limit, String includingType) {
        return jdbc.sql("""
                        WITH cent AS (
                            SELECT p.id, p.machine_id, m.machine_no, p.title, p.protocol_type,
                                   avg(c.embedding) v
                            FROM protocol p
                            JOIN machine m ON m.id = p.machine_id
                            JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.deleted_at IS NULL AND p.uploaded_by <> 'calibration'
                            GROUP BY p.id, m.machine_no
                        )
                        SELECT a.machine_no, a.title AS ta, b.title AS tb, 1 - (a.v <=> b.v) AS similarity
                        FROM cent a JOIN cent b ON b.machine_id = a.machine_id AND b.id > a.id
                        WHERE a.protocol_type = :type OR b.protocol_type = :type
                        ORDER BY similarity DESC
                        LIMIT :limit
                        """)
                .param("type", includingType)
                .param("limit", limit)
                .query((rs, i) -> new Pair(rs.getString("machine_no"), abbreviate(rs.getString("ta")),
                        abbreviate(rs.getString("tb")), rs.getDouble("similarity")))
                .list();
    }

    /**
     * The protocol's document, off the volume — <b>not</b> {@code protocol.symptom}.
     *
     * <p>Written down because this run got it wrong first and the numbers made no sense: a
     * "verbatim copy" scored 0.83, <em>below</em> the re-narration. For a seeded protocol
     * {@code symptom} holds the Symptom SECTION only (236 bytes of a 944-byte document), while the
     * indexer reads the file. Copying the column produced a copy of one paragraph, which is a
     * different document. What the pipeline embeds is the file, so that is what a calibration has to
     * copy.
     */
    private String documentOf(UUID id) {
        String sourceFile = jdbc.sql("SELECT source_file FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).single();
        try {
            return Files.readString(Path.of(files.basePath()).resolve(sourceFile).normalize());
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + sourceFile
                    + " — this run needs the local document volume, see the class javadoc", e);
        }
    }

    private String titleOf(UUID id) {
        return jdbc.sql("SELECT title FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private void purge(UUID id) {
        if (id == null) {
            return;
        }
        String sourceFile = jdbc.sql("SELECT source_file FROM protocol WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
        jdbc.sql("DELETE FROM chunk WHERE protocol_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM moderation_event WHERE protocol_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM protocol WHERE id = :id").param("id", id).update();
        if (sourceFile != null) {
            try {
                Files.deleteIfExists(Path.of(files.basePath()).resolve(sourceFile).normalize());
            } catch (Exception e) {
                System.out.println("calibration: could not remove " + sourceFile + ": " + e);
            }
        }
    }

    private static String abbreviate(String title) {
        return title.length() <= 32 ? title : title.substring(0, 31) + "…";
    }

    private record Pair(String machineNo, String a, String b, double similarity) {
    }
}
