package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * Where {@code maintenance.duplicates.similarity-threshold} comes from — measured, not chosen.
 *
 * <p>Skipped unless {@code LLM_API_KEY} is set, exactly like {@link
 * com.keglevich.maintenanceassistant.query.QueryDemoVerificationIT}, so CI never runs it and it
 * never spends money by accident. It exists for the same reason that one does: the numbers this
 * project quotes are measurements, and a measurement that cannot be re-taken is a claim. Re-run it
 * after any change to the embedding model, to the chunker, or to the corpus.
 *
 * <p><b>It measures four things and prints all of them:</b>
 *
 * <ol>
 *   <li>the four E-47 protocols against each other — the proof case, four different root causes
 *       behind one fault code, every one of them legitimate;</li>
 *   <li>the highest-scoring legitimate pair anywhere in the corpus, which is <em>not</em> an E-47
 *       pair and is the finding that actually sets the threshold;</li>
 *   <li>a genuine near-duplicate: the same incident written up a second time by a second person, in
 *       their own words — what a real duplicate looks like in a plant;</li>
 *   <li>a verbatim re-file under a different title — the naive duplicate, the upper bound.</li>
 * </ol>
 *
 * <p>Both synthetic protocols are filed through the real intake and the real indexer, measured, and
 * <b>removed row, chunks and file</b> in a finally block. Nothing it creates survives the run.
 *
 * <p>Reproduce (PowerShell, from backend/), against the local development stack with the corpus
 * already indexed:
 * <pre>
 *   $env:LLM_API_KEY = (Select-String -Path ..\spike\adr-002\.env -Pattern '^IONOS_API_KEY=').Line.Split('=')[1]
 *   mvn verify -Dit.test=DuplicateSimilarityCalibrationIT
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+",
        disabledReason = "needs a real provider key; set LLM_API_KEY to run")
class DuplicateSimilarityCalibrationIT {

    /** E-47 Druckabfall im Presshub — the incident both synthetic protocols are about. */
    private static final UUID E47_SEAL_FAILURE = UUID.fromString("0f9c5b02-0000-4000-8000-000000000001");
    private static final String MACHINE = "PR-03";

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
    @DisplayName("the highest legitimate pair in the corpus is NOT an E-47 pair — and it sets the threshold")
    void theCeilingIsScheduledMaintenance() {
        List<Pair> top = topLegitimatePairs(12);

        System.out.printf("%n=== the most similar LEGITIMATE pairs in the corpus, same machine%n");
        top.forEach(p -> System.out.printf("  %.4f  [%s] %-32s  %-32s%n",
                p.similarity(), p.machineNo(), p.a(), p.b()));

        double ceiling = top.get(0).similarity();
        System.out.printf("  legitimate ceiling: %.4f%n", ceiling);

        // THE FINDING. The prompt for this work assumed the E-47 four were the tight cluster to
        // clear. They are the tightest FAULT cluster, and they are not the corpus's ceiling:
        // scheduled-maintenance protocols on one machine share a template, a technician and a
        // vocabulary, and two different services score higher against each other than any two E-47
        // protocols do. A threshold set just above the E-47 spread would flag them every time.
        assertThat(ceiling)
                .as("if this drops below the E-47 spread the finding has changed and the ADR is stale")
                .isGreaterThan(0.83);
        assertThat(ceiling)
                .as("the threshold must clear every legitimate pair, not merely the E-47 ones")
                .isLessThan(properties.similarityThreshold());
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
            reNarration = fileAndIndex("Presse baut keinen Druck auf, E-47", RE_NARRATION);
            verbatim = fileAndIndex("Kein Druck im Presshub, Fehler E-47", original);

            double reNarrated = similarityBetween(reNarration, E47_SEAL_FAILURE);
            double copied = similarityBetween(verbatim, E47_SEAL_FAILURE);

            System.out.printf("%n=== synthetic near-duplicates of '%s'%n", titleOf(E47_SEAL_FAILURE));
            System.out.printf("  %.4f  re-narration by a second person (the realistic duplicate)%n",
                    reNarrated);
            System.out.printf("  %.4f  verbatim re-file under a new title (the upper bound)%n", copied);
            System.out.printf("  threshold %.3f | margin to the legitimate ceiling %.4f%n",
                    properties.similarityThreshold(),
                    properties.similarityThreshold() - topLegitimatePairs(1).get(0).similarity());

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

    // -------------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------------

    private UUID fileAndIndex(String title, String content) {
        UUID id = intake.accept(new ProtocolIntakeService.NewProtocol(
                MACHINE, "STOERUNG", "E-47", title, "de", content, "calibration"));
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

    private List<Pair> topLegitimatePairs(int limit) {
        return jdbc.sql("""
                        WITH cent AS (
                            SELECT p.id, p.machine_id, m.machine_no, p.title, avg(c.embedding) v
                            FROM protocol p
                            JOIN machine m ON m.id = p.machine_id
                            JOIN chunk c ON c.protocol_id = p.id
                            WHERE p.deleted_at IS NULL AND p.uploaded_by <> 'calibration'
                            GROUP BY p.id, m.machine_no
                        )
                        SELECT a.machine_no, a.title AS ta, b.title AS tb, 1 - (a.v <=> b.v) AS similarity
                        FROM cent a JOIN cent b ON b.machine_id = a.machine_id AND b.id > a.id
                        ORDER BY similarity DESC
                        LIMIT :limit
                        """)
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
