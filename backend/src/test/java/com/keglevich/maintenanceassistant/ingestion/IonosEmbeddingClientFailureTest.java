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
 * <p><b>TWO TESTS HERE PIN BEHAVIOUR THAT IS A FINDING RATHER THAN A DESIGN.</b> They are named and
 * commented as such: writing them to assert what the code SHOULD do would have produced two red
 * tests and no record of what it actually does. See {@code malformedResponseBody} and
 * {@code unauthorized}.
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
    void embed_malformedResponseBody_isRetriedAndCountedNowhere() {
        provider.enqueue(200, "application/json", "{ this is not json ");
        provider.enqueue(200, "application/json", "{ this is not json ");

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class);

        // FINDING, PINNED AS IT IS RATHER THAN AS IT READS. The client has a terminal branch for
        // exactly this — "cannot read the provider response", counted and never retried, written
        // after the 150-paid-calls incident. A body that will not parse does not reach it: Spring
        // reports it as a plain RestClientException ("Error while extracting response"), not as an
        // HttpMessageConversionException, so it lands in the TRANSIENT catch instead.
        //
        // Two consequences, both the original incident: the served calls are invisible to the
        // budget, and the request is retried, so the same unreadable answer is bought twice.
        assertThat(provider.requests()).hasSize(2);
        verifyNoInteractions(budget);
    }

    @Test
    void embed_base64Vector_failsRatherThanWritingNonsense() {
        provider.enqueueJson(200, EmbeddingClientFixtures.base64Vector(MODEL));
        provider.enqueueJson(200, EmbeddingClientFixtures.base64Vector(MODEL));

        // ADR-002's first caveat arriving anyway: a string where the array belongs. What must never
        // happen is a row of vectors built from it, and that much holds. It travels the same
        // transient path as the malformed body above, for the same reason and with the same finding.
        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class);
        assertThat(provider.requests()).hasSize(2);
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

    @Test
    void embed_unauthorized_namesTheStatusButLosesTheProvidersReason() {
        provider.enqueueJson(401, EmbeddingClientFixtures.error("invalid api key"));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding request rejected: 401")
                // FINDING, PINNED. Every other status carries the provider's sentence through —
                // 403 above proves it — and 401 alone arrives with an empty body, because
                // HttpURLConnection under SimpleClientHttpRequestFactory swallows a 401 body as
                // part of its own authentication handling. So the one 4xx most likely in
                // production, a revoked or mistyped key, is the one whose explanation the operator
                // never sees. Verified by status: 400, 403, 404, 429 and 500 all deliver a body.
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
