package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Embedding provider configuration (ADR-002).
 *
 * <p>Every value is a property so that swapping IONOS for the documented Nebius fallback is a
 * configuration change and not a code change — the API is OpenAI-compatible on both sides. The one
 * value that is <em>not</em> free to change is {@link #dimensions}: pgvector encodes dimensionality
 * in the column type, so a model with a different width needs a migration and a re-embedding of the
 * whole corpus. It is a property here only so the mismatch can be detected and reported rather than
 * discovered as a Postgres error halfway through a run.
 *
 * @param baseUrl         OpenAI-compatible endpoint root, including {@code /v1}
 * @param apiKey          bearer token; empty disables ingestion rather than failing at call time
 * @param model           model id, e.g. {@code BAAI/bge-m3}
 * @param dimensions      expected vector width; must match the {@code chunk.embedding} column
 * @param batchSize       texts per HTTP call
 * @param maxRetries      retries after the first attempt, for transient failures only
 * @param retryBackoff    initial backoff, doubled per attempt
 * @param timeout         per-request read timeout
 * @param dailyCallBudget maximum provider calls per day across the whole application (NFR-7)
 */
@ConfigurationProperties(prefix = "maintenance.embedding")
public record EmbeddingProperties(
        String baseUrl,
        String apiKey,
        String model,
        int dimensions,
        int batchSize,
        int maxRetries,
        Duration retryBackoff,
        Duration timeout,
        int dailyCallBudget) {

    /** Ingestion is inert without a key rather than failing per protocol at call time. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
    }
}
