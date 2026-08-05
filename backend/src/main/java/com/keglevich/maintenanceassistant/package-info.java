/**
 * Base package of the maintenance-assistant backend.
 *
 * <p>The application is a modular monolith (ADR-001). Each direct sub-package is one module:
 *
 * <ul>
 *   <li>{@link com.keglevich.maintenanceassistant.ingestion} — protocols in
 *   <li>{@link com.keglevich.maintenanceassistant.query} — answers out
 *   <li>{@link com.keglevich.maintenanceassistant.web} — HTTP boundary and security
 * </ul>
 *
 * <p>Modules communicate through Spring application events rather than direct calls, so that the
 * transport can become Kafka topics in Phase 2 without touching the domain model.
 */
package com.keglevich.maintenanceassistant;
