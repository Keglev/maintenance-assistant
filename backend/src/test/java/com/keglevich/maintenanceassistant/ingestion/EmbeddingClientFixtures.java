package com.keglevich.maintenanceassistant.ingestion;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Canned embedding-provider responses, in the shape the IONOS gateway really answers with.
 *
 * <p>Fixtures only: no assertions, and nothing here decides what a test claims. The bodies are
 * written out as JSON rather than serialised from the typed records on purpose — a fixture built
 * with the same records the client parses could not fail when those records are wrong, which is
 * exactly the class of defect these tests exist to catch.
 *
 * <p>Consumers: IonosEmbeddingClientTest, IonosEmbeddingClientFailureTest.
 */
final class EmbeddingClientFixtures {

    private EmbeddingClientFixtures() {
    }

    /**
     * Attaches a collecting appender to the client's own logger and returns it.
     *
     * <p>The provenance warning is the whole observable of the model-mismatch rule — it warns and
     * deliberately does not refuse — so the log IS the behaviour under test there, not a side effect.
     */
    static ListAppender<ILoggingEvent> captureLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(IonosEmbeddingClient.class)).addAppender(appender);
        return appender;
    }

    /** A client pointed at the stub, with the small, fast settings every consumer wants. */
    static IonosEmbeddingClient clientFor(ProviderStub provider, EmbeddingBudget budget, String model, int batchSize) {
        return new IonosEmbeddingClient(
                new EmbeddingProperties(
                        provider.baseUrl(), "test-key", model, DIMENSIONS, batchSize,
                        // One retry and a 1 ms backoff: the retry POLICY is what these tests are
                        // about, and its timing would only buy a slow test and a flaky one.
                        1, Duration.ofMillis(1), Duration.ofSeconds(5), 1000),
                budget);
    }

    /** The configured width in these tests. Small, because the assertion is on the check, not the model. */
    static final int DIMENSIONS = 4;

    /** One embedding of the configured width, with usage, answering as the requested model. */
    static String oneVector(String model) {
        return """
                {
                  "object": "list",
                  "model": "%s",
                  "data": [ { "object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3, 0.4] } ],
                  "usage": { "prompt_tokens": 11, "total_tokens": 11 }
                }
                """.formatted(model);
    }

    /** {@code count} embeddings of the configured width — for asserting on batch shape. */
    static String vectors(String model, int count) {
        String items = IntStream.range(0, count)
                .mapToObj(index -> """
                        { "object": "embedding", "index": %d, "embedding": [0.1, 0.2, 0.3, 0.4] }
                        """.formatted(index))
                .collect(Collectors.joining(","));
        return """
                {
                  "object": "list",
                  "model": "%s",
                  "data": [ %s ],
                  "usage": { "prompt_tokens": 40, "total_tokens": 40 }
                }
                """.formatted(model, items);
    }

    /** A vector of the wrong width — the model swap that would otherwise fail as a Postgres error. */
    static String wrongWidthVector(String model) {
        return """
                {
                  "model": "%s",
                  "data": [ { "embedding": [0.1, 0.2] } ],
                  "usage": { "prompt_tokens": 7, "total_tokens": 7 }
                }
                """.formatted(model);
    }

    /** What base64 encoding looks like coming back: a string where an array belongs. */
    static String base64Vector(String model) {
        return """
                {
                  "model": "%s",
                  "data": [ { "embedding": "gK1EPgAAAAA=" } ],
                  "usage": { "prompt_tokens": 7, "total_tokens": 7 }
                }
                """.formatted(model);
    }

    /** Usage absent altogether — it costs a metric, not a protocol. */
    static String withoutUsage(String model) {
        return """
                {
                  "model": "%s",
                  "data": [ { "embedding": [0.1, 0.2, 0.3, 0.4] } ]
                }
                """.formatted(model);
    }

    /** Only total_tokens, which is what the gateway sends on some models. */
    static String totalTokensOnly(String model) {
        return """
                {
                  "model": "%s",
                  "data": [ { "embedding": [0.1, 0.2, 0.3, 0.4] } ],
                  "usage": { "total_tokens": 23 }
                }
                """.formatted(model);
    }

    /** An error body in the provider's own shape, for the message the client surfaces. */
    static String error(String message) {
        return """
                { "error": { "message": "%s", "type": "invalid_request_error" } }
                """.formatted(message);
    }
}
