package com.keglevich.maintenanceassistant.query;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The exact terms in a question — alarm codes, part numbers — that a dense vector cannot carry.
 *
 * <p><b>The rule: a term must contain at least one letter AND at least one digit.</b> That single
 * condition is what makes the lexical signal safe to add, and it is worth stating why rather than
 * treating it as a heuristic.
 *
 * <p>Ordinary German or English prose cannot satisfy it. {@code Dosierung}, {@code Füllmenge},
 * {@code Betriebsurlaub} carry no digits; {@code 400}, {@code 250}, {@code 30} carry no letters. So
 * a question that is merely a description — the shape of every question the corpus deliberately
 * cannot answer — produces no terms at all, gets no lexical signal, and is routed exactly as it is
 * today. <b>Measured on the golden set before this class existed: of 19 questions, 3 produce terms
 * ({@code E-47}, {@code SV0410}, {@code KOM-04}), and both Mode B questions produce none.</b> The
 * anti-hallucination guarantee is preserved by construction rather than by tuning.
 *
 * <p>Bare numbers are excluded on purpose even though {@code "Antriebstrommel 400 mm"} is a real
 * part reference: {@code 400} also appears as a pressure, a duration, a count and a year across this
 * corpus, so matching it would fire the signal on questions that merely mention a quantity. The cost
 * is that a pure part-number question is answered by the vector alone, which is what it already
 * does — G14 clears the gate at 0.6000 without help.
 *
 * <p>The character set is restricted to letters, digits and {@code -}, which also makes the terms
 * safe to hand to {@code ILIKE}: neither {@code %} nor {@code _} can survive extraction, so no term
 * can become a wildcard. The retrieval query still binds them as a parameter; this is the second
 * line of defence, not the first.
 */
final class LexicalTerms {

    /** Below three characters a "code" is noise; above 32 it is not a code. */
    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 32;

    /**
     * At most this many terms per question.
     *
     * <p>A bound rather than a limit anyone should hit: it stops a pasted log line from turning one
     * question into a scan with fifty {@code ILIKE}s, and the retrieval cost stays predictable.
     */
    private static final int MAX_TERMS = 5;

    private LexicalTerms() {
    }

    /**
     * The code-like terms of a question, lower-cased, de-duplicated, in the order they appear.
     *
     * @return possibly empty — and empty is the common case, which is why the retrieval query has to
     *         reduce exactly to its pre-hybrid form when it happens
     */
    static List<String> extract(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String raw : question.split("[^\\p{L}\\p{N}-]+")) {
            String term = trimSeparators(raw);
            if (isCodeLike(term)) {
                terms.add(term.toLowerCase(Locale.ROOT));
            }
            if (terms.size() == MAX_TERMS) {
                break;
            }
        }
        return List.copyOf(terms);
    }

    /**
     * Joined by a space for the retrieval query, which splits it again with {@code string_to_array}.
     *
     * <p>A space is unambiguous because extraction cannot produce one, and passing a single bound
     * String avoids handing the driver an array type — the terms never become part of the statement
     * text.
     */
    static String joined(List<String> terms) {
        return String.join(" ", terms);
    }

    private static String trimSeparators(String raw) {
        int from = 0;
        int to = raw.length();
        while (from < to && raw.charAt(from) == '-') {
            from++;
        }
        while (to > from && raw.charAt(to - 1) == '-') {
            to--;
        }
        return raw.substring(from, to);
    }

    private static boolean isCodeLike(String term) {
        if (term.length() < MIN_LENGTH || term.length() > MAX_LENGTH) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        return letter && digit;
    }
}
