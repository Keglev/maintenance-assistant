package com.keglevich.maintenanceassistant.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient.EmbeddingBatch;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.keglevich.maintenanceassistant.ingestion.EmbeddingClientFixtures.clientFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the embedding client sends, and what it makes of what comes back.
 *
 * <p><b>The division of labour, because it is easy to blur.</b> These tests pin what OUR code does
 * with a given provider response, forever, on every CI run. Whether IONOS actually answers that way
 * is the key-gated integration tests' question — and CI never runs those, so every behaviour
 * asserted here was until now verified only on a laptop with an API key.
 *
 * <p>The seam is a real loopback socket rather than {@code MockRestServiceServer}: this client
 * builds its own {@code RestClient} from the static factory, so nothing is injectable to intercept.
 * See {@link ProviderStub} for the reasoning and for what the socket buys.
 *
 * <p>OUT OF SCOPE: the failure paths, which are IonosEmbeddingClientFailureTest, and the retry
 * timing — the backoff sleeps, and asserting on wall-clock would buy a slow test and a flaky one.
 *
 * <p>SIBLING: IonosEmbeddingClientFailureTest, sharing EmbeddingClientFixtures.
 */
class IonosEmbeddingClientTest {

    private static final String MODEL = "BAAI/bge-m3";

    private ProviderStub provider;
    private EmbeddingBudget budget;

    @BeforeEach
    void startProvider() {
        provider = ProviderStub.start();
        // Mocked rather than real: the budget writes to Postgres in its own transaction, and what
        // these tests need from it is the record of what was counted, not the row.
        budget = mock(EmbeddingBudget.class);
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    @Test
    void embed_oneText_postsToTheConfiguredEndpointAndReturnsTheVector() {
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector(MODEL));

        EmbeddingBatch batch = clientFor(provider, budget, MODEL, 2)
                .embed(List.of("Kein Druck an der Presse."));

        // THE SEAM PROOF. A real request was serialised, sent over a socket and answered, and the
        // response bound to the typed records through Boot's own message converters — the half that
        // cannot be taken on trust: Boot 4.1 defaults to Jackson 3 while Jackson 2 is still on the
        // classpath, and this binding failing at runtime is what failed all 150 protocols on the
        // first live corpus run.
        ProviderStub.RecordedRequest request = provider.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/embeddings");
        assertThat(request.headers()).containsEntry("authorization", "Bearer test-key");
        assertThat(batch.vectors()).hasSize(1);
        assertThat(batch.vectors().get(0)).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(batch.providerCalls()).isEqualTo(1);
        assertThat(batch.promptTokens()).isEqualTo(11L);
    }

    @Test
    void embed_anyRequest_sendsEncodingFormatFloatExplicitly() {
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector(MODEL));

        clientFor(provider, budget, MODEL, 2).embed(List.of("Kein Druck."));

