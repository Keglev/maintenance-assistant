package com.keglevich.maintenanceassistant.ingestion.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the synthetic corpus seed.
 *
 * <p>Disabled by default on purpose. Seeding is a deliberate act — on a machine that already holds
 * the corpus it is a no-op, but it is still not something a plain application start should do
 * without being asked.
 *
 * @param enabled  whether the seed runs at startup ({@code CORPUS_SEED_ENABLED=true})
 * @param resource Spring resource location of the NDJSON corpus
 */
@ConfigurationProperties(prefix = "maintenance.corpus-seed")
public record CorpusSeedProperties(boolean enabled, String resource) {
}
