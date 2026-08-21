package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.query.ChatClient.Completion;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.LLAMA;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.QWEN;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.clientFor;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.prompt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * What the chat client puts on the wire, and what it makes of a usable answer.
 *
 * <p><b>The division of labour, because it is easy to blur.</b> These tests pin what OUR code does
 * with a given provider response, forever, on every CI run. Whether IONOS actually answers that way
 * is the key-gated integration tests' question — and CI never runs those, so every behaviour
 * asserted here was until now verified only on a laptop with an API key.
 *
 * <p>Three things happen in this client and nowhere else, and all three are only visible on the
 * wire: the NFR-7 output cap, the reasoning-model flag derived from the model id, and the JSON
 * schema that makes an uncited claim unrepresentable. A test that called the client and inspected
 * the answer could not see any of them.
 *
 * <p>OUT OF SCOPE: the failure paths (IonosChatClientFailureTest) and the retry timing.
 *
 * <p>SIBLING: IonosChatClientFailureTest, sharing ChatClientFixtures.
 */
class IonosChatClientTest {

    private ProviderStub provider;
    private ChatBudget budget;

    @BeforeEach
    void startProvider() {
        provider = ProviderStub.start();
        // Mocked rather than real: the budget writes to Postgres in its own transaction, and what
        // these tests need from it is the record of what was counted, not the row.
        budget = mock(ChatBudget.class);
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void complete_answeredPrompt_postsToChatCompletionsAndReturnsTheContent() {
        provider.enqueueJson(200, ChatClientFixtures.answer("{ \\\"answer\\\": \\\"E-47 ist ein Druckabfall.\\\" }"));

        Completion completion = clientFor(provider, budget, LLAMA).complete(prompt());

        // THE SEAM PROOF. Really serialised, really sent, and the response really bound through
        // Boot's message converters — the half that cannot be assumed: Boot 4.1 defaults to
        // Jackson 3 while Jackson 2 is on the classpath, and this binding failing at runtime is
        // what failed all 150 protocols on the first live corpus run.
        ProviderStub.RecordedRequest request = provider.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.headers()).containsEntry("authorization", "Bearer test-key");
        assertThat(completion.content()).contains("Druckabfall");
        assertThat(completion.promptTokens()).isEqualTo(812L);
        assertThat(completion.completionTokens()).isEqualTo(195L);
    }

    @Test
    void complete_anyPrompt_attachesTheOutputCapItself() {
        provider.enqueueJson(200, ChatClientFixtures.answer("ok"));

        clientFor(provider, budget, LLAMA).complete(prompt());

        // NFR-7, on every single call, attached by the CLIENT so that no prompt can forget it. The
        // spike measured that it never binds in practice — the longest answer was 195 tokens — so
        // it costs nothing in quality and is pure runaway protection, the only kind worth having.
        assertThat(provider.lastRequest().body()).contains("\"max_tokens\":1200");
    }

    @Test
    void complete_reasoningModel_sendsReasoningEffortNone() {
        provider.enqueueJson(200, ChatClientFixtures.answer("ok"));

        clientFor(provider, budget, QWEN).complete(prompt());

        // ADR-002 caveat 3, decided from the model id rather than from a flag someone has to
        // remember: without this, a Qwen3.5 spends the entire output budget on its reasoning field
        // and returns EMPTY content with finish_reason "length". The spike measured that
        // enable_thinking:false, reasoning_effort:"low" and "/no_think" all leave it empty — only
        // this works. It is keyed off the model because the trap travels with the model.
        assertThat(provider.lastRequest().body()).contains("\"reasoning_effort\":\"none\"");
    }

    @Test
    void complete_nonReasoningModel_omitsReasoningEffortEntirely() {
        provider.enqueueJson(200, ChatClientFixtures.answer("ok"));

        clientFor(provider, budget, LLAMA).complete(prompt());

        // Absent, not sent as some neutral value: the parameter is not part of the OpenAI-compatible
        // contract, and a gateway that rejects an unknown field would refuse every Llama answer.
        assertThat(provider.lastRequest().body()).doesNotContain("reasoning_effort");
    }

    @Test
    void complete_anyPrompt_constrainsTheAnswerToTheStrictSchema() {
        provider.enqueueJson(200, ChatClientFixtures.answer("ok"));

        clientFor(provider, budget, LLAMA).complete(prompt());

        // ADR-002: an uncited claim is unrepresentable in this schema, which is the layer of the
        // citation rule that does not depend on the model's goodwill. strict=true was accepted
        // first try by every model on both providers in the spike, so there is no fallback path.
        assertThat(provider.lastRequest().body())
                .contains("\"type\":\"json_schema\"")
                .contains("\"strict\":true")
                .contains("\"name\":\"grounded_answer\"");
        assertThat(provider.lastRequest().body())
                .contains("Answer only from the sources.")
                .contains("Was bedeutet E-47");
    }

    @Test
    void complete_answeredPrompt_recordsBothTokenCountsAsServed() {
        provider.enqueueJson(200, ChatClientFixtures.answer("ok"));

        clientFor(provider, budget, LLAMA).complete(prompt());

        // Counted where the provider served it, not where the caller succeeded — the same rule the
        // embedding client follows, and for the same reason: the money is already spent.
        verify(budget).record(1, 812L, 195L);
    }

    @Test
    void complete_answerWithoutUsage_reportsZeroRatherThanFailing() {
        provider.enqueueJson(200, ChatClientFixtures.answerWithoutUsage());

        Completion completion = clientFor(provider, budget, LLAMA).complete(prompt());

        // Absent usage costs a metric, not an answer: the technician still gets their answer.
        assertThat(completion.content()).contains("ok");
        assertThat(completion.promptTokens()).isZero();
        assertThat(completion.completionTokens()).isZero();
    }

    @Test
    void model_configuredModel_isWhatTheAnswerWillBeAttributedTo() {
        assertThat(clientFor(provider, budget, LLAMA).model()).isEqualTo(LLAMA);
    }

    @Test
    void isConfigured_keyAndBaseUrlPresent_saysYes() {
        // The query path answers 503 rather than a wrong answer when this is false, so it is a
        // routing decision and not a diagnostic.
        assertThat(clientFor(provider, budget, LLAMA).isConfigured()).isTrue();
    }
}
