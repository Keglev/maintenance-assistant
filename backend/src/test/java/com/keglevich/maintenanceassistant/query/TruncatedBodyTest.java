package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anatomy of a truncated answer, which is the evidence the A4 hypothesis is decided on.
 *
 * <p>The two cases below are the two things a 1200-token body can be, and the whole point of this
 * record is that they are TELLABLE APART. The first is the constrained-decoding degeneration the
 * measurement of 2026-08-26 made likely: a refusal the model had already committed to, followed by
 * unbounded JSON whitespace. The second is the reading the incident entry originally assumed — an
 * answer that genuinely had more to say. They share their first characters, which is why the
 * previous log line could not separate them, and they differ in every field asserted here.
 *
 * <p>NO PROVIDER AND NO SPRING CONTEXT: this is a pure function over a string, so it is a unit test
 * in the strict sense. What it does NOT cover is which of the two shapes production actually
 * produces — that is a measurement, and W-2 exists to take it.
 */
class TruncatedBodyTest {

    /** The shape A4 predicts: the refusal, then padding to the cap. */
    private static final String DEGENERATED = "{\"answer_language\": \"de\", \"claims\": []" + " ".repeat(800);

    @Test
    @DisplayName("a refusal followed by whitespace reports itself as one")
    void of_refusalPaddedToTheCap_isRefusalShapedAndAlmostAllWhitespace() {
        TruncatedBody anatomy = TruncatedBody.of(DEGENERATED, 1200);

        assertThat(anatomy.refusalShaped())
                .as("the body parses as an empty claims list once the missing brace is added")
                .isTrue();
        // 800 spaces against 838 characters. The assertion is a band rather than an equality
        // because the prefix length is incidental; what W-3 keys off is "almost all whitespace",
        // and the ledger's confirmation rule says > 0.9.
        assertThat(anatomy.whitespaceRatio()).isGreaterThan(0.9).isLessThan(1.0);
        assertThat(anatomy.characters()).isEqualTo(DEGENERATED.length());
        assertThat(anatomy.completionTokens()).isEqualTo(1200);
        assertThat(anatomy.tail())
                .as("the tail is what the first-characters preview could never show")
                .hasSize(200)
                .isBlank();
    }

    @Test
    @DisplayName("an answer that ran out of room is not refusal-shaped and carries prose in its tail")
    void of_genuinelyLongAnswer_isNotRefusalShapedAndCarriesProse() {
        String cutOff = "{\"answer_language\": \"de\", \"claims\": [{\"text\": \""
                + "Der Fehler E-47 bedeutet Druckabfall im Presshub. ".repeat(20);

        TruncatedBody anatomy = TruncatedBody.of(cutOff, 1200);

        assertThat(anatomy.refusalShaped())
                .as("a brace does not repair this, and this record does not guess further")
                .isFalse();
        assertThat(anatomy.whitespaceRatio()).isLessThan(0.3);
        assertThat(anatomy.tail()).contains("Presshub");
    }

    @Test
    @DisplayName("newlines in the tail are escaped so the log line stays one line")
    void of_bodyWithNewlines_escapesThemInTheTail() {
        TruncatedBody anatomy = TruncatedBody.of("{\n  \"claims\": [\n  ]\n", 42);

        assertThat(anatomy.tail()).doesNotContain("\n").contains("\\n");
        assertThat(anatomy.refusalShaped()).isTrue();
    }

    @Test
    @DisplayName("a body that is already closed is not brace-repaired twice")
    void of_completeRefusal_isRefusalShapedWithoutRepair() {
        assertThat(TruncatedBody.of("{\"claims\": []}", 12).refusalShaped()).isTrue();
    }

    @Test
    @DisplayName("a non-empty claims list is an answer, not a refusal")
    void of_claimsPresent_isNotRefusalShaped() {
        assertThat(TruncatedBody.of("{\"claims\": [{\"text\": \"x\", \"source\": \"P1\"}]}", 12)
                .refusalShaped()).isFalse();
    }

    @Test
    @DisplayName("an empty body reports zeroes rather than throwing while an exception is built")
    void of_emptyOrNullContent_isSafeBecauseItRunsInsideAFailurePath() {
        // The ordering in the client makes this unreachable today — blank content throws as EMPTY
        // before the truncation branch is reached. It is asserted anyway: this helper runs while
        // another exception's message is being assembled, and a NullPointerException here would
        // replace a useful diagnosis with a useless one.
        assertThat(TruncatedBody.of(null, 0).characters()).isZero();
        assertThat(TruncatedBody.of(null, 0).whitespaceRatio()).isZero();
        assertThat(TruncatedBody.of("", 0).refusalShaped()).isFalse();
        assertThat(TruncatedBody.of("   ", 0).whitespaceRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("toString is the log line, and it names every field it prints")
    void toString_isGreppableAndCarriesEveryField() {
        String line = TruncatedBody.of(DEGENERATED, 1200).toString();

        assertThat(line)
                .contains("completionTokens=1200")
                .contains("characters=838")
                .contains("refusalShaped=true")
                .contains("whitespaceRatio=0.9")
                .contains("tail=\"");
    }
}
