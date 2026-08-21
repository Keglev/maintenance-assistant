package com.keglevich.maintenanceassistant.ingestion.seed;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendered document, in both languages and with every optional field present and absent.
 *
 * <p>This text is not decoration: it is what {@code protocol.source_file} points at, what the
 * chunker cuts on and what a technician reads when they open a citation. Its labelled sections are
 * the boundaries the chunking strategy depends on — a blank line between sections is what makes a
 * chunk a section rather than a fixed window.
 *
 * <p><b>Every optional field is tested present AND absent</b>, because the rule is "skipped entirely
 * rather than printed as empty", and an empty label is exactly what would put a stray heading into a
 * chunk with nothing under it.
 *
 * <p>OUT OF SCOPE: what the chunker then does with this text (ProtocolChunkerTest), and where the
 * file is written (the seed runner, PR-5's).
 */
class ProtocolDocumentRendererTest {

    /** A complete German protocol; each test overrides only the field it is about. */
    private static CorpusProtocol protocol(String language, String errorCode, String partsUsed,
                                           Integer downtimeMinutes, String technicianInitials) {
        return new CorpusProtocol(
                UUID.randomUUID(), UUID.randomUUID(), "PR-03",
                LocalDate.of(2026, 8, 7), "STOERUNG", errorCode,
                "E-47 Druckabfall im Presshub",
                "Presse baut keinen Druck auf.",
                "Dichtsatz am Hauptzylinder verschlissen.",
                "Dichtsatz erneuert, entlüftet.",
                partsUsed, downtimeMinutes, technicianInitials, language, "schichtleiter", true);
    }

    private static CorpusProtocol german() {
        return protocol("de", "E-47", "Dichtsatz 80/45", 45, "M.K.");
    }

    @Test
    void render_germanProtocol_labelsEverySectionInGerman() {
        String document = ProtocolDocumentRenderer.render(german());

        // The document's own language, because the chunk keeps these labels and the embedding model
        // sees them. A German protocol under English headings would be half-translated text in a
        // space where nothing else is.
        assertThat(document).startsWith("WARTUNGSPROTOKOLL\n=================\n\n");
        assertThat(document)
                .contains("Maschine: PR-03")
                .contains("Datum: 07.08.2026")
                .contains("Fehlercode: E-47")
                .contains("Stillstand: 45 Minuten")
                .contains("Symptom:")
                .contains("Ursache:")
                .contains("Massnahme:")
                .contains("Ersatzteile:");
    }

    @Test
    void render_englishProtocol_labelsEverySectionInEnglish() {
        String document = ProtocolDocumentRenderer.render(protocol("en", "E-47", "Seal kit", 45, "M.K."));

        // The underline is as long as the heading it underlines — 20 against 17 — which is the one
        // place the two languages differ by more than a word.
        assertThat(document).startsWith("MAINTENANCE PROTOCOL\n====================\n\n");
        assertThat(document)
                .contains("Machine: PR-03")
                .contains("Downtime: 45 minutes")
                .contains("Cause:")
                .contains("Action:")
                .contains("Parts used:");
    }

    @Test
    void render_unknownLanguage_fallsBackToEnglish() {
        // Only "de" selects German. Anything else — a future language, a typo in the seed data —
        // renders English rather than a half-labelled document.
        assertThat(ProtocolDocumentRenderer.render(protocol("fr", "E-47", null, null, "M.K.")))
                .startsWith("MAINTENANCE PROTOCOL");
    }

    @Test
    void render_absentDowntime_omitsTheLineRatherThanPrintingAnEmptyOne() {
        String document = ProtocolDocumentRenderer.render(protocol("de", "E-47", "Dichtsatz", null, "M.K."));

        // Not "Stillstand: ". A label with nothing after it survives into the chunk and reads, to
        // both the model and the technician, as a value that was lost rather than never recorded.
        assertThat(document).doesNotContain("Stillstand");
        assertThat(document).contains("Fehlercode: E-47");
    }

    @Test
    void render_absentErrorCode_omitsTheLine() {
        // A WARTUNG protocol has no fault code — the common case, not an edge one.
        assertThat(ProtocolDocumentRenderer.render(protocol("de", null, "Dichtsatz", 45, "M.K.")))
                .doesNotContain("Fehlercode");
    }

    @Test
    void render_blankErrorCode_isTreatedAsAbsent() {
        // Blank rather than null: seed data and hand-typed forms both produce "   ", and the rule
        // has to be about content rather than about nullness.
        assertThat(ProtocolDocumentRenderer.render(protocol("de", "   ", "Dichtsatz", 45, "M.K.")))
                .doesNotContain("Fehlercode");
    }

    @Test
    void render_absentPartsUsed_omitsTheWholeSection() {
        String document = ProtocolDocumentRenderer.render(protocol("de", "E-47", null, 45, "M.K."));

        // A heading with no body would become a chunk boundary around nothing.
        assertThat(document).doesNotContain("Ersatzteile");
        assertThat(document).contains("Massnahme:");
    }

    @Test
    void render_blankPartsUsed_isTreatedAsAbsent() {
        assertThat(ProtocolDocumentRenderer.render(protocol("de", "E-47", "  \n ", 45, "M.K.")))
                .doesNotContain("Ersatzteile");
    }

    @Test
    void render_absentTechnician_omitsTheLine() {
        assertThat(ProtocolDocumentRenderer.render(protocol("de", "E-47", "Dichtsatz", 45, null)))
                .doesNotContain("Techniker");
    }

    @Test
    void render_anyProtocol_separatesSectionsWithABlankLine() {
        String document = ProtocolDocumentRenderer.render(german());

        // THE CONTRACT WITH THE CHUNKER, and the reason this is not a cosmetic test: the chunker
        // splits on blank-line boundaries. Sections run together would arrive there as one section
        // and be cut by size instead — across "Ursache:" and "Massnahme:", the two boundaries a
        // maintenance answer most needs kept together.
        assertThat(document).contains("Symptom:\nPresse baut keinen Druck auf.\n\n");
        assertThat(document).contains("\n\nUrsache:");
    }

    @Test
    void render_anyProtocol_putsTheTitleOnItsOwnBetweenHeaderAndSections() {
        String document = ProtocolDocumentRenderer.render(german());

        // The title is what a search result shows, and it sits in its own paragraph so it lands in
        // the first chunk whole rather than as the tail of the header block.
        assertThat(document).contains("\nE-47 Druckabfall im Presshub\n\nSymptom:");
    }
}
