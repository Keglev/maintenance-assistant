package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule the whole hybrid signal rests on: letter AND digit, or it is not a term.
 *
 * <p>These are the cases that decide whether ADR-009 is safe. The extraction is the only thing
 * standing between "a code lifts an answer into Mode A" and "any sentence lifts any answer into
 * Mode A", so the negative cases matter more here than the positive ones.
 */
class LexicalTermsTest {

    // -------------------------------------------------------------------------------------------
    // What must be found
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "Was bedeutet KOM-04?",
            "kom-04",
            "Fehler KOM-04 steht an, was tun?",
            "KOM-04.",
            "(KOM-04)",
    })
    @DisplayName("an alarm code is found however the question is punctuated or cased")
    void findsTheCode(String question) {
        assertThat(LexicalTerms.extract(question)).containsExactly("kom-04");
    }

    @Test
    @DisplayName("the corpus's real code shapes all extract")
    void findsEveryCodeShapeInTheCorpus() {
        // Hyphenated, run-together and letter-suffixed: all three appear in protocols.ndjson.
        assertThat(LexicalTerms.extract("SV0410 Schleppfehler")).containsExactly("sv0410");
        assertThat(LexicalTerms.extract("Presse kommt nicht auf Druck, Fehler E-47"))
                .containsExactly("e-47");
        assertThat(LexicalTerms.extract("Alarm A-1140 und OH0700 zusammen"))
                .containsExactly("a-1140", "oh0700");
    }

    // -------------------------------------------------------------------------------------------
    // What must NOT be found — the safety half, and the reason Mode B survives
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "Die Dosierung ist ungenau, die Füllmenge schwankt",
            "Wie wird die Anlage über den Betriebsurlaub eingelagert?",
            "Presse reagiert beim Einschalten überhaupt nicht, nichts leuchtet",
            "Getriebe wird heiß und es liegt Öl auf dem Boden",
            "One cavity is filling incompletely, the other seven are fine",
    })
    @DisplayName("ordinary prose yields no terms — this is why Mode B questions stay Mode B")
    void proseProducesNothing(String question) {
        assertThat(LexicalTerms.extract(question)).isEmpty();
    }

    @Test
    @DisplayName("bare numbers are not terms, however domain-shaped they look")
    void bareNumbersAreNotTerms() {
        // 400 is a drum diameter here and a pressure, a duration and a year elsewhere in the corpus.
        // Matching it would fire the signal on any question that mentions a quantity.
        assertThat(LexicalTerms.extract("gummierte Antriebstrommel 400 mm getauscht")).isEmpty();
        assertThat(LexicalTerms.extract("Druck faellt von 250 auf 180 bar in 20 s")).isEmpty();
    }

    @Test
    @DisplayName("a German compound is not a term — the case that ruled out full-text search")
    void germanCompoundsAreNotTerms() {
        // Postgres' 'german' configuration does not decompound, and measured on this database
        // "Antriebstrommeln" does NOT match "Antriebstrommel" through the stemmer. Rather than take
        // a stemmer's word for what a compound means, the lexical signal simply does not claim
        // compounds at all and leaves them to the embedding, which handles them well.
        assertThat(LexicalTerms.extract("Antriebstrommel")).isEmpty();
        assertThat(LexicalTerms.extract("Druckbegrenzungsventil klemmt")).isEmpty();
        assertThat(LexicalTerms.extract("Zentralschmierung Störung")).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // Bounds
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("no term can carry an ILIKE wildcard")
    void wildcardsCannotSurvive() {
        assertThat(LexicalTerms.extract("code A%1 and B_2 and C-3"))
                .as("%% and _ are split points, so a term can never become a wildcard")
                .containsExactly("c-3");
    }

    @Test
    @DisplayName("a pasted log line cannot turn one question into a scan")
    void isBounded() {
        String log = "E-01 E-02 E-03 E-04 E-05 E-06 E-07 E-08 E-09 E-10 E-11 E-12";
        assertThat(LexicalTerms.extract(log)).hasSize(5);
    }

    @Test
    @DisplayName("empty, blank and null questions yield nothing rather than failing")
    void handlesNothing() {
        assertThat(LexicalTerms.extract(null)).isEmpty();
        assertThat(LexicalTerms.extract("")).isEmpty();
        assertThat(LexicalTerms.extract("   ")).isEmpty();
        assertThat(LexicalTerms.joined(LexicalTerms.extract(""))).isEmpty();
    }

    @Test
    @DisplayName("terms are joined by a space, which extraction can never produce")
    void joinsForTheQuery() {
        assertThat(LexicalTerms.joined(LexicalTerms.extract("A-1140 and OH0700")))
                .isEqualTo("a-1140 oh0700");
    }
}