        // ADR-002 caveat 1, and the reason this client exists rather than Spring AI's: the gateway
        // answers HTTP 500 — "cannot unmarshal string into Go struct field of type []float32" — for
        // the base64 that OpenAI's own SDKs send by default. It is not a default anywhere, so it
        // has to be on the wire of every request, and this is the only test that can see the wire.
        assertThat(provider.lastRequest().body())
                .contains("\"encoding_format\":\"float\"")
                .contains("\"model\":\"" + MODEL + "\"");
    }

    @Test
    void embed_moreTextsThanBatchSize_splitsIntoOneRequestPerBatch() {
        provider.enqueueJson(200, EmbeddingClientFixtures.vectors(MODEL, 2));
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector(MODEL));

        EmbeddingBatch batch = clientFor(provider, budget, MODEL, 2)
                .embed(List.of("eins", "zwei", "drei"));

        // Batched because the provider bills and rate-limits per REQUEST: one call per chunk would
        // turn a 150-protocol corpus into hundreds of round trips at the same token cost.
        assertThat(provider.requests()).hasSize(2);
        assertThat(provider.requests().get(0).body()).contains("eins").contains("zwei");
        assertThat(provider.requests().get(1).body()).contains("drei").doesNotContain("zwei");
        assertThat(batch.vectors()).hasSize(3);
        assertThat(batch.providerCalls()).isEqualTo(2);
        assertThat(batch.promptTokens()).isEqualTo(51L);
    }

    @Test
    void embed_noTexts_callsTheProviderNotAtAll() {
        EmbeddingBatch batch = clientFor(provider, budget, MODEL, 2).embed(List.of());

        // A protocol that chunked to nothing must not cost a paid call, or a corpus of empty files
        // would be billed for exactly as much as a corpus of real ones.
        assertThat(provider.requests()).isEmpty();
        assertThat(batch.vectors()).isEmpty();
        assertThat(batch.providerCalls()).isZero();
        verifyNoInteractions(budget);
    }

    @Test
    void embed_responseWithoutUsage_reportsZeroTokens() {
        provider.enqueueJson(200, EmbeddingClientFixtures.withoutUsage(MODEL));

        EmbeddingBatch batch = clientFor(provider, budget, MODEL, 2).embed(List.of("eins"));

        // Absent usage costs a metric, not a protocol: the vectors are good and the run goes on.
        assertThat(batch.vectors()).hasSize(1);
        assertThat(batch.promptTokens()).isZero();
    }

    @Test
    void embed_responseWithTotalTokensOnly_fallsBackToThatCount() {
        provider.enqueueJson(200, EmbeddingClientFixtures.totalTokensOnly(MODEL));

        EmbeddingBatch batch = clientFor(provider, budget, MODEL, 2).embed(List.of("eins"));

        // Some models on the gateway send total_tokens and no prompt_tokens. For an embedding call
        // the two are the same number, and taking it is better than reporting a spend of zero.
        assertThat(batch.promptTokens()).isEqualTo(23L);
    }

    @Test
    void embed_anotherModelAnswered_warnsOnceAndEmbedsAnyway() {
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector("BAAI/bge-m3-migration"));
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector("BAAI/bge-m3-migration"));
        ListAppender<ILoggingEvent> log = EmbeddingClientFixtures.captureLog();

        IonosEmbeddingClient client = clientFor(provider, budget, MODEL, 1);
        client.embed(List.of("eins"));
        client.embed(List.of("zwei"));

        // A WARNING, NOT A REFUSAL, and once per process. Two real failures share this shape: the
        // *-migration aliases that silently resolve elsewhere, and a stub reached by pointing
        // LLM_BASE_URL at it — fifteen protocols reached a database that way and were unretrievable
        // for a week, every row healthy and every test green. The client cannot tell which it is
        // looking at, so refusing would turn a provider's rename into an outage; what it can do is
        // make sure the fact is never absent from the log of the run that wrote the rows.
        assertThat(warnings(log)).hasSize(1);
        assertThat(warnings(log).get(0))
                .contains("BAAI/bge-m3-migration")
                .contains("verify-embeddings");
    }

    @Test
    void embed_theRequestedModelAnswered_saysNothing() {
        provider.enqueueJson(200, EmbeddingClientFixtures.oneVector(MODEL));
        ListAppender<ILoggingEvent> log = EmbeddingClientFixtures.captureLog();

        clientFor(provider, budget, MODEL, 1).embed(List.of("eins"));

        // The other half, and the reason the assertion above is not vacuous: an ordinary run is
        // silent, so a warning in a log is evidence rather than noise a reader learns to skip.
        assertThat(warnings(log)).isEmpty();
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> log) {
        return log.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void dimensions_configuredWidth_isWhatTheColumnExpects() {
        assertThat(clientFor(provider, budget, MODEL, 2).dimensions())
                .isEqualTo(EmbeddingClientFixtures.DIMENSIONS);
    }
}
