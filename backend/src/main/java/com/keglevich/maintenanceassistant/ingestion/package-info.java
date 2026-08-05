/**
 * Ingestion module — everything that turns a submitted protocol into searchable content.
 *
 * <p>Responsibility: accept a protocol from a Schichtleiter (structured form or uploaded
 * PDF/text), store the original file on the volume and its path in the database, extract text,
 * split it into chunks, request embeddings from the EU-hosted provider, persist the chunks, and
 * drive the status lifecycle {@code RECEIVED → INDEXED / FAILED}. Upload confirmation is
 * immediate; the rest runs asynchronously on a Spring application event (NFR-4).
 *
 * <p>This module is the extraction candidate for Phase 2, where the in-process event becomes a
 * Kafka topic (ADR-001). Nothing outside this package may reach into it directly.
 *
 * <p>Empty in Phase 1 — implemented in Phase 2.
 */
package com.keglevich.maintenanceassistant.ingestion;
