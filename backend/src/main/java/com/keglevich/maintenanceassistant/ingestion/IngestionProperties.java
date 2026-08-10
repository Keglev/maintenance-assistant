package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingestion behaviour that is policy rather than provider configuration.
 *
 * @param maxUploadBytes       rejected above this size, before the file is written. The second of
 *                             two ceilings: the container refuses a larger multipart part outright
 *                             ({@code spring.servlet.multipart.max-file-size}), and this one bounds
 *                             the DECODED text, which is what actually reaches the embedder
 * @param uploadsPerMinute     uploads one user may submit per minute (NFR-7, burst case)
 * @param workers              threads that index protocols; also the provider concurrency
 * @param queueCapacity        protocols that may wait for a worker before submission is refused
 * @param backlogOnStartup     scan for RECEIVED protocols at startup and index them
 * @param backlogBatchSize     how many the backlog picks up per run
 */
@ConfigurationProperties(prefix = "maintenance.ingestion")
public record IngestionProperties(
        long maxUploadBytes,
        int uploadsPerMinute,
        int workers,
        int queueCapacity,
        boolean backlogOnStartup,
        int backlogBatchSize) {
}
