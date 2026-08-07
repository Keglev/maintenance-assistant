package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingestion behaviour that is policy rather than provider configuration.
 *
 * @param maxUploadBytes    rejected above this size, before the file is written
 * @param workers           threads that index protocols; also the provider concurrency
 * @param queueCapacity     protocols that may wait for a worker before submission is refused
 * @param backlogOnStartup  scan for RECEIVED protocols at startup and index them
 * @param backlogBatchSize  how many the backlog picks up per run
 */
@ConfigurationProperties(prefix = "maintenance.ingestion")
public record IngestionProperties(
        long maxUploadBytes,
        int workers,
        int queueCapacity,
        boolean backlogOnStartup,
        int backlogBatchSize) {
}
