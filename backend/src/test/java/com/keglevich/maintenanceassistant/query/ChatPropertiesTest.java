package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether answering is configured at all, and whether the configured model is a reasoning model.
 *
 * <p>Two one-line methods that each decide something a user sees. The first is the difference
 * between a deployment that is INERT and one that fails per question at call time: without a key,
 * every question would be accepted, sent and answered with a provider error. The second is ADR-002
 * caveat 3 — a Qwen3.5 model without {@code reasoning_effort="none"} spends the whole output budget
 * on its reasoning field and returns empty content with {@code finish_reason: "length"}, which reads
 * as a truncation bug and is a configuration one.
 *
 * <p>Parameterised rather than a dozen near-identical tests: the table IS the specification here,
 * and a reader checking whether blank counts the same as null should see both rows side by side.
 *
 * <p>SIBLING: EmbeddingPropertiesTest, which does the same job for {@code maintenance.embedding.*}.
 */
class ChatPropertiesTest {

    private static ChatProperties with(String baseUrl, String apiKey) {
        return withModel(baseUrl, apiKey, "meta-llama/Llama-3.3-70B-Instruct");
    }

    private static ChatProperties withModel(String baseUrl, String apiKey, String model) {
        return new ChatProperties(baseUrl, apiKey, model, 1200, 0.2,
                Duration.ofSeconds(20), 1, Duration.ofMillis(500), 200);
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
        // null would call an empty key "configured" and send the provider a bare "Bearer ".
        assertThat(with(baseUrl, apiKey).isConfigured()).isFalse();
    }

    @ParameterizedTest(name = "model={0}")
    @ValueSource(strings = {
            "Qwen/Qwen3.5-9B-Instruct",
            "Qwen/Qwen3.5-32B-Instruct",
            // Case-insensitive on purpose: the id is copied out of a catalogue by hand, and a
            // capital letter deciding whether the output budget survives would be a trap.
            "qwen/QWEN3.5-9b-instruct",
    })
    void isReasoningModel_aQwen35Model_saysYes(String model) {
        assertThat(withModel("https://api/v1", "secret", model).isReasoningModel()).isTrue();
    }

    @ParameterizedTest(name = "model={0}")
    @CsvSource(value = {
            "meta-llama/Llama-3.3-70B-Instruct",
            // A different Qwen generation is NOT the trap: keying off the family rather than the
            // vendor is what keeps reasoning_effort off a model that would reject it.
            "Qwen/Qwen2.5-72B-Instruct",
            "NULL",
    }, nullValues = "NULL")
    void isReasoningModel_anythingElse_saysNo(String model) {
        // A null model is a misconfiguration, and it has to answer the question rather than throw:
        // this is read while the client's own log line is being built, before anything is sent.
        assertThat(withModel("https://api/v1", "secret", model).isReasoningModel()).isFalse();
    }
}
