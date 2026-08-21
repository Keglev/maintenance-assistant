package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.query.ChatClient.ChatException;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.LLAMA;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.QWEN;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.clientFor;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.prompt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the chat client does when the answer is unusable, refused, or absent.
 *
 * <p><b>Every message here is a diagnosis, not a report.</b> A person is waiting inside NFR-4's
 * 30 s ceiling, and the two failures that actually happen in this system look like something else:
 * empty content looks like a broken model and is a missing {@code reasoning_effort}, and a
 * truncated answer looks like a shorter answer and is unparseable JSON. Both messages name the
 * cause, and these tests are what keeps them naming it.
 *
 * <p>MESSAGE ASSERTIONS: the service layer, so the exception type and its message are both
 * asserted — the message is the only observable a caller has, and it is what a developer reads at
 * three in the afternoon when the demo stops answering.
 *
 * <p>ONE TEST PINS A FINDING rather than a design; it is named and commented as such. See
 * {@code malformedResponseBody}.
 *
 * <p>OUT OF SCOPE: the happy paths (IonosChatClientTest) and the backoff's timing.
 *
 * <p>SIBLING: IonosChatClientTest, sharing ChatClientFixtures.
 */
class IonosChatClientFailureTest {

    private ProviderStub provider;
    private ChatBudget budget;

