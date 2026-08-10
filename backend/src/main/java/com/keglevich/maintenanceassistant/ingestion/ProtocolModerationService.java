package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The corpus as an administrator sees it: everything, and the power to remove any of it.
 *
 * <p><b>Why this exists.</b> The write path is restricted to one role for quality reasons, and that
 * restriction bounds volume and profanity — it does nothing about the threat that actually matters,
 * which is a <em>plausible</em> protocol with a wrong Massnahme filed by someone entitled to file
 * it. Mode A will cite that faithfully, which is precisely what makes it dangerous: the citation
 * discipline that makes a good answer checkable makes a poisoned one credible.
 *
 * <p>The application already had the accountability half — {@code uploaded_by} records who wrote it
 * and a citation traces an answer back to a specific protocol and therefore a specific author. This
 * is the remediation half. See ADR-006.
 *
 * <p><b>There is deliberately no update method.</b> Correcting a protocol is delete-then-reupload,
 * not an edit: an answer must never cite text that changed underneath it, and the ingestion path is
 * already idempotent per protocol (chunks are deleted and rewritten), so re-uploading is a first-
 * class operation rather than a workaround.
 */
@Service
public class ProtocolModerationService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolModerationService.class);

    /** Never more than this per page, whatever the caller asks for. */
    public static final int MAX_PAGE_SIZE = 50;

    private final JdbcClient jdbc;
    private final FileStorageProperties fileProperties;

    ProtocolModerationService(JdbcClient jdbc, FileStorageProperties fileProperties) {
        this.jdbc = jdbc;
        this.fileProperties = fileProperties;
    }

    /**
     * One page of the whole corpus, newest first.
     *
     * <p>Paged rather than capped-and-truncated like {@code /protocols/mine}: that list answers
     * "what happened to the three I just uploaded" and 50 rows covers it forever. This one is the
     * corpus itself — 150 protocols today and growing every time the feature it moderates is used —
     * so a reviewer has to be able to reach the far end of it, not just the top.
     */
    public ProtocolPage list(int page, int size) {
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * limit;

        List<ModeratedProtocol> rows = jdbc.sql("""
                        SELECT p.id, p.title, p.protocol_type, p.error_code, p.status,
                               p.uploaded_by, p.created_at, m.machine_no,
                               (SELECT count(*) FROM chunk c WHERE c.protocol_id = p.id) AS chunk_count
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        ORDER BY p.created_at DESC, p.id
                        LIMIT :limit OFFSET :offset
                        """)
                // Tie-broken by id: two protocols uploaded in the same second would otherwise be
                // free to swap places between pages, and a row that moves during paging is a row a
                // reviewer can see twice or never.
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new ModeratedProtocol(
                        rs.getObject("id", UUID.class),
                        rs.getString("machine_no"),
                        rs.getString("title"),
                        rs.getString("protocol_type"),
                        rs.getString("error_code"),
                        rs.getString("uploaded_by"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("status"),
                        rs.getInt("chunk_count")))
                .list();

        long total = jdbc.sql("SELECT count(*) FROM protocol").query(Long.class).single();
        return new ProtocolPage(rows, page, limit, total);
    }

    /**
     * Removes a protocol completely: chunks, row, file.
     *
     * <p><b>The order is the whole design.</b> Chunks first: a chunk is what retrieval searches, so
     * a chunk whose protocol is gone is retrievable garbage — it can still be returned, ranked and
     * cited, pointing at a row that no longer exists. The row second. The file last, because a file
     * with no row is inert: nothing can find it, nothing will read it, and it costs a few kilobytes
     * until someone sweeps the volume.
     *
     * <p>That ordering also makes a half-failure safe to retry. Interrupted anywhere, what survives
     * is always the harmless end of the list, and calling this again finishes the job — each step
     * is a delete of something that may already be absent.
     *
     * <p>The schema declares {@code ON DELETE CASCADE} from chunk to protocol, so the first
     * statement is technically redundant. It is written out anyway: the guarantee this method makes
     * is "no retrievable orphan", and a guarantee that silently depends on a foreign-key clause in a
     * migration nobody is reading is a guarantee that lasts until someone edits the migration.
     *
     * @return false if there was no such protocol; true if one was removed
     */
    @Transactional
    public boolean delete(UUID protocolId, String deletedBy) {
        Optional<DeletedProtocol> target = jdbc.sql("""
                        SELECT p.title, p.source_file, m.machine_no
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.id = :id
                        """)
                .param("id", protocolId)
                .query((rs, rowNum) -> new DeletedProtocol(
                        rs.getString("title"), rs.getString("source_file"), rs.getString("machine_no")))
                .optional();

        if (target.isEmpty()) {
            return false;
        }

        int chunks = jdbc.sql("DELETE FROM chunk WHERE protocol_id = :id")
                .param("id", protocolId)
                .update();
        jdbc.sql("DELETE FROM protocol WHERE id = :id")
                .param("id", protocolId)
                .update();
        deleteFile(target.get().sourceFile());

        // INFO, not DEBUG: this is the audit trail of the audit function. Who removed what, and
        // enough of the protocol's identity to recognise it later — a bare UUID in a log tells a
        // reader nothing about what was lost.
        log.info("Moderation: {} deleted protocol {} ('{}', machine {}) with {} chunks",
                deletedBy, protocolId, target.get().title(), target.get().machineNo(), chunks);
        return true;
    }

    /**
     * Best effort, and deliberately not fatal.
     *
     * <p>The row is already gone by the time this runs, so throwing here would roll the transaction
     * back and restore a protocol the administrator asked to have removed — trading an inert file
     * on a volume for a searchable protocol in the corpus, which is the wrong way round. The failure
     * is logged as a warning so a stale file can be swept up; nothing can reach it in the meantime.
     */
    private void deleteFile(String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return;
        }
        Path base = Path.of(fileProperties.basePath()).toAbsolutePath().normalize();
        Path path = base.resolve(sourceFile).normalize();
        // The same containment check the read path makes, for the same reason: source_file is a
        // column, and here a bad value would delete something outside the volume.
        if (!path.startsWith(base)) {
            log.warn("Moderation: refusing to delete {} — outside the protocol volume", sourceFile);
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Moderation: protocol row removed but its file {} could not be deleted: {}",
                    sourceFile, e.toString());
        }
    }

    /**
     * @param page      zero-based page index, as asked for
     * @param size      rows per page after clamping
     * @param total     protocols in the corpus, so a client can render "page 3 of 16"
     */
    public record ProtocolPage(List<ModeratedProtocol> items, int page, int size, long total) {
    }

    /**
     * @param uploadedBy the Keycloak username of the author — the accountability half of ADR-006,
     *                   and the reason a reviewer can ask who filed a protocol they distrust
     * @param chunkCount how many searchable pieces this protocol contributes; 0 means it is stored
     *                   but not retrievable, which is what RECEIVED and FAILED look like
     */
    public record ModeratedProtocol(
            UUID id,
            String machineNo,
            String title,
            String protocolType,
            String errorCode,
            String uploadedBy,
            OffsetDateTime uploadedAt,
            String status,
            int chunkCount) {
    }

    private record DeletedProtocol(String title, String sourceFile, String machineNo) {
    }
}
