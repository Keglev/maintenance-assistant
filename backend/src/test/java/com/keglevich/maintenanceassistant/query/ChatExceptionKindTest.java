package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.query.ChatClient.ChatException;
import com.keglevich.maintenanceassistant.query.ChatClient.ChatException.Kind;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.LLAMA;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.clientFor;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.clientForBaseUrl;
import static com.keglevich.maintenanceassistant.query.ChatClientFixtures.prompt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Every way the chat path can fail, and the kind each one reports.
 *
 * <p><b>Why a whole test for one field.</b> The kind exists to be GROUPED BY in a log, and its
 * value is that a reader can trust it without reading the message beside it. A mapping that is
 * right today and drifts the next time a throw site moves is worse than no kind at all, because it
 * groups two different failures under one name and nobody notices — which is exactly the failure
 * the field was added to end. These assertions are what stop that.
 *
 * <p>The messages themselves are asserted in {@link IonosChatClientFailureTest}, deliberately not
 * duplicated here: that file owns the diagnosis, this one owns the classification.
 *
 * <p>SIBLING: IonosChatClientFailureTest, sharing ChatClientFixtures and ProviderStub.
 */
class ChatExceptionKindTest {

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
    @DisplayName("finish_reason=length is TRUNCATED, and carries the anatomy in its message")
    void truncatedAnswer_isTruncated() {
        provider.enqueueJson(200, ChatClientFixtures.truncated());

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.TRUNCATED))
                // The anatomy rides in the message tail as well as in the WARN, so a failure
                // reaching a developer through a stack trace carries the same evidence as one
                // reaching them through a log.
                .hasMessageContaining("refusalShaped=")
                .hasMessageContaining("whitespaceRatio=");
    }

    @Test
    @DisplayName("empty content is EMPTY — the reasoning-model diagnosis, not a transport one")
    void emptyContent_isEmpty() {
        provider.enqueueJson(200, ChatClientFixtures.emptyContent("length"));

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.EMPTY));
    }

    @Test
    @DisplayName("a 4xx that is not 429 is REJECTED, and terminal")
    void rejectedRequest_isRejected() {
        provider.enqueueJson(403, ChatClientFixtures.error("model access denied"));

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
    }

    @Test
    @DisplayName("nothing served after every attempt is TRANSPORT")
    void nothingServed_isTransport() {
        // A port nothing is listening on: the connection is refused, so no response is ever read
        // and the retry loop exhausts. This is the shape a genuine provider outage has, and the
        // one that D1 was reported as while being something else entirely.
        assertThatThrownBy(() -> clientForBaseUrl("http://localhost:1", budget, LLAMA).complete(prompt()))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.TRANSPORT));
    }

    @Test
    @DisplayName("an answer that arrived and cannot be read is UNREADABLE, not TRANSPORT")
    void unreadableResponse_isUnreadable() {
        // The distinction that costs money: an unreadable 200 was served and billed, so it is
        // terminal, while a transport failure was not and is retried. #81 found these two sharing
        // a catch block; the kinds keep them apart in the log as well as in the code.
        provider.enqueue(200, "application/json", "this is not json at all");

        assertThatThrownBy(() -> clientFor(provider, budget, LLAMA).complete(prompt()))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNREADABLE));
    }

    @Test
    @DisplayName("the assembler's own failures are kinded too, so no throw site is unclassified")
    void assemblerFailures_areKinded() {
        assertThat(new ChatException(Kind.UNREADABLE, "x").kind()).isEqualTo(Kind.UNREADABLE);
        assertThat(new ChatException(Kind.EMPTY, "x", new IllegalStateException()).kind())
                .isEqualTo(Kind.EMPTY);
    }
}
