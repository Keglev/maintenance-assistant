package com.keglevich.maintenanceassistant.ingestion;

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
 * <p>Consumers: IonosEmbeddingClientTest.
 */
final class EmbeddingResponses {

    private EmbeddingResponses() {
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
