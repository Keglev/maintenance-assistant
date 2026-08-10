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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    // Filters
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the machine filter answers with that machine only, and counts only it")
    void filteringByMachine() throws IOException {
        seedProtocol("PR-03", "Presse steht", day(10));
        seedProtocol("PR-03", "Presse leckt", day(11));
        seedProtocol("AB-02", "Dosierer ungenau", day(12));

        ProtocolModerationService.ProtocolPage page =
                moderation.list(0, 10, filter("PR-03", null, null, null));

        assertThat(page.items()).extracting(ProtocolModerationService.ModeratedProtocol::machineNo)
                .containsOnly("PR-03");
        // The total is the FILTERED total: a pager counting the corpus while showing two rows would
        // offer pages of nothing.
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("the title filter matches a substring, ignoring case")
    void filteringByTitle() throws IOException {
        seedProtocol("PR-03", "Sensorfehler am Band", day(10));
        seedProtocol("PR-03", "Druckabfall", day(11));

        // "sensor" finds "Sensorfehler": people type what they remember, not what was written.
        assertThat(titlesOf(moderation.list(0, 10, filter("PR-03", "sensor", null, null))))
                .containsExactly("Sensorfehler am Band");
    }

    @Test
    @DisplayName("a per-cent sign in the title filter is a per-cent sign, not 'match anything'")
    void wildcardsInTheTitleFilterAreEscaped() throws IOException {
        seedProtocol("PR-03", "Ausschuss 5% nach Umbau", day(10));
        seedProtocol("PR-03", "Druckabfall", day(11));

        // Unescaped, "%" is LIKE's "anything" and the filter would silently stop filtering — it
        // would answer with both rows and look like a working search.
        assertThat(titlesOf(moderation.list(0, 10, filter("PR-03", "%", null, null))))
                .containsExactly("Ausschuss 5% nach Umbau");
    }

    @Test
    @DisplayName("an underscore in the title filter is an underscore, not 'any character'")
    void underscoresInTheTitleFilterAreEscaped() throws IOException {
        seedProtocol("PR-03", "Fehler E_47 quittiert", day(10));
        seedProtocol("PR-03", "Fehler E-47 quittiert", day(11));

        // "E_47" must not also find "E-47": a single-character wildcard is the difference between
        // one fault code and its neighbour.
        assertThat(titlesOf(moderation.list(0, 10, filter("PR-03", "E_47", null, null))))
                .containsExactly("Fehler E_47 quittiert");
    }

    @Test
    @DisplayName("the date range includes both ends, whatever time of day the protocol was filed")
    void filteringByDateRange() throws IOException {
        seedProtocol("PR-03", "Zu frueh", day(9));
        seedProtocol("PR-03", "Erster Tag", day(10).plusHours(6));
        // Late on the last day: an inclusive `to` compared as `<= midnight` would drop this one,
        // which is how a range filter loses the day the user actually meant.
        seedProtocol("PR-03", "Letzter Tag", day(12).plusHours(23));
        seedProtocol("PR-03", "Zu spaet", day(13));

        assertThat(titlesOf(moderation.list(0, 10, filter("PR-03", null, date(10), date(12)))))
                .containsExactlyInAnyOrder("Erster Tag", "Letzter Tag");
    }

    @Test
    @DisplayName("an open-ended range filters from one side only")
    void anOpenEndedRangeIsAllowed() throws IOException {
        seedProtocol("PR-03", "Alt", day(9));
        seedProtocol("PR-03", "Neu", day(12));

        assertThat(titlesOf(moderation.list(0, 10, filter("PR-03", null, date(10), null))))
                .containsExactly("Neu");
        assertThat(titlesOf(moderation.list(0, 10, filter("PR-03", null, null, date(10)))))
                .containsExactly("Alt");
    }

    @Test
    @DisplayName("machine, title and dates combine, and paging still works inside the result")
    void filtersCombineAndStillPage() throws IOException {
        seedProtocol("PR-03", "Sensorfehler am Band", day(10));
        seedProtocol("PR-03", "Sensorfehler am Auswurf", day(11));
        seedProtocol("PR-03", "Sensorfehler zu alt", day(1));
        seedProtocol("PR-03", "Druckabfall", day(11));
        seedProtocol("AB-02", "Sensorfehler Dosierer", day(11));

        ProtocolModerationService.ProtocolFilter combined =
                filter("PR-03", "sensorfehler", date(10), date(12));

        ProtocolModerationService.ProtocolPage first = moderation.list(0, 1, combined);
        assertThat(first.total()).isEqualTo(2);
        // Newest first, unchanged by filtering: a filter narrows the set, it does not reorder it.
        assertThat(titlesOf(first)).containsExactly("Sensorfehler am Auswurf");

        assertThat(titlesOf(moderation.list(1, 1, combined))).containsExactly("Sensorfehler am Band");
    }

    @Test
    @DisplayName("no filter at all is the corpus, exactly as before")
    void anEmptyFilterChangesNothing() throws IOException {
        seedProtocol("PR-03", "Eins", day(10));
        seedProtocol("AB-02", "Zwei", day(11));

        assertThat(moderation.list(0, 10, ProtocolModerationService.ProtocolFilter.none()).total())
                .isEqualTo(moderation.list(0, 10).total())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a title or date filter without a machine is refused, not silently widened")
    void theMachineComesFirst() {
        // 150 protocols across ten machines: a title fragment on its own answers with rows from
        // machines the reviewer was not looking at, which is noise dressed as a result.
        assertThatThrownBy(() -> filter(null, "sensor", null, null))
                .isInstanceOf(ProtocolModerationService.InvalidFilterException.class)
                .hasFieldOrPropertyWithValue("code", "MACHINE_REQUIRED_FOR_FILTER");
        assertThatThrownBy(() -> filter(" ", null, date(10), null))
                .isInstanceOf(ProtocolModerationService.InvalidFilterException.class);
        assertThatThrownBy(() -> filter(null, null, null, date(10)))
                .isInstanceOf(ProtocolModerationService.InvalidFilterException.class);
    }

    @Test
    @DisplayName("a filter that matches nothing is an empty page, not an error")
    void aFilterThatMatchesNothingIsEmpty() throws IOException {
        seedProtocol("PR-03", "Druckabfall", day(10));

        ProtocolModerationService.ProtocolPage page =
                moderation.list(0, 10, filter("PR-03", "getriebe", null, null));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    // ---------------------------------------------------------------------------------------

    private static ProtocolModerationService.ProtocolFilter filter(
            String machineNo, String titleContains, LocalDate from, LocalDate to) {
        return new ProtocolModerationService.ProtocolFilter(machineNo, titleContains, from, to);
    }

    private static List<String> titlesOf(ProtocolModerationService.ProtocolPage page) {
        return page.items().stream().map(ProtocolModerationService.ModeratedProtocol::title).toList();
    }

    /**
     * A fixed day in UTC, so the assertions do not depend on which side of midnight the suite runs.
     * The service draws its day boundaries in UTC because that is the zone the API's timestamps are
     * in; seeding in the same zone is what makes an inclusive range testable at all.
     */
    private static OffsetDateTime day(int dayOfMonth) {
        return date(dayOfMonth).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static LocalDate date(int dayOfMonth) {
        return LocalDate.of(2026, 8, dayOfMonth);
    }

    private long countChunks(UUID protocolId) {
        return jdbc.sql("SELECT count(*) FROM chunk WHERE protocol_id = :id")
                .param("id", protocolId).query(Long.class).single();
    }

    private long countProtocols(UUID protocolId) {
        return jdbc.sql("SELECT count(*) FROM protocol WHERE id = :id")
                .param("id", protocolId).query(Long.class).single();
    }

    /**
     * A protocol on a named machine, filed at a named instant.
     *
     * <p>{@code created_at} is set rather than defaulted to {@code now()}: an inclusive date range
     * is only testable against timestamps the test chose, and a suite that seeds "today" would
     * assert something different depending on which side of midnight it ran.
     */
    private UUID seedProtocol(String machineNo, String title, OffsetDateTime filedAt)
            throws IOException {
        UUID id = seedProtocol(title, "Symptom:\nEtwas.\n",
                jdbc.sql("SELECT id FROM machine WHERE machine_no = :no")
                        .param("no", machineNo).query(UUID.class).single(),
                machineNo);
        jdbc.sql("UPDATE protocol SET created_at = :filedAt WHERE id = :id")
                .param("filedAt", filedAt)
                .param("id", id)
                .update();
        return id;
    }

    private UUID seedProtocol(String title, String documentText) throws IOException {
        return seedProtocol(title, documentText, machineId, "PR-03");
    }

    private UUID seedProtocol(String title, String documentText, UUID machine, String machineNo)
            throws IOException {
        UUID id = UUID.randomUUID();
        String relative = "%s/%s.txt".formatted(machineNo, id);
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
                .param("machineId", machine)
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
