package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chunking rules, at both sides of every boundary they turn on.
 *
 * <p>This is the retrieval-critical logic of the project and it had no unit test: it was exercised
 * only incidentally, through the ingestion pipeline. A chunk is what gets embedded, retrieved and
 * shown as a citation, so a wrong boundary here is not a formatting problem — it is an answer that
 * cites a paragraph the technician cannot make sense of, or a cause and its consequence landing in
 * different chunks with neither one usable.
 *
 * <p>THE CONSTANTS ARE RESTATED HERE ON PURPOSE, not imported: they are private to the chunker, and
 * a test that read them from the class under test would keep passing if they changed. These numbers
 * are the contract — 900 to aim for, 1200 as the ceiling, 150 carried over — and a change to any of
 * them should turn this file red and be re-ruled rather than absorbed.
 *
 * <p>OUT OF SCOPE: how the document that arrives here was rendered (ProtocolDocumentRenderer) and
 * what happens to the chunks afterwards (ProtocolIndexer, ProtocolIndexWriter).
 */
class ProtocolChunkerTest {

    private static final int TARGET_CHARS = 900;
    private static final int MAX_CHARS = 1_200;
    private static final int OVERLAP_CHARS = 150;

    private static final String CONTEXT = "PR-03 · E-47 · E-47 Druckabfall im Presshub";

    private final ProtocolChunker chunker = new ProtocolChunker();

    /** {@code length} characters of filler with no sentence end in them. */
    private static String filler(int length) {
        return "x".repeat(length);
    }

    // -------------------------------------------------------------------------------------------
    // The prefix
    // -------------------------------------------------------------------------------------------

    @Test
    void chunk_anyChunk_carriesTheContextPrefix() {
        List<String> chunks = chunker.chunk("Symptom:\nKein Druck.\n\nUrsache:\nDichtsatz.", CONTEXT);

        // A chunk is retrieved on its own and shown on its own. Without the prefix, "Dichtsatz
        // erneuert" is a sentence with no machine and no fault attached to it — to the embedding
        // model as much as to the technician reading the citation.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).startsWith(CONTEXT + "\n\n").contains("Kein Druck.");
    }

    @Test
    void chunk_blankContext_omitsThePrefixAndItsBlankLine() {
        List<String> chunks = chunker.chunk("Symptom:\nKein Druck.", "  ");

        // Not an empty prefix followed by two newlines: a chunk that began with blank lines would
        // spend its first tokens on nothing and read as a formatting fault in the citation.
        assertThat(chunks).containsExactly("Symptom:\nKein Druck.");
    }

    // -------------------------------------------------------------------------------------------
    // Merging: the boundary is 900, and both sides of it are tested
    // -------------------------------------------------------------------------------------------

    @Test
    void chunk_sectionsMeetingTheTargetExactly_stayOneChunk() {
        // a + "\n\n" + b == exactly TARGET_CHARS. The rule flushes when the total would EXCEED the
        // target, so landing on it precisely must still merge.
        String document = filler(400) + "\n\n" + filler(TARGET_CHARS - 400 - 2);

        List<String> chunks = chunker.chunk(document, "");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(TARGET_CHARS);
    }

    @Test
    void chunk_sectionsOneCharacterPastTheTarget_startANewChunk() {
        // The same document with one character more. One char is the whole difference between a
        // protocol that stays whole and one that becomes two weaker matches.
        String document = filler(400) + "\n\n" + filler(TARGET_CHARS - 400 - 1);

        List<String> chunks = chunker.chunk(document, "");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(400);
        assertThat(chunks.get(1)).hasSize(TARGET_CHARS - 400 - 1);
    }

    @Test
    void chunk_shortProtocol_staysWholeRatherThanBecomingFragments() {
        String document = """
                Symptom:
                Presse baut keinen Druck auf.

                Ursache:
                Dichtsatz am Hauptzylinder verschlissen.

                Massnahme:
                Dichtsatz erneuert, entlüftet, Druck auf 250 bar geprüft.""";

        List<String> chunks = chunker.chunk(document, "");

        // The corpus README records that many protocols are two lines. Four fragments that all
        // match weakly is worse than one chunk that matches well.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("Symptom:").contains("Ursache:").contains("Massnahme:");
    }

