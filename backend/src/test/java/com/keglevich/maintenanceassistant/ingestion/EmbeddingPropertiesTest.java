package com.keglevich.maintenanceassistant.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether ingestion is configured at all, at every combination that decides it.
 *
 * <p>The consequence of this one method is the difference between a pipeline that is INERT and one
 * that fails per protocol at call time. Without a key, every upload would be accepted, queued,
 * attempted and marked FAILED — a corpus of broken rows and an operator reading provider errors for
 * a configuration mistake. So the four ways of being unconfigured all have to answer the same way.
 *
 * <p>Parameterised rather than four near-identical tests: the table IS the specification here, and
 * a reader checking whether blank counts the same as null should see both rows side by side.
 */
class EmbeddingPropertiesTest {

    private static EmbeddingProperties with(String baseUrl, String apiKey) {
        return new EmbeddingProperties(
                baseUrl, apiKey, "BAAI/bge-m3", 1024, 32,
                2, Duration.ofSeconds(1), Duration.ofSeconds(30), 500);
    }

    @Test
    void isConfigured_keyAndBaseUrlBothPresent_saysYes() {
        assertThat(with("https://openai.inference.de-txl.ionos.com/v1", "secret").isConfigured())
                .isTrue();
    }

    @ParameterizedTest(name = "baseUrl={0} apiKey={1}")
    @CsvSource(value = {
            "NULL,           secret",
            "'   ',          secret",
            "https://api/v1, NULL",
            "https://api/v1, '   '",
            "NULL,           NULL",
    }, nullValues = "NULL")
    void isConfigured_anythingMissing_saysNo(String baseUrl, String apiKey) {
        // BLANK COUNTS AS MISSING, and that is the row worth having. An unset environment variable
        // reaches Spring as an empty string rather than as null — so a check that only tested for
        // null would call an empty key "configured" and hand it to the provider as "Bearer ".
        assertThat(with(baseUrl, apiKey).isConfigured()).isFalse();
    }
}
