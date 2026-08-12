package com.keglevich.maintenanceassistant.ingestion.seed;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One record of the NDJSON corpus, reduced to what the {@code protocol} table stores.
 *
 * <p>The {@code meta} object of the corpus file is deliberately absent here. It carries the fault
 * taxonomy, the quality marker and the demo tags, which exist to control and verify the corpus
 * distribution — not to be persisted. The V1 schema has no column for any of them and does not get
 * one.
 *
 * @param machineNo redundant with {@code machineId}; used for the file layout and for log messages
 */
record CorpusProtocol(
        UUID id,
        UUID machineId,
        String machineNo,
        LocalDate incidentDate,
        String protocolType,
        String errorCode,
        String title,
        String symptom,
        String cause,
        String action,
        String partsUsed,
        Integer downtimeMinutes,
        String technicianInitials,
        String language,
        String uploadedBy,
        /*
         * Whether this protocol is born reviewed (decision of 2026-08-11).
         *
         * The original 150 are: they are the demo's known-good corpus, and every answer in
         * every walkthrough is grounded in them. The ~15 added in v1.2 are not, deliberately —
         * they exist so the administrator has a real approval queue to work through rather
         * than an empty one.
         *
         * IT LIVES IN THE SEED DATA rather than in the loader, because "is this protocol
         * vouched for" is a property of the protocol, not of the mechanism that inserts it.
         * Absent means false: a record that does not claim to have been reviewed has not been.
         */
        boolean approved) {

    static CorpusProtocol from(JsonNode node) {
        return new CorpusProtocol(
                UUID.fromString(node.get("id").asText()),
                UUID.fromString(node.get("machine_id").asText()),
                node.get("machine_no").asText(),
                LocalDate.parse(node.get("incident_date").asText()),
                node.get("protocol_type").asText(),
                text(node, "error_code"),
                node.get("title").asText(),
                text(node, "symptom"),
                text(node, "cause"),
                text(node, "action"),
                text(node, "parts_used"),
                integer(node, "downtime_minutes"),
                text(node, "technician_initials"),
                node.get("language").asText(),
                node.get("uploaded_by").asText(),
                node.hasNonNull("approved") && node.get("approved").asBoolean());
    }

    /** Nullable string field: absent and JSON {@code null} both mean "not recorded". */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
