package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient.EmbeddingException;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.keglevich.maintenanceassistant.ingestion.EmbeddingClientFixtures.clientFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the embedding client does when the provider answers badly, or not at all.
 *
 * <p><b>The money is the point.</b> A provider that serves a request has been paid for it whatever
 * happens to the response afterwards, so the counter is meant to run where the request was served
 * and not where the caller succeeded. That is not a preference: the first real run of this pipeline
 * made 150 paid calls the budget never saw, because every one of them failed while converting the
 * response and the counter only ran on success.
 *
 * <p>The other half is which failures are worth another attempt. A 4xx that is not a 429 is the
 * caller's own fault — a revoked key, a bad model id — and retrying spends the same error again.
 *
 * <p><b>ONE TEST HERE PINS BEHAVIOUR THAT IS A FINDING RATHER THAN A DESIGN</b>, named and
 * commented as such: writing it to assert what the code SHOULD do would have produced a red test
 * and no record of what it actually does. See {@code malformedResponseBody}. There were two. The
 * other, {@code unauthorized}, pinned a swallowed 401 body until the transport under this client
 * was replaced; it now asserts the fix and carries the decision record.
 *
 * <p>MESSAGE ASSERTIONS: this is the service layer, so the exception type and its message are both
 * asserted. The message is the only observable a caller has here, and it is what lands in the
 * protocol's {@code failure_reason} column for a human to read later.
 *
 * <p>OUT OF SCOPE: the happy paths (IonosEmbeddingClientTest) and the backoff's timing.
 *
 * <p>SIBLING: IonosEmbeddingClientTest, sharing EmbeddingClientFixtures.
 */
class IonosEmbeddingClientFailureTest {

    private static final String MODEL = "BAAI/bge-m3";

    private ProviderStub provider;
    private EmbeddingBudget budget;

    @BeforeEach
    void startProvider() {
        provider = ProviderStub.start();
        budget = mock(EmbeddingBudget.class);
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void embed_vectorOfTheWrongWidth_failsNamingBothWidths() {
        provider.enqueueJson(200, EmbeddingClientFixtures.wrongWidthVector(MODEL));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                // Named here rather than left to Postgres: a model quietly returning a different
                // width would otherwise surface as a type error partway through writing chunks,
                // leaving the protocol half-indexed and the message blaming the column.
                .hasMessageContaining("returned 2 dimensions")
                .hasMessageContaining("vector(4)");
    }

    @Test
    void embed_wrongWidth_stillRecordsTheCallThatWasServed() {
        provider.enqueueJson(200, EmbeddingClientFixtures.wrongWidthVector(MODEL));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class);

        // THE RULE, WORKING. The provider served it and billed for it; the response being unusable
        // afterwards refunds nothing. Counted with the tokens the provider reported, not with zero.
        verify(budget).record(1, 7L);
    }