    @BeforeEach
    void startProvider() {
        provider = ProviderStub.start();
        budget = mock(ChatBudget.class);
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void complete_emptyContentFromANonReasoningModel_asksWhetherItIsOne() {
        provider.enqueueJson(200, ChatClientFixtures.emptyContent("length"));

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("returned empty content")
                .hasMessageContaining("finish_reason=length")
                // THE QUESTION IS THE FIX. Empty content has one cause in practice — a reasoning
                // model without reasoning_effort "none" burning the whole cap on its reasoning
                // field — and the symptom reads as a truncation bug rather than a configuration
                // one. Asking the question in the message is what shortens that afternoon.
                .hasMessageContaining("is this a reasoning model?");
    }

    @Test
    void complete_emptyContentFromAReasoningModel_omitsTheQuestion() {
        provider.enqueueJson(200, ChatClientFixtures.emptyContent("length"));

        assertThatThrownBy(() -> clientFor(provider, budget, QWEN).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("returned empty content")
                // Already a reasoning model, so the question is answered and asking it would send
                // the reader after a setting that is already correct.
                .hasMessageNotContaining("is this a reasoning model?");
    }

    @Test
    void complete_answerCutOffAtTheCap_failsNamingTheCapAndTheTokens() {
        provider.enqueueJson(200, ChatClientFixtures.truncated());

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                // The answer is JSON, so a truncated one is not shorter, it is unparseable. Failing
                // here names the cap instead of letting a parse error blame the model.
                .hasMessageContaining("answer truncated at the max-tokens cap of 1200")
                .hasMessageContaining("after 1200 completion tokens")
                // A preview, whitespace collapsed: the SHAPE of the whitespace is the diagnosis —
                // a model running away on indentation looks nothing like one that had more to say.
                .hasMessageContaining("Der Fehler E-47 bedeutet");
    }

    @Test
    void complete_noChoicesAtAll_failsAsEmptyContentRatherThanAsANullPointer() {
        provider.enqueueJson(200, ChatClientFixtures.noChoices());

        // The shape a gateway returns when it filtered the request. The typed record answers "none"
        // for the finish reason rather than reaching into an empty list.
        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("returned empty content")
                .hasMessageContaining("finish_reason=none");
    }

    @Test
    void complete_rejectedRequest_failsTerminallyCarryingTheProvidersReason() {
        provider.enqueueJson(403, ChatClientFixtures.error("model access denied"));

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat request rejected: 403")
                .hasMessageContaining("model access denied");

        // One attempt. Measured at a cost of EUR 0.00, because rejected requests are not billed —
        // which is also why the counter stays untouched.
        assertThat(provider.requests()).hasSize(1);
        verifyNoInteractions(budget);
    }

    /**
     * The 401 body — the ingestion side's twin, added rather than flipped.
     *
     * <p>The finding was pinned on the embedding client only, but the defect was never specific to
     * ingestion: it lived in the transport both clients shared. {@code HttpURLConnection}, under
     * the {@code SimpleClientHttpRequestFactory} this client used to build its {@code RestClient}
     * on, discards the error body of a 401, so a revoked key reached the person waiting for an
     * answer as a bare status. On {@code JdkClientHttpRequestFactory} the provider's sentence
     * arrives, and this test holds that half of the swap down the way the flipped ingestion test
     * holds the other.
     */
    @Test
    void complete_unauthorized_failsTerminallyCarryingTheProvidersReason() {
        provider.enqueueJson(401, ChatClientFixtures.error("invalid api key"));

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat request rejected: 401")
                .hasMessageContaining("invalid api key")
                .hasMessageNotContaining("(no body)");

        // Terminal like any other non-429 rejection, and unbilled for the same reason.
        assertThat(provider.requests()).hasSize(1);
        verifyNoInteractions(budget);
    }

    @Test
    void complete_serverError_retriesOnceAndThenFails() {
        provider.enqueueJson(500, ChatClientFixtures.error("upstream unavailable"));
        provider.enqueueJson(500, ChatClientFixtures.error("upstream unavailable"));

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat provider unavailable after 2 attempts");

        // Deliberately less patient than ingestion: a person is watching inside NFR-4's ceiling, so
        // a second attempt is the most that fits and a third would only make a slow failure slower.
        assertThat(provider.requests()).hasSize(2);
    }

    @Test
    void complete_rateLimited_retriesAndAnswers() {
        provider.enqueueJson(429, ChatClientFixtures.error("rate limit exceeded"));
        provider.enqueueJson(200, ChatClientFixtures.answer("ok"));

        var completion = clientFor(provider, budget, LLAMA).complete(prompt());

        assertThat(completion.content()).contains("ok");
        assertThat(provider.requests()).hasSize(2);
        verify(budget).record(1, 812L, 195L);
    }

    @Test
    void complete_malformedResponseBody_isTerminalAndCounted() {
        provider.enqueue(200, "application/json", "{ not json at all ");

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("cannot read the provider response");

        // A RESPONSE THAT ARRIVED WAS SERVED, and the provider bills for serving it. So it counts
        // against the budget even though nothing could be read out of it, and it is NOT retried:
        // the same unreadable answer would be bought a second time, and here a person is waiting
        // for it inside NFR-4's ceiling, so the second purchase costs them the wait as well.
        //
        // Until 2026-08-21 the branch meant to prevent this never ran — Spring wraps a read failure
        // in a plain RestClientException, which landed beside a connection reset. See
        // IonosChatClient#isUnreadableResponse for the measured exception shapes.
        assertThat(provider.requests()).hasSize(1);
        verify(budget).record(1, 0L, 0L);
    }

    @Test
    void complete_bodyThatIsNotJsonAtAll_isTerminalAndCounted() {
        provider.enqueue(200, "text/html", "<html>gateway timeout</html>");

        // What a proxy in front of the provider produces: an HTML page served as 200. No converter
        // can read it, which Spring reports as UnknownContentTypeException rather than through a
        // cause — hence two type checks in the classifier, not one.
        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("cannot read the provider response");
        assertThat(provider.requests()).hasSize(1);
        verify(budget).record(1, 0L, 0L);
    }

    @Test
    void complete_noResponseAtAll_isRetriedAndCountedNowhere() {
        // Nothing is listening: the stub is closed before the call, so the connection is refused.
        String deadBaseUrl = provider.baseUrl();
        provider.close();

        assertThatThrownBy(() -> ChatClientFixtures.clientForBaseUrl(deadBaseUrl, budget, LLAMA)
                .complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat provider unavailable after 2 attempts");

        // THE OTHER HALF OF THE RULE. No response arrived, so nothing was served and nothing is
        // owed — retrying is free of charge and is what a refused connection deserves.
        verifyNoInteractions(budget);
    }
}
