package com.keglevich.maintenanceassistant.query;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One retrieved chunk and everything an answer needs to cite it.
 *
 * <p>The chunk is the search unit and the protocol is the citation unit (DECISIONS.txt, DATA MODEL),
 * which is why this record carries both: the model reasons over {@link #content()} and the answer
 * points at {@link #protocolId()}. Title, error code and date come from the protocol row rather than
 * from the chunk text, so a citation says the same thing whichever chunk of a protocol was matched.
 *
 * @param similarity     cosine similarity in {@code [-1, 1]}, {@code 1 - (embedding <=> question)}.
 *                       This is the number the Mode A / Mode B threshold is compared against, and
 *                       the number ADR-002's measured demo cases are quoted in. <b>It stays pure
 *                       cosine after ADR-009's hybrid retrieval</b> — the lexical signal orders
 *                       candidates and grounds an answer, and never edits this number, so 0.55 keeps
 *                       the meaning it was measured with.
 * @param lexicalMatches how many of the question's exact terms (alarm codes, part numbers) appear
 *                       literally in this chunk. Reported <b>beside</b> the similarity rather than
 *                       folded into it, so a reader can always decompose why a chunk was returned;
 *                       zero for every question that carries no such term, which is most of them
 */
record RetrievedChunk(
        UUID chunkId,
        UUID protocolId,
        String content,
        String title,
        String errorCode,
        String language,
        LocalDate incidentDate,
        double similarity,
        int lexicalMatches,
        /*
         * Whether an administrator has vouched for the protocol this chunk came from.
         *
         * Carried all the way to the citation on purpose. Unapproved protocols stay searchable by
         * decision (2026-08-11), which means an answer can be grounded in text nobody has reviewed —
         * and NFR-2's citation discipline makes any cited claim LOOK checked. This flag is what
         * keeps that impression honest, so it has to travel with the citation rather than be
         * something the client fetches separately and might forget to.
         */
        boolean approved) {
}
