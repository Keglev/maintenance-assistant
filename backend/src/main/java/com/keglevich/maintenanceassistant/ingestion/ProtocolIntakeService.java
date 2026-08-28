package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Accepts a protocol: file to the volume, row to the database, event to the listener.
 *
 * <p>The application-facing entry point of the ingestion module (ADR-001) — the web layer calls
 * this and never reaches past it.
 *
 * <p>It deliberately does no indexing. Upload confirmation is immediate (NFR-4) and everything
 * after the row exists is the asynchronous half.
 */
@Service
public class ProtocolIntakeService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolIntakeService.class);

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final FileStorageProperties fileProperties;
    private final IngestionProperties ingestionProperties;

    ProtocolIntakeService(JdbcClient jdbc, ApplicationEventPublisher events,
                          FileStorageProperties fileProperties, IngestionProperties ingestionProperties) {
        this.jdbc = jdbc;
        this.events = events;
        this.fileProperties = fileProperties;
        this.ingestionProperties = ingestionProperties;
    }

    /**
     * @param machineNo   plant identifier, e.g. {@code PR-03}
     * @param protocolType {@code STOERUNG} or {@code WARTUNG}
     * @param errorCode   optional free-text code
     * @param title       short summary
     * @param language    {@code de} or {@code en}
     * @param content     the document, already decoded as text
     * @param uploadedBy  Keycloak username from the token — no user row exists to point at (ADR-003)
     */
    public record NewProtocol(String machineNo, String protocolType, String errorCode, String title,
                              String language, String content, String uploadedBy) {
    }

    /** Rejected input that is the caller's fault; the web layer maps this to 400. */
    public static class InvalidProtocolException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public InvalidProtocolException(String message) {
            super(message);
        }
    }

    /**
     * Stores the protocol and hands it over for indexing.
     *
     * <p>Transactional so the row and the event commit together; the file is written first and
     * deliberately outside the transaction. If the insert then fails, an orphaned file on the volume
     * is harmless and invisible, whereas a committed row pointing at a file that was never written
     * would be a protocol that can never be indexed.
     *
     * @return the id of the stored protocol, which the caller returns with its 202
     */
    @Transactional
    public UUID accept(NewProtocol submission) {
        String machineNo = required(submission.machineNo(), "machine");
        UUID machineId = resolveMachine(machineNo);
        String type = requireOneOf(submission.protocolType(), "type", "STOERUNG", "WARTUNG");
        String language = detectOrValidateLanguage(submission.language());
        String title = required(submission.title(), "title");
        String content = required(submission.content(), "content");

        if (content.getBytes(StandardCharsets.UTF_8).length > ingestionProperties.maxUploadBytes()) {
            throw new InvalidProtocolException("file exceeds the %d byte limit"
                    .formatted(ingestionProperties.maxUploadBytes()));
        }

        UUID protocolId = UUID.randomUUID();
        String relativePath = "%s/%s.txt".formatted(machineNo, protocolId);
        writeDocument(relativePath, content);

        jdbc.sql("""
                        INSERT INTO protocol (
                            id, machine_id, incident_date, protocol_type, error_code, title,
                            symptom, language, source_file, status, uploaded_by)
                        VALUES (
                            :id, :machineId, :incidentDate, :protocolType, :errorCode, :title,
                            :symptom, :language, :sourceFile, 'RECEIVED', :uploadedBy)
                        """)
                .param("id", protocolId)
                .param("machineId", machineId)
                // The upload carries no incident date of its own yet; the structured Schichtleiter
                // form (Phase 3 UI) supplies one. Today's date is honest for a note typed today.
                .param("incidentDate", LocalDate.now())
                .param("protocolType", type)
                .param("errorCode", blankToNull(submission.errorCode()), java.sql.Types.VARCHAR)
                .param("title", title)
                // Extracted text lands in `symptom` per domain-model.md: an uploaded document is
                // raw observation, not the structured cause/action a form would separate.
                .param("symptom", content)
                .param("language", language)
                .param("sourceFile", relativePath)
                .param("uploadedBy", submission.uploadedBy())
                .update();

        events.publishEvent(new ProtocolReceivedEvent(protocolId));
        log.info("Accepted protocol {} for {} from {} ({} bytes)",
                protocolId, machineNo, submission.uploadedBy(), content.length());
        return protocolId;
    }

    private UUID resolveMachine(String machineNo) {
        return jdbc.sql("SELECT id FROM machine WHERE machine_no = :machineNo")
                .param("machineNo", machineNo)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new InvalidProtocolException("unknown machine: " + machineNo));
    }

    private void writeDocument(String relativePath, String content) {
        Path path = Path.of(fileProperties.basePath()).resolve(relativePath).normalize();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store protocol document " + relativePath, e);
        }
    }

    /**
     * Text only, and checked rather than trusted. The bytes are decoded strictly as UTF-8 by the
     * web layer before they arrive here; a PDF or a scan fails that decode. Extraction from those
     * formats is a documented later-phase item, and silently storing their bytes as "text" would
     * produce a protocol that embeds noise and looks indexed.
     */
    public static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
    }

    private static String detectOrValidateLanguage(String language) {
        if (language == null || language.isBlank()) {
            // Nothing is translated (DECISIONS.txt), so the value only tags what was written.
            // German is the plant's language and the safe default for an unlabelled note.
            return "de";
        }
        return requireOneOf(language, "language", "de", "en");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidProtocolException(field + " is required");
        }
        return value.strip();
    }

    private static String requireOneOf(String value, String field, String... allowed) {
        String candidate = required(value, field);
        for (String option : allowed) {
            if (option.equalsIgnoreCase(candidate)) {
                return option;
            }
        }
        throw new InvalidProtocolException("%s must be one of %s".formatted(field, String.join(", ", allowed)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
