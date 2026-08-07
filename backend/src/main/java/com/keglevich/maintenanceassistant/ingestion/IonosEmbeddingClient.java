package com.keglevich.maintenanceassistant.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding client for the IONOS AI Model Hub, spoken as plain OpenAI-compatible JSON.
 *
 * <p>A {@link RestClient} rather than Spring AI. The decision and its evidence are in
 * {@code spike/spring-ai-boot4/RESULTS.md} and dated in DECISIONS.txt: Spring AI 2.0 does work on
 * Boot 4.1, but it is a large dependency for one POST and it defaults to the exact encoding the
 * IONOS gateway cannot parse.
 *
 * <p>Three provider quirks from ADR-002 are handled here and nowhere else:
 * <ul>
 *   <li>{@code encoding_format} is sent explicitly as {@code float}. The gateway returns HTTP 500
 *       — {@code cannot unmarshal string into Go struct field …of type []float32} — for the base64
 *       that OpenAI's own SDKs send by default.</li>
 *   <li>The model id is taken from configuration verbatim, so the {@code *-migration} aliases in
 *       the IONOS catalogue are simply never named.</li>
 *   <li>The vector width is asserted against configuration on every response.</li>
 * </ul>
 */
@Component
class IonosEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(IonosEmbeddingClient.class);

    private final EmbeddingProperties properties;
    private final RestClient restClient;

    // RestClient.builder() rather than the auto-configured builder bean: this client talks to one
    // external provider with its own timeouts and its own auth header, and should not inherit
    // interceptors or converters added for the application's own HTTP calls.
    IonosEmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        // Generous: a batch of 32 texts through bge-m3 took ~4 s in the spike, and a slow
        // response is worth waiting for rather than retrying into a second charge.
        requestFactory.setReadTimeout((int) properties.timeout().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
        log.info("Embedding provider: {} model={} dims={} batch={} dailyCallBudget={}",
                properties.baseUrl(), properties.model(), properties.dimensions(),
                properties.batchSize(), properties.dailyCallBudget());
    }

    @Override
    public int dimensions() {
        return properties.dimensions();
    }

    @Override
    public EmbeddingBatch embed(List<String> texts) {
        if (texts.isEmpty()) {
            return new EmbeddingBatch(List.of(), 0, 0L);
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        int calls = 0;
        long tokens = 0L;

        // Batched because the provider bills and rate-limits per request, and one request per
        // chunk would turn a 150-protocol corpus into hundreds of round trips.
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + properties.batchSize(), texts.size()));
            JsonNode response = callWithRetry(batch);
            calls++;
            tokens += extractPromptTokens(response);
            vectors.addAll(extractVectors(response, batch.size()));
        }
        return new EmbeddingBatch(vectors, calls, tokens);
    }

    /**
     * Retries transient failures only, with doubling backoff. A 4xx other than 429 is the caller's
     * fault — a bad model id, a revoked key — and retrying it just spends the same error again.
     */
    private JsonNode callWithRetry(List<String> batch) {
        Duration backoff = properties.retryBackoff();
        RuntimeException last = null;

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            if (last != null) {
                log.warn("Embedding call failed ({}), retry {} of {} in {}",
                        last.getMessage(), attempt, properties.maxRetries(), backoff);
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
            try {
                JsonNode body = restClient.post()
                        .uri("/embeddings")
                        .body(Map.of(
                                "model", properties.model(),
                                "input", batch,
                                // Not a default anywhere: the gateway 500s on base64 (ADR-002).
                                "encoding_format", "float"))
                        .retrieve()
                        .body(JsonNode.class);
                if (body == null) {
                    throw new EmbeddingException("provider returned an empty body");
                }
                return body;
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                last = e;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                // Terminal: retrying a 401 or a 404 model id only wastes time.
                throw new EmbeddingException(
                        "embedding request rejected: %s %s".formatted(e.getStatusCode(), firstLine(e.getResponseBodyAsString())), e);
            } catch (RestClientException e) {
                // 5xx, read timeout, connection reset — all worth another attempt.
                last = e;
            }
        }
        throw new EmbeddingException(
                "embedding provider unavailable after %d attempts: %s"
                        .formatted(properties.maxRetries() + 1, last == null ? "unknown" : last.getMessage()), last);
    }

    /**
     * Reads the vectors and checks their width. A model that quietly returns 768 dimensions would
     * otherwise fail as a Postgres type error partway through writing chunks, leaving the protocol
     * half-indexed; failing here keeps the failure atomic and names the actual cause.
     */
    private List<float[]> extractVectors(JsonNode response, int expectedCount) {
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != expectedCount) {
            throw new EmbeddingException(
                    "expected %d embeddings, provider returned %d".formatted(expectedCount, data.size()));
        }
        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (JsonNode item : data) {
            JsonNode values = item.path("embedding");
            if (!values.isArray()) {
                throw new EmbeddingException("embedding is not an array — is encoding_format still float?");
            }
            if (values.size() != properties.dimensions()) {
                throw new EmbeddingException(
                        "model %s returned %d dimensions, the chunk.embedding column is vector(%d)"
                                .formatted(properties.model(), values.size(), properties.dimensions()));
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    /** Absent usage is logged as zero rather than failing: it costs a metric, not a protocol. */
    private static long extractPromptTokens(JsonNode response) {
        JsonNode usage = response.path("usage");
        long tokens = usage.path("prompt_tokens").asLong(0L);
        return tokens > 0 ? tokens : usage.path("total_tokens").asLong(0L);
    }

    private static String firstLine(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        String trimmed = body.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("interrupted while backing off", e);
        }
    }
}
