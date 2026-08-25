package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * In-place correction of a protocol, with a re-index that is not optional.
 *
 * <p><b>Why this exists at all.</b> ADR-006 originally refused editing, on the ground that an answer
 * cites a protocol as it stood when the answer was produced. Its 2026-08-10 revision reversed that:
 * the system stores no answers. Every answer is generated per query and every citation resolves live,
 * so the population of answers that could point at changed text is not the corpus of everything ever
 * said — it is whatever is open on a screen right now. What tipped it is the plant: a wrong torque
 * figure should be corrected by the person who spots it, and the old path (delete, tell the
 * Schichtleiter, have them retype the whole protocol) is expensive enough that the wrong figure
 * stays instead.
 *
 * <p><b>Re-indexing is a condition of the flow, not a step in it.</b> An edit that rewrote the file
 * and left the old chunks would be strictly worse than no edit: retrieval would keep matching the
 * old text while the document read correctly, and the citation would point at a protocol that no
 * longer says what was matched. So the status goes back to {@code RECEIVED} and the same event the
 * upload path publishes is published here — the indexer deletes and rewrites the chunks, and the
 * vector the search uses is always the vector of the text on screen.
 *
 * <p><b>Machine and protocol type are not editable.</b> Title, fault code and text are words about
 * an event; machine identity is the protocol's provenance — what retrieval filters on and what the
 * citation names. Re-attributing a defect to another machine is not a correction, it is a different
 * protocol wearing this one's history. Words can be fixed, identity cannot.
 */
