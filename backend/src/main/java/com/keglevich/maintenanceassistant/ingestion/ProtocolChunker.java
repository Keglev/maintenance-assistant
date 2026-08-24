package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a protocol document into the units that get embedded and searched.
 *
 * <h2>Why this strategy</h2>
 *
 * <p>These documents are short. A shift protocol is a header plus a few labelled sections, and
 * the corpus README records that many are only a few sentences — the messy and sparse ones are two
 * lines. Fixed-width token windows, the default choice for long documents, are the wrong tool here:
 * they would cut across "Ursache:" and "Massnahme:", which are exactly the boundaries a maintenance
 * answer needs to keep together.
 *
 * <p>So the unit is the <b>paragraph</b> — which in these documents is the labelled section, since
 * {@code ProtocolDocumentRenderer} separates them with a blank line. Small sections are merged up to
 * a target size so a two-line protocol stays one chunk instead of becoming four fragments that all
 * match weakly. An oversized section is split on sentence boundaries with a small overlap, so a
 * sentence cut in half still appears whole in one of the two chunks.
 *
 * <p><b>Every chunk is prefixed with its context</b> — machine number, error code, title. A chunk is
 * retrieved on its own and shown on its own; without the prefix, "Dichtsatz erneuert, entlüftet,
 * Druck auf 250 bar" is a sentence with no machine and no fault attached to it. The prefix costs a
 * few tokens per chunk and makes each one self-describing, both to the embedding model and to a
 * technician reading the citation.
 */
@Component
class ProtocolChunker {

    /** Aim for chunks around this size; a protocol shorter than this stays whole. */
    private static final int TARGET_CHARS = 900;
    /** Hard ceiling before a section is split internally. */
    private static final int MAX_CHARS = 1_200;
    /** Carried from the end of one chunk into the next, only where a section had to be cut. */
    private static final int OVERLAP_CHARS = 150;

    /**
     * @param context short, human-readable identity of the protocol, e.g. {@code "PR-03 · E-47 · E-47 Druckabfall im Presshub"}
     */
    List<String> chunk(String documentText, String context) {
        List<String> sections = splitIntoSections(documentText);
        List<String> merged = mergeToTarget(sections);

        List<String> chunks = new ArrayList<>(merged.size());
        for (String body : merged) {
            chunks.add(context.isBlank() ? body : context + "\n\n" + body);
        }
        return chunks;
    }

    /** Blank-line separated, which is what the rendered documents and hand-typed notes both use. */
    private static List<String> splitIntoSections(String text) {
        List<String> sections = new ArrayList<>();
        for (String part : text.strip().split("\\r?\\n\\s*\\r?\\n")) {
            String section = part.strip();
            if (!section.isEmpty()) {
                sections.add(section);
            }
        }
        return sections.isEmpty() ? List.of() : sections;
    }

    /**
     * Greedy accumulation up to {@link #TARGET_CHARS}. Sections that are themselves too large are
     * flushed and split; everything else is packed, which is what keeps a short protocol whole.
     */
    private static List<String> mergeToTarget(List<String> sections) {
        List<String> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String section : sections) {
            if (section.length() > MAX_CHARS) {
                flush(buffer, out);
                out.addAll(splitOversized(section));
                continue;
            }
            if (!buffer.isEmpty() && buffer.length() + section.length() + 2 > TARGET_CHARS) {
                flush(buffer, out);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(section);
        }
        flush(buffer, out);
        return out;
    }

    private static void flush(StringBuilder buffer, List<String> out) {
        if (!buffer.isEmpty()) {
            out.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    /**
     * Splits one long section on sentence ends, with an overlap. The overlap exists for this case
     * only: between two sections there is a real boundary and repeating text would just return the
     * same content twice. Inside a section the cut is arbitrary, and the overlap is what stops a
     * cause and its consequence landing in different chunks with neither making sense.
     */
    private static List<String> splitOversized(String section) {
        List<String> parts = new ArrayList<>();
        int from = 0;

        // COVERAGE WAIVER U1 (2026-08-22, register in docs/REFACTOR-STANDARDS.txt). The false side
        // of this condition never runs, and that is the loop being correct rather than a gap in the
        // tests: every iteration that does not exit through the break below leaves `from` strictly
        // inside the section, so the only way out is the break. Reaching the condition as false
        // would mean an empty section arrived here, which the sole caller prevents by only calling
        // this for sections longer than MAX_CHARS. Forcing it green would mean adding a caller that
        // does not exist, purely to satisfy a counter.
        while (from < section.length()) {
            int to = Math.min(from + MAX_CHARS, section.length());
            if (to < section.length()) {
                int boundary = lastSentenceEnd(section, from, to);
                if (boundary > from) {
                    to = boundary;
                }
            }
            parts.add(section.substring(from, to).strip());
            if (to >= section.length()) {
                break;
            }
            from = Math.max(from + 1, to - OVERLAP_CHARS);
        }
        return parts;
    }

    /** Last ". ", "! " or "? " in the window; -1 when the window has no sentence end at all. */
    private static int lastSentenceEnd(String text, int from, int to) {
        for (int i = to - 1; i > from; i--) {
            char c = text.charAt(i);
            // COVERAGE WAIVER U2 (2026-08-22, register in docs/REFACTOR-STANDARDS.txt). The false
            // side of `i + 1 < text.length()` never runs because the only caller passes a window
            // that stops short of the end, so `i` can never be the last character. It is kept as a
            // bounds check on a method that reads one character past `i`: the guarantee lives in
            // the caller, and a second caller written without it would otherwise read off the end.
            // Deleting it to win a branch would trade a real safety property for a percentage.
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }
}
