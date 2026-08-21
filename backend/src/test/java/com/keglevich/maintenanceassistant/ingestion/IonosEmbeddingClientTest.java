package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The embedding client against a stubbed provider, offline and in CI.
 *
 * <p><b>The division of labour, because it is easy to blur.</b> These tests pin what OUR code does
 * with a given provider response, forever, on every run. Whether IONOS actually answers that way is
 * the key-gated integration tests' question, and CI never runs those — so every behaviour asserted
 * here was, until now, verified only on a laptop with an API key.
 *
 * <p>The seam is a real loopback socket rather than {@code MockRestServiceServer}: this client
 * builds its own {@code RestClient} from the static factory, so nothing is injectable to intercept.
 * See {@link ProviderStub} for the full reasoning and for what the socket buys.
 *
 * <p>OUT OF SCOPE: the retry timing itself (the backoff sleeps, and asserting on wall-clock would
 * buy a slow test and a flaky one), and anything about the real provider's behaviour.
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

    private IonosEmbeddingClient client() {
        return client(MODEL, 2);
    }

    private IonosEmbeddingClient client(String model, int batchSize) {
        return new IonosEmbeddingClient(
                new EmbeddingProperties(
                        provider.baseUrl(), "test-key", model,
                        EmbeddingResponses.DIMENSIONS, batchSize,
                        1, Duration.ofMillis(1), Duration.ofSeconds(5), 1000),
                budget);
    }

    @Test
    void embed_oneText_postsToTheConfiguredEndpointAndReturnsTheVector() {
        provider.enqueueJson(200, EmbeddingResponses.oneVector(MODEL));

        EmbeddingClient.EmbeddingBatch batch = client().embed(List.of("Kein Druck an der Presse."));

        // THE SEAM PROOF. A real request was serialised, sent over a socket and answered, and the
        // response bound to the typed records through Boot's own message converters — which is the
        // half that cannot be taken on trust here: Boot 4.1 defaults to Jackson 3 while Jackson 2
        // is still on the classpath, and this binding failing at runtime is what took down the
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
}
