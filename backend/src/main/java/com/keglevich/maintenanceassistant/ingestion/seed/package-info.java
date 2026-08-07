/**
 * Corpus seed — loads the synthetic protocol corpus into an empty environment.
 *
 * <p>It lives under {@code ingestion} because it does what ingestion does: it puts protocols into
 * the database and their documents onto the file volume. It stops where ingestion proper begins —
 * protocols land with status {@code RECEIVED} and are neither chunked nor embedded here.
 *
 * <p>Demo scaffolding, not a production feature: it is disabled unless
 * {@code maintenance.corpus-seed.enabled} is set, and it is idempotent.
 */
package com.keglevich.maintenanceassistant.ingestion.seed;
