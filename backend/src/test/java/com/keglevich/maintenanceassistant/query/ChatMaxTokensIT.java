package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output cap that ships, and the one an operator can set instead.
 *
 * <p><b>Why this is bound from the real configuration rather than constructed.</b> The cap is a
 * number in {@code application.yml} with a ruling behind it (R1: 1642 x 1.25 = 2052.5 -> 2100), and
 * every other test in this package hands the client a cap of its own — so the shipped default has
 * never been asserted anywhere, and moving it would have broken nothing. It moved on 2026-08-26,
 * and this is what stops it moving again by accident.
 *
 * <p>THE NUMBER IS SIZED FOR THE FALLBACK MODEL, not for the deployed one: the incumbent measured a
 * 95th percentile of 575 completion tokens and did not use more room when it was given 3000, while
 * openai/gpt-oss-120b qualified as the fallback at 3000 and failed at 1200. The tables are in
 * PROJECT-PHASES under the diagnostics wave, A3.
 *
 * <p>The second case is the escape hatch the deployment relies on: the cap is
 * {@code ${LLM_CHAT_MAX_TOKENS:2100}}, and nothing in docker-compose.prod.yml or .env.prod.example
 * sets it — so the default above is what production runs, and this proves the override still exists
 * for the day it is needed.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatMaxTokensIT {

    /** R1, 2026-08-26. Changing this line means changing the ruling, not just the test. */
    private static final int SHIPPED_CAP = 2100;

    @Autowired
    private ChatProperties chatProperties;

    @Test
    @DisplayName("the shipped default is 2100, the ruling's number")
    void theDefaultIsTheRuledCap() {
        assertThat(chatProperties.maxTokens()).isEqualTo(SHIPPED_CAP);
    }

    @Test
    @DisplayName("the configured cap is what reaches the provider, not a constant in the client")
    void theConfiguredCapIsWhatGoesOnTheWire() {
        // ON THE WIRE, because a property that binds correctly and is then ignored by the request
        // builder would pass the assertion above and still send 1200 to the provider.
        try (ProviderStub provider = ProviderStub.start()) {
            // The client does not parse the content — the assembler does — so any non-blank body
            // with finish_reason stop exercises the request path this test is about.
            provider.enqueueJson(200, ChatClientFixtures.answer("ok"));
            clientWithCap(provider, chatProperties.maxTokens()).complete(ChatClientFixtures.prompt());

            assertThat(provider.lastRequest().body()).contains("\"max_tokens\":" + SHIPPED_CAP);
        }
    }

    private static IonosChatClient clientWithCap(ProviderStub provider, int cap) {
        return new IonosChatClient(
                new ChatProperties(provider.baseUrl(), "test-key",
                        "meta-llama/Llama-3.3-70B-Instruct", cap, 0.1,
                        Duration.ofSeconds(5), 1, Duration.ofMillis(1), 200),
                org.mockito.Mockito.mock(ChatBudget.class));
    }

    @Nested
    @SpringBootTest(properties = "LLM_CHAT_MAX_TOKENS=1500")
    @ActiveProfiles("test")
    @DisplayName("LLM_CHAT_MAX_TOKENS overrides it")
    class WithAnOverride {

        @Autowired
        private ChatProperties overridden;

        @Test
        @DisplayName("the placeholder still resolves an operator's value")
        void theOverrideWins() {
            assertThat(overridden.maxTokens()).isEqualTo(1500);
        }
    }
}