@Service
public class ProtocolEditService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolEditService.class);

    /** A caller trying to move a protocol to another machine or reclassify it. */
    public static final String IDENTITY_LOCKED = "PROTOCOL_IDENTITY_LOCKED";
    /** A caller trying to edit something that is already in the archive. */
    public static final String PROTOCOL_ARCHIVED = "PROTOCOL_ARCHIVED";

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final ProtocolModerationService moderation;
    private final FileStorageProperties fileProperties;
    private final IngestionProperties ingestionProperties;

    ProtocolEditService(JdbcClient jdbc, ApplicationEventPublisher events,
                        ProtocolModerationService moderation, FileStorageProperties fileProperties,
                        IngestionProperties ingestionProperties) {
        this.jdbc = jdbc;
        this.events = events;
        this.moderation = moderation;
        this.fileProperties = fileProperties;
        this.ingestionProperties = ingestionProperties;
    }

    /**
     * What an administrator submitted.
     *
     * @param machineNo     echoed back by the client and checked, not applied — see
     *                      {@link #IDENTITY_LOCKED}. Null means "not sent", which is accepted.
     * @param protocolType  same treatment as {@code machineNo}
     * @param content       the full corrected text, replacing the stored document
     * @param comment       why, required and non-blank
     */
    public record Correction(String machineNo, String protocolType, String title, String errorCode,
                             String content, String comment) {
    }

    /**
     * Applies a correction and queues the protocol for re-indexing.
     *
     * <p>Transactional, and the file is rewritten inside it. That is the opposite trade from
     * {@link ProtocolIntakeService#accept}, which writes the file first on purpose: there, a rolled
     * back insert leaves an orphan file nothing can reach. Here the file already belongs to a row
     * that will survive either way, so the failure to avoid is the mirror image — a committed row
     * describing text that was never written. Writing inside the transaction means a failed update
     * leaves the row unchanged; the file is then the odd one out, and the re-index that follows a
     * successful edit is what makes file and chunks agree again.
     *
     * @return empty if there is no such live protocol — see {@link #PROTOCOL_ARCHIVED} for the
     *         archived case, which is refused rather than reported as missing
     */
    @Transactional
    public Optional<UUID> edit(UUID protocolId, Correction correction, String editedBy) {
        // Before anything is read: a correction with no stated reason is an unexplained change to
        // the corpus, which is the shape of the thing the audit trail exists to make visible.
        String comment = ProtocolModerationService.requireComment(correction.comment());

        Optional<Stored> found = load(protocolId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Stored stored = found.get();

        if (stored.deletedAt() != null) {
            // 409 rather than 404, decided here and mapped in the controller: the protocol exists
            // and this same administrator can read it in the archive one tab over. Answering "no
            // such protocol" would be a lie their own screen contradicts. What is wrong is the
            // request, not the address — nothing is editable once it has been retired, because an
            // edit re-indexes and re-indexing an archived protocol would put it back into search.
            throw new ProtocolModerationService.InvalidModerationRequestException(PROTOCOL_ARCHIVED,
                    "this protocol is in the archive and cannot be edited; archived is final");
        }
        requireUnchangedIdentity(correction.machineNo(), stored.machineNo(), "machine");
        requireUnchangedIdentity(correction.protocolType(), stored.protocolType(), "type");

        String title = required(correction.title(), "title");
        String content = required(correction.content(), "content");
        // The same ceiling the upload path enforces, read from the same property. A correction that
        // could be larger than the document it replaces would be a way around #36's size cap.
        if (content.getBytes(StandardCharsets.UTF_8).length > ingestionProperties.maxUploadBytes()) {
            throw new ProtocolIntakeService.InvalidProtocolException(
                    "text exceeds the %d byte limit".formatted(ingestionProperties.maxUploadBytes()));
        }

        writeDocument(stored.sourceFile(), content);

        /*
         * AN EDIT RESETS APPROVAL, and this is the rule the whole trust chain rests on.
         *
         * The corrected text is text nobody has reviewed. Leaving the protocol approved would mean
         * the approval flag vouches for words the approver never read — and ADR-006's entire
         * argument is that a citation must never point at silently-changed content. The reset makes
         * the flag mean what it says: APPROVED describes THIS text, not an earlier version of it.
         *
         * It is deliberately unconditional. "Only reset if the text actually changed" sounds
         * thriftier and is a trap: it would make the guarantee depend on a diff, and a whitespace
         * or encoding difference deciding whether a review still counts is not a rule anyone can
         * reason about. Every edit already forces a re-index for the same reason.
         *
         * The protocol stays SEARCHABLE throughout (decision 1 of 2026-08-11) — it simply says of
         * itself that it is no longer reviewed, and reappears in the administrator's queue.
         */
        jdbc.sql("""
                        UPDATE protocol
                        SET title = :title, error_code = :errorCode, symptom = :content,
                            status = 'RECEIVED', indexed_at = NULL, failure_reason = NULL,
                            approval_state = 'UNAPPROVED', approved_by = NULL, approved_at = NULL,
                            updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", protocolId)
                .param("title", title)
                .param("errorCode", blankToNull(correction.errorCode()), java.sql.Types.VARCHAR)
                // `symptom` holds the document text, as the upload path writes it (domain-model.md).
                .param("content", content)
                .param("now", OffsetDateTime.now())
                .update();

        moderation.recordEvent(protocolId, "EDIT", editedBy, comment);

        // Two rows, not one, when the edit withdrew an approval. The EDIT row says the text changed;
        // this one says the protocol stopped being vouched for, and by whose act. A reader of the
        // ledger should not have to know the reset rule to see that the approval ended here.
        if (ProtocolApprovalService.APPROVED.equals(stored.approvalState())) {
            moderation.recordEvent(protocolId, "UNAPPROVE", editedBy,
                    "approval reset automatically: the protocol was corrected — " + comment);
        }

        // AFTER_COMMIT on the listener, so the indexer never looks for an update it cannot see yet.
        events.publishEvent(new ProtocolReceivedEvent(protocolId));
        log.info("Moderation: {} edited protocol {} ('{}' -> '{}', machine {}), re-indexing: {}",
                editedBy, protocolId, stored.title(), title, stored.machineNo(), comment);
        return Optional.of(protocolId);
    }

    /**
     * Refuses a request that would change what a protocol <em>is</em>.
     *
     * <p>Refused rather than ignored. A client that thought it was moving a protocol to another
     * machine and received a 200 would have been told it succeeded, and the next person to read
     * that screen would believe the protocol had moved.
     */
    private static void requireUnchangedIdentity(String submitted, String stored, String field) {
        if (submitted != null && !submitted.isBlank() && !submitted.trim().equalsIgnoreCase(stored)) {
            throw new ProtocolModerationService.InvalidModerationRequestException(IDENTITY_LOCKED,
                    ("%s cannot be changed by an edit (submitted '%s', stored '%s'): a protocol's "
                            + "machine and type are its identity, not its content. If they are wrong, "
                            + "delete it and upload a new one.")
                            .formatted(field, submitted.trim(), stored));
        }
    }

    private Optional<Stored> load(UUID protocolId) {
        return jdbc.sql("""
                        SELECT p.title, p.protocol_type, p.source_file, p.deleted_at, p.approval_state,
                               m.machine_no
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.id = :id
                        """)
                .param("id", protocolId)
                .query((rs, rowNum) -> new Stored(
                        rs.getString("title"),
                        rs.getString("protocol_type"),
                        rs.getString("source_file"),
                        rs.getObject("deleted_at", OffsetDateTime.class),
                        rs.getString("approval_state"),
                        rs.getString("machine_no")))
                .optional();
    }

    /**
     * Overwrites the stored document in place.
     *
     * <p>Same path, deliberately. The file name is {@code <machine>/<protocol-id>.txt} and the id
     * does not change, so a correction leaves no second file behind and the citation, the row and
     * the volume keep pointing at one another. The path still gets the containment check every
     * other reader of {@code source_file} makes — it is a column, and a column is only as
     * trustworthy as everything that has ever written to it.
     */
    private void writeDocument(String sourceFile, String content) {
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new ProtocolIntakeService.InvalidProtocolException(
                    "this protocol has no stored document to correct");
        }
        Path base = Path.of(fileProperties.basePath()).toAbsolutePath().normalize();
        Path path = base.resolve(sourceFile).normalize();
        if (!path.startsWith(base)) {
            throw new IllegalStateException("source file outside the protocol volume: " + sourceFile);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rewrite protocol document " + sourceFile, e);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProtocolIntakeService.InvalidProtocolException(field + " is required");
        }
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record Stored(String title, String protocolType, String sourceFile,
                          OffsetDateTime deletedAt, String approvalState, String machineNo) {
    }
}
