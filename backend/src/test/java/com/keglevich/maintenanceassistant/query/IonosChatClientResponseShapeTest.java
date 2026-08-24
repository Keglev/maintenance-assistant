package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.query.ChatClient.ChatException;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.LLAMA;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.clientFor;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.prompt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Response shapes the typed records have to survive, and the sentences the client builds from them.
 *
 * <p>The client parses the provider's answer into records rather than navigating a tree, which means
 * every "absent" case is a null somewhere in a nested record: no choices, a choice with no message,
 * a usage block naming neither token count. Each of those has a defined answer in the code, and each
 * of them is a shape a gateway in front of the provider really produces — so they are tested here
 * rather than left to the reader of a ternary.
 *
 * <p>The message-shortening helpers are here for the same reason. A provider error body is attacker-
 * adjacent text that lands in a log and in {@code failure_reason}: the client cuts it to one line and
 * caps it, and a regression there is how one bad gateway response fills a log file.
 *
 * <p>MESSAGE ASSERTIONS: service layer, so type and message both — the sentence is the only
 * observable a caller has.
 *
 * <p>OUT OF SCOPE: the retry policy and the budget rule (IonosChatClientFailureTest) and the happy
 * paths (IonosChatClientTest).
 *
 * <p>SIBLINGS: IonosChatClientTest and IonosChatClientFailureTest, sharing ChatClientFixtures.
 */
class IonosChatClientResponseShapeTest {

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

    // ---------------------------------------------------------------------------------------
    // Absent pieces of the response
    // ---------------------------------------------------------------------------------------

    @Test
    void complete_bodyThatIsTheJsonNullLiteral_failsAsAnEmptyBodyAndIsStillCounted() {
        provider.enqueueJson(200, "null");

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("provider returned an empty body");

        // Served, so counted — with zeros, because there is no usage block to read. The rule is
        // that the money is spent when the provider answers, whatever the answer turned out to be.
        assertThat(provider.requests()).hasSize(1);
        verify(budget).record(1, 0L, 0L);
    }

    @Test
    void complete_choicesPresentAsJsonNull_failsAsEmptyContentNamingNoFinishReason() {
        provider.enqueueJson(200, ChatClientFixtures.nullChoices());

        // Distinct from an empty array: a null field skips the isEmpty() check entirely, and a
        // records-based parser that got this wrong would throw a NullPointerException at the
        // reader instead of naming what happened.
        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("returned empty content")
                .hasMessageContaining("finish_reason=none");
    }

    @Test
    void complete_choiceCarryingNoMessage_failsAsEmptyContentRatherThanANullPointer() {
        provider.enqueueJson(200, ChatClientFixtures.choiceWithoutMessage());

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("returned empty content")
                // The finish reason survives even though the message did not, which is what tells
                // a reader the gateway truncated the choice rather than the model saying nothing.
                .hasMessageContaining("finish_reason=stop");
    }

    @Test
    void complete_usageBlockNamingNeitherTokenCount_countsZerosRatherThanFailing() {
        provider.enqueueJson(200, ChatClientFixtures.answerWithEmptyUsage("{ \\\"answer\\\": \\\"ok\\\" }"));

        var completion = clientFor(provider, budget, LLAMA).complete(prompt());

        assertThat(completion.content()).contains("ok");
        // Absent usage costs a metric, not an answer: the call is still counted, at zero tokens,
        // because refusing to answer over a missing counter would be the metric breaking the app.
        verify(budget).record(1, 0L, 0L);
    }

    // ---------------------------------------------------------------------------------------
    // The sentences built from a provider error body
    // ---------------------------------------------------------------------------------------

    @Test
    void complete_rejectionBodyOnASingleLine_isCarriedWhole() {
        provider.enqueueJson(400, ChatClientFixtures.errorOnOneLine("model id is not valid"));

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat request rejected: 400")
                .hasMessageContaining("model id is not valid");

        verifyNoInteractions(budget);
    }

    @Test
    void complete_rejectionBodySpanningLines_carriesOnlyTheFirstLine() {
        provider.enqueueJson(400, "{ \"error\": \"first line\" }\nsecond line\nthird line");

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("first line")
                // One line, because this sentence goes in a log and in failure_reason, and a
                // multi-line HTML gateway page pasted into either is unreadable in both.
                .hasMessageNotContaining("second line");
    }

    @Test
    void complete_rejectionBodyWithAVeryLongFirstLine_isCappedWithAnEllipsis() {
        provider.enqueueJson(400, ChatClientFixtures.errorWithAVeryLongFirstLine());

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat request rejected: 400")
                // Capped rather than trusted: the body is the provider's text, and one runaway
                // response should not decide how big a log line gets.
                .hasMessageContaining("…")
                .satisfies(thrown -> assertThat(thrown.getMessage().length()).isLessThan(400));
    }

    @Test
    void complete_rejectionWithNoBodyAtAll_stillNamesTheStatus() {
        provider.enqueue(400, "application/json", "");

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat request rejected: 400")
                // A gateway in front of the provider can reject with nothing at all, and the
                // message has to stay readable instead of trailing off after the status code.
                .hasMessageContaining("(no body)");
    }

    @Test
    void complete_answerCutOffAfterALongRun_previewsOnlyTheStartOfIt() {
        provider.enqueueJson(200, ChatClientFixtures.truncatedLongAnswer());

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("answer truncated at the max-tokens cap of 1200")
                // The preview is a diagnosis, not the answer: enough to see what the model was
                // doing when it ran out, capped so the whole runaway body stays out of the log.
                .hasMessageContaining("…")
                .hasMessageContaining("Der Fehler E-47 bedeutet");
    }

    // ---------------------------------------------------------------------------------------
    // Configuration edges
    // ---------------------------------------------------------------------------------------

    @Test
    void isConfigured_withoutABaseUrl_isFalseAndTheClientStillBuilds() {
        // Answering is inert without configuration rather than failing per question at call time,
        // so the bean has to construct even with nothing to talk to — the RestClient's base URL
        // becomes empty rather than null, which is what keeps the builder from throwing here.
        IonosChatClient client = ChatClientFixtures.clientWithoutBaseUrl(budget);

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.model()).isEqualTo(LLAMA);
    }

    @Test
    void complete_withANegativeRetryCount_makesNoCallAndSaysTheCauseIsUnknown() {
        // A misconfiguration, not a supported setting: maxRetries of -1 makes the attempt loop
        // never run, so there is no attempt and no exception to name. Tested because the message
        // is the only thing that reaches an operator, and "unknown" beats a null in a log — and
        // because a guard that silently spends nothing looks identical to a provider outage.
        assertThatThrownBy(() -> ChatClientFixtures.clientWithRetries(provider, budget, -1)
                .complete(prompt()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("chat provider unavailable after 0 attempts")
                .hasMessageContaining("unknown");

        assertThat(provider.requests()).isEmpty();
        verifyNoInteractions(budget);
    }
}