    @Test
    void embed_fewerVectorsThanTexts_failsNamingBothCounts() {
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector(MODEL));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins", "zwei")))
                .isInstanceOf(EmbeddingException.class)
                // A silently short batch would pair chunk 2's text with chunk 1's vector, and every
                // answer citing it afterwards would be grounded in the wrong paragraph.
                .hasMessageContaining("expected 2 embeddings, provider returned 1");
    }

    @Test
    void embed_malformedResponseBody_isTerminalAndCounted() {
        provider.enqueue(200, "application/json", "{ this is not json ");

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("cannot read the provider response");

        // A RESPONSE THAT ARRIVED WAS SERVED, and the provider bills for serving it. So it counts
        // against the budget even though nothing could be read out of it, and it is NOT retried:
        // the same unreadable answer would simply be bought a second time.
        //
        // This was the 2026-08 incident — 150 paid calls the budget never saw — and until
        // 2026-08-21 the branch meant to prevent it never ran, because Spring wraps a read failure
        // in a plain RestClientException that landed beside a connection reset. See
        // IonosEmbeddingClient#isUnreadableResponse for the measured exception shapes.
        assertThat(provider.requests()).hasSize(1);
        verify(budget).record(1, 0L);
    }

    @Test
    void embed_base64Vector_isTerminalRatherThanWritingNonsense() {
        provider.enqueueJson(200, EmbeddingClientFixtures.base64Vector(MODEL));

        // ADR-002's first caveat arriving anyway: a string where the array belongs. Valid JSON of
        // the wrong shape, so it fails on binding rather than on parsing — a different cause, the
        // same rule. What must never happen is a row of vectors built from it.
        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("cannot read the provider response");
        assertThat(provider.requests()).hasSize(1);
        verify(budget).record(1, 0L);
    }

    @Test
    void embed_bodyThatIsNotJsonAtAll_isTerminalAndCounted() {
        provider.enqueue(200, "text/html", "<html>gateway timeout</html>");

        // The third measured shape, and the one a proxy in front of the provider produces: an HTML
        // page served as 200. No converter can read it, which Spring reports as
        // UnknownContentTypeException rather than through a cause — hence two type checks, not one.
        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("cannot read the provider response");
        assertThat(provider.requests()).hasSize(1);
        verify(budget).record(1, 0L);
    }

    @Test
    void embed_noResponseAtAll_isRetriedAndCountedNowhere() {
        // Nothing is listening: the stub is closed before the call, so the connection is refused.
        String deadBaseUrl = provider.baseUrl();
        provider.close();

        assertThatThrownBy(() -> EmbeddingClientFixtures.clientForBaseUrl(deadBaseUrl, budget, MODEL)
                .embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding provider unavailable after 2 attempts");

        // THE OTHER HALF OF THE RULE, and the reason the tests above are not simply "never retry".
        // No response arrived, so nothing was served and nothing is owed — retrying is free of
        // charge and is exactly what a reset connection deserves.
        verifyNoInteractions(budget);
    }

    @Test
    void embed_rejectedRequest_failsTerminallyCarryingTheProvidersReason() {
        provider.enqueueJson(403, EmbeddingClientFixtures.error("model access denied"));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding request rejected: 403")
                // The provider's own sentence, which is what makes failure_reason worth reading.
                .hasMessageContaining("model access denied");

        // One attempt: a denied model is not transient. A rejected request is not billed either,
        // so the counter stays untouched.
        assertThat(provider.requests()).hasSize(1);
        verifyNoInteractions(budget);
    }

    /**
     * The 401 body, and why this test used to assert the opposite.
     *
     * <p>A revoked or mistyped key is the 4xx most likely to happen in production, and the one the
     * ops runbook is written around. Until this transport swap it was also the ONLY status whose
     * explanation never reached {@code failure_reason}: measured by status against this same stub,
     * 400, 403, 404, 429 and 500 all delivered a body and 401 alone arrived empty, because
     * {@code HttpURLConnection} — under the {@code SimpleClientHttpRequestFactory} both clients
     * used to build their {@code RestClient} on — discards the error body of a 401 as part of its
     * own authentication handling. The finding was pinned as {@code "(no body)"} rather than fixed,
     * so the defect could not quietly change shape while it waited.
     *
     * <p>It no longer holds. On {@code JdkClientHttpRequestFactory} (java.net.http) the provider's
     * own sentence arrives, so the assertion is inverted: the operator now reads
     * <em>invalid api key</em> instead of <em>(no body)</em>. That inversion is the whole point of
     * the swap, which makes this test its vacuity guard — revert the factory in either client and
     * this is the assertion that fails.
     */
    @Test
    void embed_unauthorized_failsTerminallyCarryingTheProvidersReason() {
        provider.enqueueJson(401, EmbeddingClientFixtures.error("invalid api key"));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding request rejected: 401")
                // The provider's own sentence, on the one rejection that used to lose it.
                .hasMessageContaining("invalid api key")
                .hasMessageNotContaining("(no body)");

        assertThat(provider.requests()).hasSize(1);
        verifyNoInteractions(budget);
    }

    /**
     * A rejection that genuinely carries no body still names its status.
     *
     * <p>Added with the transport swap, and not padding: {@code "(no body)"} used to be reached
     * only by the swallowed 401 above, so fixing that defect left the fallback with nothing
     * exercising it. It still has work to do — a gateway in front of the provider can reject with
     * an empty body of its own — and the message must stay readable when it does, rather than
     * trailing off after the status code.
     */
    @Test
    void embed_rejectionWithNoBodyAtAll_stillNamesTheStatus() {
        provider.enqueue(400, "application/json", "");

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding request rejected: 400")
                .hasMessageContaining("(no body)");

        assertThat(provider.requests()).hasSize(1);
        verifyNoInteractions(budget);
    }

    @Test
    void embed_serverError_retriesAndThenFailsNamingTheAttempts() {
        provider.enqueueJson(500, EmbeddingClientFixtures.error("upstream unavailable"));
        provider.enqueueJson(500, EmbeddingClientFixtures.error("upstream unavailable"));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding provider unavailable after 2 attempts");

        assertThat(provider.requests()).hasSize(2);
    }

    @Test
    void embed_rateLimited_retriesAndSucceeds() {
        provider.enqueueJson(429, EmbeddingClientFixtures.error("rate limit exceeded"));
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector(MODEL));

        var batch = clientFor(provider, budget, MODEL, 2).embed(List.of("eins"));

        // 429 is the one 4xx worth waiting out — it says "later", not "no".
        assertThat(provider.requests()).hasSize(2);
        assertThat(batch.vectors()).hasSize(1);
        // The refused attempt was not served, so only the successful one is counted.
        verify(budget, times(1)).record(1, 11L);
    }
}
