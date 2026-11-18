package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two guarantees every log line of model output relies on: one line, and a bounded length.
 *
 * <p>NO PROVIDER AND NO SPRING CONTEXT: pure functions over a string.
 */
class LogTextTest {

    @Test
    @DisplayName("the head keeps the first characters and stops at the limit")
    void head_longText_keepsTheFirstMaxCharacters() {
        String text = "A".repeat(LogText.MAX) + "BEYOND";

        assertThat(LogText.head(text)).hasSize(LogText.MAX).doesNotContain("B");
    }

    @Test
    @DisplayName("the tail keeps the last characters and stops at the limit")
    void tail_longText_keepsTheLastMaxCharacters() {
        String text = "BEFORE" + "Z".repeat(LogText.MAX);

        assertThat(LogText.tail(text)).hasSize(LogText.MAX).doesNotContain("B");
    }

    @Test
    @DisplayName("a short answer is kept whole, which is what makes an 11-token reply readable")
    void head_shortText_isKeptWhole() {
        String refusal = "{\"answer_language\":\"de\",\"claims\":[]}";

        assertThat(LogText.head(refusal)).isEqualTo(refusal);
    }

    @Test
    @DisplayName("newlines, tabs and backslashes are escaped so the line stays one line")
    void head_controlCharacters_areEscaped() {
        assertThat(LogText.head("a\nb\r\tc\\d")).isEqualTo("a\\nb\\r\\tc\\\\d");
    }

    @Test
    @DisplayName("null is logged as nothing rather than failing inside a warning")
    void headAndTail_null_returnEmpty() {
        assertThat(LogText.head(null)).isEmpty();
        assertThat(LogText.tail(null)).isEmpty();
    }
}
