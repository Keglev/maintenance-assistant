package com.keglevich.maintenanceassistant.ingestion.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the original protocol documents live.
 *
 * <p>Per DOMAIN-MODEL.md the files sit on a Docker volume and the database stores only the path
 * ({@code protocol.source_file}); nothing is a BLOB in Postgres. The container overrides this with
 * {@code MAINTENANCE_FILES_PATH=/var/lib/maintenance/files}, which is where the volume is mounted.
 *
 * @param basePath root directory for protocol documents
 */
@ConfigurationProperties(prefix = "maintenance.files")
public record FileStorageProperties(String basePath) {
}
