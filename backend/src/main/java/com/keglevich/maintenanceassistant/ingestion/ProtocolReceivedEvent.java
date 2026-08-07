package com.keglevich.maintenanceassistant.ingestion;

import java.util.UUID;

/**
 * A protocol has been stored and is waiting to be indexed.
 *
 * <p><b>This is the Kafka seam.</b> ADR-001 chose a modular monolith with Spring application events
 * for asynchrony, and named the extraction of the ingestion service as the Phase 5 evolution. When
 * that happens, this event becomes a message on a topic and {@link IngestionEventListener} becomes
 * a consumer in a separate deployable — the publisher does not change, and neither does the status
 * lifecycle it hands over.
 *
 * <p>That is why the event carries an id and nothing else. Anything richer would be a payload that
 * has to survive serialisation, versioning and a consumer running an older schema; an id means the
 * consumer reads the current row and there is one source of truth either side of the seam.
 */
public record ProtocolReceivedEvent(UUID protocolId) {
}
