package com.keglevich.maintenanceassistant.query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answer, shaped for the search view that renders it.
 *
 * <p>NFR-2 asks for two <em>visually distinct</em> answer modes, and that requirement is met by what
 * this record hands over, not by prose the client has to interpret. {@link #mode()} is the switch,
 * {@link #claims()} carries the per-claim citation labels so the view can render a source link
 * inline rather than a footnote, and {@link #citations()} is the deduplicated source list behind
 * those labels. A Mode B answer has an empty citation list and empty claims — structurally, because
 * a Mode B answer is produced by a prompt and a schema that have no source field at all.
 *
 * @param mode      A = grounded ("Belegte Antwort"), B = labelled ungrounded suggestion
 * @param answer    the rendered prose, ready to display. Mode A carries its {@code [P1]} markers
 *                  inline so a client that does nothing clever still shows a cited answer
 * @param language  the language the answer was pinned to, as the model reports having written it.
 *                  The view needs this to set {@code lang} on the element for screen readers and
 *                  hyphenation, and it is the observable half of the language rule ADR-002 records
 * @param claims    Mode A only: one statement, one source label. Empty in Mode B
 * @param citations the sources actually cited, in label order. Empty in Mode B
 */
public record QueryAnswer(
        AnswerMode mode,
        String answer,
        String language,
        List<Claim> claims,
        List<Citation> citations) {

    /** NFR-2's two modes. Serialised as "A" and "B". */
    public enum AnswerMode {
        A, B
    }

    /**
     * One statement and the source it came from.
     *
     * @param source the {@code [P1]}-style label, matching a {@link Citation#label()}
     */
    public record Claim(String text, String source) {
    }

    /**
     * A cited protocol.
     *
     * @param label      the {@code P1}..{@code P5} label used in the answer text and in claims
     * @param similarity     the cosine similarity the retrieval scored it, kept so the demo can show
     *                       why a case is Mode A and the next one is Mode B rather than asserting it.
     *                       Still pure cosine after ADR-009 — the lexical signal never edits it
     * @param lexicalMatches how many of the question's exact terms — an alarm code, a part number —
     *                       appear literally in this protocol. THE SECOND COMPONENT, REPORTED
     *                       SEPARATELY ON PURPOSE: a single fused number would be one nobody can
     *                       decompose, and this is the field that lets an interface say "the code you
     *                       typed is in this protocol" rather than showing a score that moved for
     *                       reasons the reader cannot see. Zero for most questions
     */
    public record Citation(
            String label,
            UUID protocolId,
            String title,
            String errorCode,
            LocalDate incidentDate,
            double similarity,
            int lexicalMatches,
            /*
             * Whether an administrator has vouched for the protocol behind this citation.
             *
             * ON THE CITATION, not fetched separately, and the reason is what a citation is FOR.
             * NFR-2's discipline — every claim names its source — is what makes a Mode A answer
             * checkable, and it has the side effect of making any cited claim LOOK verified.
             * Unapproved protocols stay searchable by decision (2026-08-11), so an answer can be
             * grounded in text nobody has reviewed. This flag is what lets the interface say so
             * beside the source rather than leaving the reader to assume; a client that had to ask
             * a second endpoint for it would eventually forget to.
             */
            boolean approved) {
    }
}