    // -------------------------------------------------------------------------------------------
    // Splitting an oversized section: the ceiling is 1200, the overlap is 150
    // -------------------------------------------------------------------------------------------

    @Test
    void chunk_sectionOverTheCeiling_isCutAtTheLastSentenceEnd() {
        String section = filler(998) + ". " + filler(300);

        List<String> chunks = chunker.chunk(section, "");

        // Cut after the sentence end, not at the ceiling: a sentence sliced in half is a sentence
        // neither chunk can be understood from.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).endsWith(".");
        assertThat(chunks.get(0)).hasSize(999);
    }

    @Test
    void chunk_cutSection_repeatsItsTailAtTheHeadOfTheNextChunk() {
        String section = filler(998) + ". " + filler(300);

        List<String> chunks = chunker.chunk(section, "");

        // THE OVERLAP, and it exists for this case only. Between two sections there is a real
        // boundary and repeating text would return the same content twice; inside a section the cut
        // is arbitrary, and the overlap is what stops a cause and its consequence landing in
        // different chunks with neither making sense.
        String tail = chunks.get(0).substring(chunks.get(0).length() - OVERLAP_CHARS);
        assertThat(chunks.get(1)).startsWith(tail);
    }

    @Test
    void chunk_oversizedSectionWithNoSentenceEnd_isCutAtTheCeiling() {
        String section = filler(MAX_CHARS + 100);

        List<String> chunks = chunker.chunk(section, "");

        // No boundary to prefer, so the ceiling is the cut. The alternative — refusing to split —
        // would send a chunk past what the embedding call is sized for.
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(MAX_CHARS);
    }

    @Test
    void chunk_sectionExactlyAtTheCeiling_isNotSplit() {
        String section = filler(MAX_CHARS);

        List<String> chunks = chunker.chunk(section, "");

        // At the ceiling, not past it: the split rule is "greater than", and a section that lands
        // exactly on the limit is whole.
        assertThat(chunks).hasSize(1);
    }

    @Test
    void chunk_questionAndExclamationMarks_countAsSentenceEnds() {
        String withQuestion = filler(998) + "? " + filler(300);
        String withExclamation = filler(998) + "! " + filler(300);

        // All three terminators, because a German protocol asks and exclaims as readily as it
        // states, and a rule that only knew "." would cut those mid-sentence.
        assertThat(chunker.chunk(withQuestion, "").get(0)).endsWith("?");
        assertThat(chunker.chunk(withExclamation, "").get(0)).endsWith("!");
    }

    @Test
    void chunk_decimalPointWithNoSpaceAfterIt_isNotASentenceEnd() {
        // "250.5 bar" is a measurement, not the end of a sentence. The whitespace test is what
        // keeps a pressure reading from becoming a chunk boundary.
        String section = filler(996) + "250.5" + filler(300);

        List<String> chunks = chunker.chunk(section, "");

        assertThat(chunks.get(0)).hasSize(MAX_CHARS);
    }

    // -------------------------------------------------------------------------------------------
    // Degenerate documents
    // -------------------------------------------------------------------------------------------

    @Test
    void chunk_blankDocument_producesNoChunksAtAll() {
        // Nothing to embed, and nothing is better than one empty chunk that matches everything
        // weakly. UploadContentPolicy refuses an empty file before this is ever reached.
        assertThat(chunker.chunk("   \n\n  \n ", CONTEXT)).isEmpty();
    }

    @Test
    void chunk_runsOfBlankLines_doNotProduceEmptySections() {
        List<String> chunks = chunker.chunk("Symptom:\nKein Druck.\n\n\n\n   \n\nUrsache:\nDichtsatz.", "");

        // Hand-typed notes are separated by however many blank lines the author felt like. An empty
        // section between them would become a chunk of pure context prefix.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("Symptom:\nKein Druck.\n\nUrsache:\nDichtsatz.");
    }
}
