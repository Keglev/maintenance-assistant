package com.keglevich.maintenanceassistant.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
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
    private final EmbeddingBudget budget;
    private final RestClient restClient;

    /** Latched, so a mismatch is stated once per process rather than once per batch. */
    private volatile boolean modelMismatchUnreported = true;

    // RestClient.builder() rather than the auto-configured builder bean: this client talks to one
    // external provider with its own timeouts and its own auth header, and should not inherit
    // interceptors or converters added for the application's own HTTP calls.
    IonosEmbeddingClient(EmbeddingProperties properties, EmbeddingBudget budget) {
        this.properties = properties;
        this.budget = budget;

        // java.net.http rather than HttpURLConnection. Not a modernisation: HttpURLConnection
        // discards the response body of a 401 as part of its own authentication handling, so a
        // revoked or mistyped key — the 4xx most likely in production — was the ONE failure whose
        // provider explanation never reached failure_reason. Measured, by status, against the
        // stubbed provider: 400, 403, 404, 429 and 500 all delivered a body; 401 alone did not.
        //
        // The timeout contract is unchanged, only split across two objects, because
        // JdkClientHttpRequestFactory has no setConnectTimeout: connect belongs to the HttpClient
        // (Duration), read stays on the factory. Verified against spring-web 7.0.8 on the
        // classpath, not read off documentation.
        //
        // The one thing this costs, measured the same way: a refused connection now arrives as
        // ConnectException with a null message (java.net.http, cause ClosedChannelException) where
        // HttpURLConnection said "Connection refused". Same Spring type — ResourceAccessException —
        // so the retry rule and the budget rule are untouched; only the sentence is poorer. A read
        // timeout keeps its message (HttpTimeoutException, "Request cancelled").
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // Generous: a batch of 32 texts through bge-m3 took ~4 s in the spike, and a slow
        // response is worth waiting for rather than retrying into a second charge.
        requestFactory.setReadTimeout(properties.timeout());

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
            EmbeddingResponse response = callWithRetry(batch);
            warnIfAnotherModelAnswered(response);
            calls++;
            tokens += response.promptTokens();
            vectors.addAll(extractVectors(response, batch.size()));
        }
        return new EmbeddingBatch(vectors, calls, tokens);
    }

    /**
     * Retries transient failures only, with doubling backoff. A 4xx other than 429 is the caller's
     * fault — a bad model id, a revoked key — and retrying it just spends the same error again.
     */
    private EmbeddingResponse callWithRetry(List<String> batch) {
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
                EmbeddingResponse body = restClient.post()
                        .uri("/embeddings")
                        .body(Map.of(
                                "model", properties.model(),
                                "input", batch,
                                // Not a default anywhere: the gateway 500s on base64 (ADR-002).
                                "encoding_format", "float"))
                        .retrieve()
                        .body(EmbeddingResponse.class);
                // Counted here, not by the caller. The money is spent when the provider serves the
                // request, whatever happens to the response afterwards. Recording it upstream is
                // how the first real run of this pipeline made 150 paid calls that the budget
                // never saw: every one of them failed while converting the response, and the
                // counter only ran on success.
                recordUsage(body == null ? 0L : body.promptTokens());
                if (body == null) {
                    throw new EmbeddingException("provider returned an empty body");
                }
                return body;
            } catch (org.springframework.http.converter.HttpMessageConversionException e) {
                // The provider answered and billed for it; we simply could not read the answer.
                // Terminal, because retrying produces the same unreadable response.
                recordUsage(0L);
                throw new EmbeddingException("cannot read the provider response: " + e.getMessage(), e);
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                last = e;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                // Terminal: retrying a 401 or a 404 model id only wastes time.
                throw new EmbeddingException(
                        "embedding request rejected: %s %s".formatted(e.getStatusCode(), firstLine(e.getResponseBodyAsString())), e);
            } catch (RestClientException e) {
                if (isUnreadableResponse(e)) {
                    recordUsage(0L);
                    throw new EmbeddingException("cannot read the provider response: " + e.getMessage(), e);
                }
                // No response arrived — connect refused, read timeout, connection reset. Nothing
                // was served, so nothing is counted, and another attempt is worth making.
                last = e;
            }
        }
        throw new EmbeddingException(
                "embedding provider unavailable after %d attempts: %s"
                        .formatted(properties.maxRetries() + 1, last == null ? "unknown" : last.getMessage()), last);
    }

    /**
     * Whether a {@link RestClientException} means "the answer arrived and could not be read".
     *
     * <p><b>Why this is not simply a {@code catch} of the conversion exception.</b> It was, and the
     * branch never ran. Spring's {@code RestClient} wraps a failure to read the body in a plain
     * {@code RestClientException} — "Error while extracting response for type […]" — so an
     * unreadable 200 landed in the transient catch beside a connection reset. The response was
     * therefore retried, buying the same unreadable answer a second time, and never counted. That
     * is the 2026-08 incident this code was written to prevent, alive inside the fix for it:
     * 150 paid calls that the daily budget never saw.
     *
     * <p><b>Measured, not read off documentation</b> (2026-08-21, against a stubbed provider):
     * <ul>
     *   <li>a body that is not JSON → cause {@code HttpMessageNotReadableException} → {@code StreamReadException}</li>
     *   <li>JSON of the wrong shape, e.g. base64 where the array belongs → cause
     *       {@code HttpMessageNotReadableException} → {@code MismatchedInputException}</li>
     *   <li>a body that is not JSON at all, e.g. an HTML gateway page → {@code UnknownContentTypeException}</li>
     * </ul>
     *
     * <p>Both are matched by TYPE rather than by the message text, so a reworded Spring message
     * cannot silently turn this back off. {@code HttpMessageNotReadableException} is itself an
     * {@code HttpMessageConversionException}, which is why the cause check is the narrow one.
     */
    private static boolean isUnreadableResponse(RestClientException e) {
        return e instanceof org.springframework.web.client.UnknownContentTypeException
                || e.getCause() instanceof org.springframework.http.converter.HttpMessageConversionException;
    }

    /**
     * Reads the vectors and checks their width. A model that quietly returns 768 dimensions would
     * otherwise fail as a Postgres type error partway through writing chunks, leaving the protocol
     * half-indexed; failing here keeps the failure atomic and names the actual cause.
     */
    private List<float[]> extractVectors(EmbeddingResponse response, int expectedCount) {
        List<EmbeddingResponse.Item> data = response.data();
        if (data == null || data.size() != expectedCount) {
            throw new EmbeddingException("expected %d embeddings, provider returned %d"
                    .formatted(expectedCount, data == null ? 0 : data.size()));
        }
        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (EmbeddingResponse.Item item : data) {
            List<Double> values = item.embedding();
            if (values == null || values.isEmpty()) {
                throw new EmbeddingException("embedding is not an array — is encoding_format still float?");
            }
            if (values.size() != properties.dimensions()) {
                throw new EmbeddingException(
                        "model %s returned %d dimensions, the chunk.embedding column is vector(%d)"
                                .formatted(properties.model(), values.size(), properties.dimensions()));
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i).floatValue();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    /**
     * The provider's response, typed rather than navigated as a tree.
     *
     * <p>Boot 4.1 ships <b>Jackson 3</b> ({@code tools.jackson.databind}) as the message-converter
     * default while Jackson 2 is still on the classpath through other libraries. Asking a converter
     * for a {@code com.fasterxml.jackson.databind.JsonNode} therefore fails at runtime with a type
     * definition error — found by running this against IONOS, where all 150 protocols failed on it.
     * Records sidestep the question: they bind under either generation. The annotations package is
     * shared by both, so {@code @JsonIgnoreProperties} is safe here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data, Usage usage, String model) {

        /** Provider field {@code data[]}: {@code embedding} is the vector written to {@code chunk.embedding}. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(List<Double> embedding) {
        }

        /** Provider field {@code usage}: {@code prompt_tokens} feeds {@link EmbeddingBudget#record}, which bills per call. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(Long prompt_tokens, Long total_tokens) {
        }

        /** Absent usage counts as zero: it costs a metric, not a protocol. */
        long promptTokens() {
            if (usage == null) {
                return 0L;
            }
            if (usage.prompt_tokens() != null && usage.prompt_tokens() > 0) {
                return usage.prompt_tokens();
            }
            return usage.total_tokens() == null ? 0L : usage.total_tokens();
        }
    }

    /**
     * Says so, once, when the answer did not come from the model that was asked for.
     *
     * <p>Two real failures share this shape and neither announces itself any other way.
     *
     * <ul>
     *   <li><b>The IONOS {@code *-migration} aliases</b> (ADR-002 caveat): the catalogue carries ids
     *       that silently resolve to a different model. The vectors come back the right width and
     *       are simply not in the space the stored index is in.</li>
     *   <li><b>A stub answering instead of the provider.</b> The e2e provider stub is reached by
     *       pointing {@code LLM_BASE_URL} at it, which is a supported configuration change and
     *       therefore leaves no other trace. Fifteen protocols reached the development database that
     *       way and were unretrievable for a week — every row healthy, every test green (ADR-008).</li>
     * </ul>
     *
     * <p>A warning rather than a refusal: this client cannot know which of the two it is looking at,
     * and refusing to embed would turn a provider's harmless rename into an outage. What it can do
     * is make sure the fact is never absent from the log of the run that wrote the rows.
     *
     * <p>Once per process, because this is called per batch and a corpus seeding run would otherwise
     * print it 6 times and a re-index of everything hundreds.
     */
    private void warnIfAnotherModelAnswered(EmbeddingResponse response) {
        String answered = response.model();
        if (answered == null || answered.equals(properties.model()) || !modelMismatchUnreported) {
            return;
        }
        modelMismatchUnreported = false;
        log.warn("Embedding provider answered as model '{}' but '{}' was requested at {}. "
                        + "Vectors written now may not be comparable with vectors already stored — "
                        + "verify with maintenance.ops.verify-embeddings before trusting retrieval.",
                answered, properties.model(), properties.baseUrl());
    }

    /** One provider request, counted whether or not its response could be used. */
    private void recordUsage(long promptTokens) {
        budget.record(1, promptTokens);
    }

    private static String firstLine(String body) {
        // COVERAGE WAIVER (2026-08-22, register in docs/REFACTOR-STANDARDS.txt; raised as F3 in
        // #81), identical to the chat client's. The null side never runs: the only argument ever
        // passed is getResponseBodyAsString(), which builds a String over a byte array that
        // defaults to empty, so it returns "" and never null. The blank side IS covered, by the
        // empty-body rejection test added with #80. The check stays because this helper runs while
        // a failure_reason is being built, and that is the worst possible place to throw.
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
