package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService.ProtocolDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolving a stored document to something a browser can be handed.
 *
 * <p>Two jobs, and both decide what a technician sees when they click a citation: whether the file
 * is served at all, and what it is called and typed when it is. The row says where the file should
 * be; everything after that is this class deciding whether to believe it.
 *
 * <p>THE REFUSALS ARE THE POINT. Every "not there" case answers the same way — empty, so the
 * controller says 404 — because telling them apart in the response would describe the database to
 * whoever asked. Each of those cases is tested separately here, where they can be told apart.
 *
 * <p>The JdbcClient is stubbed rather than run against Postgres: what is under test is the file
 * resolution and the naming, not the query. ProtocolDocumentIT covers the query against a real
 * schema, and the two do not overlap.
 *
 * <p>OUT OF SCOPE: who may call which method — that is the role matrix, in ModerationController's
 * javadoc and its own tests — and the archived-versus-live query, which needs the real schema.
 */
class ProtocolDocumentServiceTest {

    @TempDir
    Path volume;

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
    }

    /**
     * Answers the one query the service makes with a single row, built through the real row mapper.
     *
     * <p>Through the mapper on purpose: a stub that returned a ready-made record would skip the
     * column names, and a renamed column is exactly the kind of break this arrangement should not
     * hide from ProtocolDocumentIT.
     */
    private void rowIs(String title, String sourceFile, String machineNo) {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(0);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("title")).thenReturn(title);
            when(rs.getString("source_file")).thenReturn(sourceFile);
            when(rs.getString("language")).thenReturn("de");
            when(rs.getString("machine_no")).thenReturn(machineNo);
            Object mapped = mapper.mapRow(rs, 0);
            JdbcClient.MappedQuerySpec<Object> query = mock(JdbcClient.MappedQuerySpec.class);
            when(query.optional()).thenReturn(Optional.ofNullable(mapped));
            return query;
        });
    }

    private ProtocolDocumentService serviceOn(Path base) {
        return new ProtocolDocumentService(jdbc, new FileStorageProperties(base.toString()));
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = volume.resolve(name);
        Files.createDirectories(file.getParent() == null ? volume : file.getParent());
        Files.writeString(file, content);
        return file;
    }

    // -------------------------------------------------------------------------------------------
    // Whether it is served at all
    // -------------------------------------------------------------------------------------------

    @Test
    void find_readableFileUnderTheVolume_isServed() throws IOException {
        writeFile("e47.txt", "Symptom:\nKein Druck.\n");
        rowIs("E-47 Druckabfall", "e47.txt", "PR-03");

        Optional<ProtocolDocument> document = serviceOn(volume).find(UUID.randomUUID());

        assertThat(document).isPresent();
        assertThat(document.get().sizeBytes()).isEqualTo(Files.size(volume.resolve("e47.txt")));
    }

    @Test
    void find_rowWithNoSourceFile_isEmpty() {
        rowIs("E-47 Druckabfall", null, "PR-03");

        // A protocol row can exist with no file behind it — an import, a half-finished migration.
        // One outcome for the caller, and not an exception: this is a 404, not a fault.
        assertThat(serviceOn(volume).find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void find_rowWithBlankSourceFile_isEmpty() {
        rowIs("E-47 Druckabfall", "   ", "PR-03");

        assertThat(serviceOn(volume).find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void find_pathEscapingTheVolume_isEmpty() throws IOException {
        Path outside = volume.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "not yours");
        Path base = volume.resolve("files");
        Files.createDirectories(base);
        rowIs("E-47", "../outside/secret.txt", "PR-03");

        // THE PATH COMES FROM OUR OWN DATABASE AND IS STILL CHECKED. A column is only as
        // trustworthy as everything that has ever written to it — a future import, a manual fix, a
        // restored dump. The check costs one comparison and removes the whole class of question.
        assertThat(serviceOn(base).find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void find_missingFile_isEmpty() {
        rowIs("E-47", "gone.txt", "PR-03");

        // The row survived the file. A citation from before a restore lands here.
        assertThat(serviceOn(volume).find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void find_directoryWhereAFileShouldBe_isEmpty() throws IOException {
        Files.createDirectories(volume.resolve("adirectory"));
        rowIs("E-47", "adirectory", "PR-03");

        // Readable, inside the volume, and still not a document. Without this check the response
        // would be a stream of nothing with a 200 in front of it.
        assertThat(serviceOn(volume).find(UUID.randomUUID())).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // What it is called and what it says it is
    // -------------------------------------------------------------------------------------------

    @Test
    void find_germanTitle_isNamedInAsciiWithoutMojibake() throws IOException {
        writeFile("stored.txt", "x");
        rowIs("Druckabfall im Presshub — entlüftet", "stored.txt", "PR-03");

        String name = serviceOn(volume).find(UUID.randomUUID()).orElseThrow().downloadName();

        // A Content-Disposition filename crossing an umlaut is a well-known source of mojibake, and
        // the corpus is German. Named from the machine and the title rather than the stored UUID so
        // a technician who saves three sources can tell them apart afterwards.
        assertThat(name).startsWith("PR-03-Druckabfall-im-Presshub-entluftet");
        assertThat(name).endsWith(".txt");
        assertThat(name).matches("[A-Za-z0-9._-]+");
    }

    @Test
    void find_sharpS_isTransliteratedRatherThanDropped() throws IOException {
        writeFile("stored.txt", "x");
        rowIs("Außendichtung", "stored.txt", "PR-03");

        // "Aussendichtung", not "Aendichtung": ß has no accent to strip, so it needs its own rule.
        assertThat(serviceOn(volume).find(UUID.randomUUID()).orElseThrow().downloadName())
                .contains("Aussendichtung");
    }

    @Test
    void find_veryLongTitle_isTruncatedToAUsableName() throws IOException {
        writeFile("stored.txt", "x");
        rowIs("A".repeat(200), "stored.txt", "PR-03");

        String name = serviceOn(volume).find(UUID.randomUUID()).orElseThrow().downloadName();

        // Capped before the extension, so the file still opens by double-click on a filesystem with
        // a name limit.
        assertThat(name).hasSize(80 + ".txt".length());
        assertThat(name).endsWith(".txt");
    }

    @Test
    void find_titleWithNothingAsciiInIt_fallsBackToTheProtocolId() throws IOException {
        writeFile("stored.txt", "x");
        UUID id = UUID.randomUUID();
        rowIs("。。。", "stored.txt", "。。");

        // BOTH have to be unusable for this to fire, and that is worth knowing: a null machine
        // number is replaced by the literal "protokoll", which is never blank, so the fallback is
        // reachable only when a PRESENT machine number is also non-ASCII. Without it the browser
        // would save a file called ".txt".
        assertThat(serviceOn(volume).find(id).orElseThrow().downloadName())
                .contains(id.toString());
    }

    @Test
    void find_storedFileWithNoExtension_isNamedWithoutOne() throws IOException {
        writeFile("noextension", "x");
        rowIs("E-47", "noextension", "PR-03");

        // A dot before the last slash is part of a directory name, not an extension.
        assertThat(serviceOn(volume).find(UUID.randomUUID()).orElseThrow().downloadName())
                .isEqualTo("PR-03-E-47");
    }

    @Test
    void find_textFile_isTypedAsUtf8Text() throws IOException {
        writeFile("e47.txt", "Entlüftet.");
        rowIs("E-47", "e47.txt", "PR-03");

        // The charset is stated rather than left to be sniffed: everything the pipeline accepts is
        // UTF-8 text — a PDF fails the strict decode on upload — so this is the truth, not a guess,
        // and a browser guessing at German umlauts gets them wrong often enough to matter.
        assertThat(serviceOn(volume).find(UUID.randomUUID()).orElseThrow().contentType())
                .isEqualTo("text/plain;charset=UTF-8");
    }

    @Test
    void find_rowWithNoMachineNumber_isNamedForAProtocolRatherThanForNothing() throws IOException {
        writeFile("stored.txt", "x");
        rowIs("E-47 Druckabfall", "stored.txt", null);

        // The join guarantees a machine, so this is the belt to that brace. "protokoll-" reads as
        // a filename; "-E-47" reads as a bug.
        assertThat(serviceOn(volume).find(UUID.randomUUID()).orElseThrow().downloadName())
                .startsWith("protokoll-E-47");
    }

    @Test
    void find_rowWithNoTitle_isNamedForTheProtocolId() throws IOException {
        writeFile("stored.txt", "x");
        UUID id = UUID.randomUUID();
        rowIs(null, "stored.txt", "PR-03");

        // An untitled protocol still has to save as something a technician can find again, and the
        // id is the only thing left that distinguishes it from the next untitled one.
        assertThat(serviceOn(volume).find(id).orElseThrow().downloadName())
                .startsWith("PR-03-" + id);
    }

    @Test
    void find_pdfFile_isTypedAsPdf() throws IOException {
        writeFile("scan.pdf", "%PDF-1.4");
        rowIs("E-47", "scan.pdf", "PR-03");

        // Nothing in the pipeline accepts a PDF today — the strict UTF-8 decode refuses it on
        // upload — so this is the branch that stops a future import from being served as text and
        // rendered as mojibake in a browser tab.
        assertThat(serviceOn(volume).find(UUID.randomUUID()).orElseThrow().contentType())
                .isEqualTo("application/pdf");
    }

    @Test
    void find_markdownFile_isTypedAsMarkdown() throws IOException {
        writeFile("notes.md", "# E-47");
        rowIs("E-47", "notes.md", "PR-03");

        assertThat(serviceOn(volume).find(UUID.randomUUID()).orElseThrow().contentType())
                .isEqualTo("text/markdown;charset=UTF-8");
    }
}
