package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return list(page, size, ProtocolFilter.none());
    }

    /**
     * The same page, narrowed.
     *
     * <p>The ordering and the paging are deliberately identical to the unfiltered call — a filter
     * narrows the set, it does not change what "next page" means. {@code total} counts the
     * <em>filtered</em> set, because a pager that counted the corpus while showing four rows would
     * offer sixteen pages of nothing.
     *
     * <p><b>The WHERE clause is assembled rather than written once with {@code :p IS NULL OR}
     * guards.</b> Postgres cannot infer a type for a parameter that appears only in {@code IS NULL},
     * so the guarded form needs a cast on every parameter to work at all; and it hides from the
     * planner that a filtered query touches one machine. Only the fragments are concatenated — every
     * value the caller supplied is bound, never interpolated.
     */
    public ProtocolPage list(int page, int size, ProtocolFilter filter) {
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * limit;

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (filter.machineNo() != null) {
            conditions.add("m.machine_no = :machineNo");
            params.put("machineNo", filter.machineNo());
        }
        if (filter.titleContains() != null) {
            // ILIKE rather than a regular expression: the field is a plain substring box on a
            // review screen, and a regex there is a way for a filter to become a CPU cost.
            conditions.add("p.title ILIKE :titlePattern ESCAPE '\\'");
            params.put("titlePattern", containsPattern(filter.titleContains()));
        }
        if (filter.from() != null) {
            conditions.add("p.created_at >= :from");
            params.put("from", startOfDay(filter.from()));
        }
        if (filter.to() != null) {
            // Inclusive, expressed as "before the next day": created_at is a timestamp, so
            // `<= :to` at midnight would silently exclude everything filed on the chosen day.
            conditions.add("p.created_at < :toExclusive");
            params.put("toExclusive", startOfDay(filter.to().plusDays(1)));
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        List<ModeratedProtocol> rows = jdbc.sql("""
                        SELECT p.id, p.title, p.protocol_type, p.error_code, p.status,
                               p.uploaded_by, p.created_at, m.machine_no,
                               (SELECT count(*) FROM chunk c WHERE c.protocol_id = p.id) AS chunk_count
                        FROM protocol p JOIN machine m ON m.id = p.machine_id"""
                        + where
                        + """
                        \nORDER BY p.created_at DESC, p.id
                        LIMIT :limit OFFSET :offset
                        """)
                // Tie-broken by id: two protocols uploaded in the same second would otherwise be
                // free to swap places between pages, and a row that moves during paging is a row a
                // reviewer can see twice or never.
                .params(params)
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

        long total = jdbc.sql("SELECT count(*) FROM protocol p JOIN machine m ON m.id = p.machine_id"
                        + where)
                .params(params)
                .query(Long.class)
                .single();
        return new ProtocolPage(rows, page, limit, total);
    }

    /**
     * A substring pattern with the wildcards the user did not intend taken away.
     *
     * <p>{@code %} and {@code _} are LIKE wildcards, so an unescaped {@code %} would turn a search
     * for a literal per-cent sign into "match anything" — the filter would quietly stop filtering.
     * The backslash goes first, because escaping it afterwards would escape the escapes.
     */
    private static String containsPattern(String raw) {
        String escaped = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * A calendar day turned into an instant, in UTC.
     *
     * <p>Some zone has to be chosen, and UTC is the one the API already speaks: the timestamps this
     * endpoint returns are UTC, so a day boundary drawn anywhere else would put a protocol on one
     * side of the filter and show it with a date on the other.
     */
    private static OffsetDateTime startOfDay(LocalDate day) {
        return day.atStartOfDay().atOffset(ZoneOffset.UTC);
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
     * What the reviewer narrowed the corpus to. Every field is optional; all of them absent is the
     * plain list.
     *
     * <p><b>The machine comes first, and the others are refused without it.</b> That is a product
     * rule, not a technical one: 150 protocols across ten machines means a title fragment on its own
     * answers with rows from machines the reviewer was not looking at, and a date range on its own
     * answers with most of the corpus. Both are noise dressed as a result. Enforced here rather than
     * in the controller so the rule cannot be reached around by a second caller.
     *
     * @param machineNo     plant identifier, matched exactly ("PR-03")
     * @param titleContains case-insensitive substring of the title
     * @param from          earliest upload day, inclusive
     * @param to            latest upload day, inclusive; open-ended in either direction is allowed
     */
    public record ProtocolFilter(String machineNo, String titleContains, LocalDate from, LocalDate to) {

        /** The stable code a client matches on. English prose from this layer is not an API. */
        public static final String MACHINE_REQUIRED = "MACHINE_REQUIRED_FOR_FILTER";

        public ProtocolFilter {
            machineNo = blankToNull(machineNo);
            titleContains = blankToNull(titleContains);
            if (machineNo == null && (titleContains != null || from != null || to != null)) {
                throw new InvalidFilterException(MACHINE_REQUIRED,
                        "a title or date filter needs a machine: choose a machine first");
            }
        }

        public static ProtocolFilter none() {
            return new ProtocolFilter(null, null, null, null);
        }

        private static String blankToNull(String value) {
            // An empty query parameter is an empty form field, which is a filter the user did not
            // fill in — not a search for the empty string, and not a reason to refuse the request.
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** A filter combination the endpoint does not accept. Answered as 400 with {@link #code()}. */
    public static class InvalidFilterException extends RuntimeException {

        private final String code;

        InvalidFilterException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
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
