package com.keglevich.maintenanceassistant.ingestion.seed;

import java.time.format.DateTimeFormatter;

/**
 * Renders a protocol as the plain-text document that is written to the file volume.
 *
 * <p>This is what {@code protocol.source_file} points at. These protocols are the system's own
 * structured records (a Schichtleiter fills the form), so the document is a rendering of those
 * fields — there is no separate "original scan" to preserve. The other path, where an uploaded PDF
 * is extracted into a protocol, belongs to the upload endpoint and is not exercised by the seed.
 *
 * <p>The layout matters beyond looking tidy: this text is what the ingestion module will chunk and
 * embed. Labelled sections in the document's own language give the chunker natural boundaries and
 * keep a chunk self-describing once it is separated from its row.
 */
final class ProtocolDocumentRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private ProtocolDocumentRenderer() {
    }

    static String render(CorpusProtocol p) {
        boolean de = "de".equals(p.language());
        StringBuilder out = new StringBuilder();

        out.append(de ? "WARTUNGSPROTOKOLL" : "MAINTENANCE PROTOCOL").append('\n');
        out.append("=".repeat(de ? 17 : 20)).append("\n\n");

        line(out, de ? "Maschine" : "Machine", p.machineNo());
        line(out, de ? "Datum" : "Date", p.incidentDate().format(DATE));
        line(out, de ? "Art" : "Type", p.protocolType());
        line(out, de ? "Fehlercode" : "Error code", p.errorCode());
        line(out, de ? "Techniker" : "Technician", p.technicianInitials());
        line(out, de ? "Stillstand" : "Downtime",
                p.downtimeMinutes() == null ? null : p.downtimeMinutes() + (de ? " Minuten" : " minutes"));

        out.append('\n').append(p.title()).append("\n\n");

        section(out, de ? "Symptom" : "Symptom", p.symptom());
        section(out, de ? "Ursache" : "Cause", p.cause());
        section(out, de ? "Massnahme" : "Action", p.action());
        section(out, de ? "Ersatzteile" : "Parts used", p.partsUsed());

        return out.toString();
    }

    /** Header line; skipped entirely when the value is absent rather than printed as empty. */
    private static void line(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append(": ").append(value).append('\n');
        }
    }

    private static void section(StringBuilder out, String heading, String body) {
        if (body != null && !body.isBlank()) {
            out.append(heading).append(":\n").append(body).append("\n\n");
        }
    }
}
