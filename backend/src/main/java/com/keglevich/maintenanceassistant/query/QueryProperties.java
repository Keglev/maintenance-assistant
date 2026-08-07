package com.keglevich.maintenanceassistant.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Retrieval and guard configuration for the query path.
 *
 * @param similarityThreshold the NFR-2 Mode A / Mode B switch. A <b>property, never a literal</b>
 *                            (ADR-002): absolute cosine values are specific to the embedding model,
 *                            so any model change invalidates the number and it has to be re-measured
 *                            against the corpus rather than reasoned about
 * @param topK                chunks retrieved per question; also the number of sources the model
 *                            may cite from
 * @param rateLimitPerMinute  questions per user per minute (NFR-7)
 * @param cacheTtl            how long an identical question keeps its answer
 * @param cacheMaxEntries     cache bound, so a spray of distinct questions cannot grow the heap
 */
@ConfigurationProperties(prefix = "maintenance.query")
public record QueryProperties(
        double similarityThreshold,
        int topK,
        int rateLimitPerMinute,
        Duration cacheTtl,
        int cacheMaxEntries) {
}
