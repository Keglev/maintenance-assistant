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
 * @param similarity cosine similarity in {@code [-1, 1]}, {@code 1 - (embedding <=> question)}.
 *                   This is the number the Mode A / Mode B threshold is compared against, and the
 *                   number ADR-002's measured demo cases are quoted in.
 */
record RetrievedChunk(
        UUID chunkId,
        UUID protocolId,
        String content,
        String title,
        String errorCode,
        String language,
        LocalDate incidentDate,
        double similarity) {
}
