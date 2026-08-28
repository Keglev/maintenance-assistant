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
 * <p><b>Correction lives next door, in {@link ProtocolEditService}.</b> This class used to state that
 * there deliberately was no update method; ADR-006's 2026-08-10 revision reversed that, on the
 * ground that the system stores no answers for an edit to invalidate. Editing is a separate service
 * because it is a write into the ingestion pipeline — file, row, re-index — while everything here
 * reads or retires.
 *
 * <p><b>Deletion is a soft delete into an archive, not a hard one.</b> The chunks still go, which is
 * what removes the protocol from every answer; the row and the file stay so that removing garbage
 * does not also destroy the evidence of who produced it. There is no restore, by design.
 */
@Service
public class ProtocolModerationService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolModerationService.class);

    /** Never more than this per page, whatever the caller asks for. */
    public static final int MAX_PAGE_SIZE = 50;

    /**
     * How many deletions one machine's archive keeps before the oldest are purged for good.
     *
     * <p>Per machine, not global: a burst of deletions on one press must not push another machine's
     * evidence out. Fifty is generous for this plant and arbitrary in principle — what matters is
     * that the number exists, because an archive with no ceiling is a disk with no ceiling, and
     * every other unbounded thing in this application is bounded (NFR-7).
     */
    public static final int ARCHIVE_CAP = 50;

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

        // The live corpus, always. An archived protocol is out of every list in the application
        // except the archive itself, including this one — the reviewer's list is what is IN the
        // corpus, and something they already removed reappearing in it would read as the deletion
        // having failed (ADR-006 revision).
        List<String> conditions = new ArrayList<>(List.of("p.deleted_at IS NULL"));
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
        if (filter.approvalState() != null) {
            // NOT subject to the machine-first rule the three filters above obey, and the reason is
            // what that rule is for: it exists because a title fragment or a date range across ten
            // machines answers with rows the reviewer was not looking at. "Everything still waiting
            // for review" is the opposite — a queue is only useful whole, and the first question an
            // administrator asks after signing in is exactly that one.
            conditions.add("p.approval_state = :approvalState");
            params.put("approvalState", filter.approvalState());
        }
        String where = " WHERE " + String.join(" AND ", conditions);

        List<ModeratedProtocol> rows = jdbc.sql("""
                        SELECT p.id, p.title, p.protocol_type, p.error_code, p.status,
                               p.uploaded_by, p.created_at, m.machine_no,
                               p.approval_state, p.approved_by, p.approved_at,
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
                        rs.getInt("chunk_count"),
                        new ProtocolApprovalService.Approval(
                                rs.getString("approval_state"),
                                rs.getString("approved_by"),
                                rs.getObject("approved_at", OffsetDateTime.class))))
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
     * Removes a protocol from the corpus and moves it into the archive.
     *
     * <p><b>The chunks are still deleted, and that is the load-bearing step.</b> A chunk is what
     * retrieval searches; deleting them is what takes the protocol out of every answer instantly and
     * permanently, for every role, in this same request. The soft delete added around it changes who
     * can still <em>read</em> the protocol afterwards — nobody but an administrator — not whether it
     * can be found. Retrieval's safety is therefore structural, not a filter somebody has to
     * remember (ADR-006 revision answers the original ADR's objection to soft deletion here).
     *
     * <p>The row and the file stay. #37 removed them, and with them the evidence that the protocol
     * had ever existed — an administrator cleaning up a poisoned protocol would erase the trace of
     * who filed it, finishing the job the author started. The insider-threat chain is create →
     * detect → trace → remediate → <b>preserve</b>, and this is the last link.
     *
     * <p>Deleting an already-archived protocol answers false rather than deleting it twice: the
     * second call has nothing to remove and would otherwise overwrite the first deletion's timestamp
     * and comment with a later one, rewriting the audit trail it is supposed to be adding to.
     *
     * @param comment why it was removed, required and non-blank — an unexplained change to the
     *                corpus is the shape of the thing this audit trail exists to make visible
     * @return false if there was no such live protocol; true if one was archived
     */
    @Transactional
    public boolean delete(UUID protocolId, String deletedBy, String comment) {
        String reason = requireComment(comment);
        Optional<DeletedProtocol> target = jdbc.sql("""
                        SELECT p.title, p.source_file, p.machine_id, m.machine_no
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.id = :id AND p.deleted_at IS NULL
                        """)
                .param("id", protocolId)
                .query((rs, rowNum) -> new DeletedProtocol(
                        rs.getString("title"), rs.getString("source_file"),
                        rs.getObject("machine_id", UUID.class), rs.getString("machine_no")))
                .optional();

        if (target.isEmpty()) {
            return false;
        }

        int chunks = jdbc.sql("DELETE FROM chunk WHERE protocol_id = :id")
                .param("id", protocolId)
                .update();
        jdbc.sql("UPDATE protocol SET deleted_at = :now, updated_at = :now WHERE id = :id")
                .param("id", protocolId)
                .param("now", OffsetDateTime.now())
                .update();
        recordEvent(protocolId, "DELETE", deletedBy, reason);

        // INFO, not DEBUG: this is the audit trail of the audit function, and it is now the
        // human-readable half of a record whose queryable half is moderation_event. Enough of the
        // protocol's identity to recognise it later — a bare UUID tells a reader nothing about what
        // was removed, and after a purge this line is the only place the title survives.
        log.info("Moderation: {} archived protocol {} ('{}', machine {}), {} chunks removed: {}",
                deletedBy, protocolId, target.get().title(), target.get().machineNo(), chunks, reason);

        enforceArchiveCap(target.get().machineId(), target.get().machineNo());
        return true;
    }

    /**
     * Keeps the archive of one machine at {@link #ARCHIVE_CAP} entries, purging the oldest
     * deletions completely — row and file.
     *
     * <p>An archive that only grows is a disk that only fills, and this application bounds every
     * other unbounded thing it has (NFR-7). The cap is per machine rather than global so that one
     * machine under a burst of deletions cannot push another machine's evidence out.
     *
     * <p><b>Synchronous, as part of the delete that breached it.</b> A retention job would be a
     * scheduler, a timer and a piece of state that can silently stop running — and an archive that
     * quietly stopped being capped looks exactly like one that is working. This cannot stop running
     * without deletion itself stopping.
     *
     * <p>The {@code moderation_event} rows of a purged protocol are deliberately <b>not</b> removed
     * (the table carries no foreign key). The fifty-first deletion erasing the record of the first
     * would be the audit function losing its own audit trail.
     */
    private void enforceArchiveCap(UUID machineId, String machineNo) {
        List<PurgeTarget> excess = jdbc.sql("""
                        SELECT p.id, p.title, p.source_file
                        FROM protocol p
                        WHERE p.machine_id = :machineId AND p.deleted_at IS NOT NULL
                        ORDER BY p.deleted_at DESC, p.id
                        OFFSET :cap
                        """)
                // OFFSET past the cap on the newest-first ordering: whatever is left is by
                // definition everything older than the fiftieth deletion, so the query names the
                // rows to purge rather than counting first and deciding afterwards.
                .param("machineId", machineId)
                .param("cap", ARCHIVE_CAP)
                .query((rs, rowNum) -> new PurgeTarget(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("source_file")))
                .list();

        for (PurgeTarget purged : excess) {
            jdbc.sql("DELETE FROM protocol WHERE id = :id").param("id", purged.id()).update();
            deleteFile(purged.sourceFile());
            // The title survives here and nowhere else once the row is gone: moderation_event keeps
            // who removed it and why, and this line keeps what "it" was.
            log.info("Moderation: archive cap ({}) reached for machine {} — purged protocol {} ('{}') "
                            + "permanently; its moderation events are kept",
                    ARCHIVE_CAP, machineNo, purged.id(), purged.title());
        }
    }

    /**
     * One page of the archive: what was removed from this machine, newest deletion first.
     *
     * <p>Joined to the {@code DELETE} event rather than storing the comment on the protocol row,
     * because a protocol can carry several moderation acts — edits before the deletion — and the
     * archive is asking about one of them. The latest DELETE is the one that put it here.
     */
    public DeletedProtocolPage listDeleted(String machineNo, int page, int size) {
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * limit;
        String machine = machineNo == null || machineNo.isBlank() ? null : machineNo.trim();
        String where = machine == null ? "" : " AND m.machine_no = :machineNo";

        List<ArchivedProtocol> rows = jdbc.sql("""
                        SELECT p.id, p.title, p.protocol_type, p.error_code, p.uploaded_by,
                               p.created_at, p.deleted_at, m.machine_no,
                               e.actor AS deleted_by, e.comment AS delete_comment
                        FROM protocol p
                        JOIN machine m ON m.id = p.machine_id
                        LEFT JOIN LATERAL (
                            SELECT actor, comment FROM moderation_event
                            WHERE protocol_id = p.id AND action = 'DELETE'
                            ORDER BY created_at DESC LIMIT 1
                        ) e ON true
                        WHERE p.deleted_at IS NOT NULL"""
                        + where
                        + """
                        \nORDER BY p.deleted_at DESC, p.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("machineNo", machine, java.sql.Types.VARCHAR)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new ArchivedProtocol(
                        rs.getObject("id", UUID.class),
                        rs.getString("machine_no"),
                        rs.getString("title"),
                        rs.getString("protocol_type"),
                        rs.getString("error_code"),
                        rs.getString("uploaded_by"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("deleted_at", OffsetDateTime.class),
                        rs.getString("deleted_by"),
                        rs.getString("delete_comment")))
                .list();

        long total = jdbc.sql("""
                        SELECT count(*) FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.deleted_at IS NOT NULL"""
                        + where)
                .param("machineNo", machine, java.sql.Types.VARCHAR)
                .query(Long.class)
                .single();
        return new DeletedProtocolPage(rows, page, limit, total, ARCHIVE_CAP);
    }

    /**
     * How many ledger entries the protocol viewer shows.
     *
     * <p><b>THREE IS A PRODUCT DECISION, NOT A TECHNICAL LIMIT — please do not "fix" it.</b> The
     * viewer exists to let somebody read a protocol; the history beside it answers "what has been
     * done to this recently, and by whom", and three lines answer that on a tablet without pushing
     * the document itself below the fold. A full change history is a REPORT — its own screen, with
     * paging and filters — and Carlos has deferred it deliberately rather than left it out by
     * accident. Raising this number would grow a dialog into that report one line at a time.
     *
     * <p>The count of everything is returned beside the three, so the viewer can say that older
     * entries exist. A truncation nobody is told about is the failure this constant would otherwise
     * cause.
     */
    public static final int HISTORY_LIMIT = 3;

    /**
     * What has been done to one protocol, newest act first.
     *
     * <p><b>Limited in SQL rather than in the client</b>, which is the whole reason this returns a
     * record instead of a list: a protocol edited weekly for a year has fifty rows, and sending all
     * of them so a dialog can drop forty-seven is a payload that grows with the corpus's age. The
     * index {@code ix_moderation_event_protocol (protocol_id, created_at DESC)} exists for exactly
     * this ordering and was written in V4 for the archive's lateral join.
     *
     * <p>Works for an archived protocol as well as a live one. {@code moderation_event} has no
     * foreign key — deliberately, so a purge cannot erase the ledger — so this is a lookup by id and
     * nothing about the protocol's state changes what it can answer.
     *
     * <p><b>Every row carries a comment by database constraint</b> ({@code ck_moderation_event_comment}
     * requires a non-blank one), so the caller never has to render an entry with no reason beside it.
     */
    public ProtocolHistory history(UUID protocolId) {
        List<ModerationEvent> events = jdbc.sql("""
                        SELECT action, actor, comment, created_at
                        FROM moderation_event
                        WHERE protocol_id = :id
                        ORDER BY created_at DESC, id
                        LIMIT :limit
                        """)
                // Tie-broken by id for the same reason the corpus list is: an edit and the
                // UNAPPROVE it triggers share a transaction timestamp to the microsecond, so
                // created_at alone leaves their order free to swap between two reads of one dialog.
                .param("id", protocolId)
                .param("limit", HISTORY_LIMIT)
                .query((rs, rowNum) -> new ModerationEvent(
                        rs.getString("action"),
                        rs.getString("actor"),
                        rs.getString("comment"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        long total = jdbc.sql("SELECT count(*) FROM moderation_event WHERE protocol_id = :id")
                .param("id", protocolId)
                .query(Long.class)
                .single();
        return new ProtocolHistory(events, total, HISTORY_LIMIT);
    }

    /**
     * One act, as the viewer shows it.
     *
     * @param action one of EDIT, DELETE, APPROVE, UNAPPROVE — the vocabulary
     *               {@code ck_moderation_event_action} allows
     * @param actor  the Keycloak username of whoever did it
     * @param comment why. Never null and never blank; the table's own constraint says so
     * @param at     when, to the second. The viewer shows the TIME as well as the day: two edits on
     *               one afternoon are a different story from two edits a month apart, and the
     *               moderation list's day-only format cannot tell them apart
     */
    public record ModerationEvent(String action, String actor, String comment, OffsetDateTime at) {
    }

    /**
     * @param total how many acts this protocol has had in all, so the viewer can say that older
     *              entries exist rather than silently showing the newest three as though they were
     *              everything
     * @param limit the server-side cap, reported so no screen hard-codes a number this service is
     *              free to change — the same reason the archive page reports its own cap
     */
    public record ProtocolHistory(List<ModerationEvent> events, long total, int limit) {
    }

    /** Writes one line of the ledger. Called by every moderation act, including {@link #delete}. */
    void recordEvent(UUID protocolId, String action, String actor, String comment) {
        jdbc.sql("""
                        INSERT INTO moderation_event (id, protocol_id, action, actor, comment)
                        VALUES (:id, :protocolId, :action, :actor, :comment)
                        """)
                .param("id", UUID.randomUUID())
                .param("protocolId", protocolId)
                .param("action", action)
                .param("actor", actor)
                .param("comment", comment)
                .update();
    }

    /** The stable code a client matches on when a moderation act arrives without its reason. */
    public static final String COMMENT_REQUIRED = "MODERATION_COMMENT_REQUIRED";

    static String requireComment(String comment) {
        if (comment == null || comment.isBlank()) {
            // Blank counts as missing. A comment box someone tabbed past is not a stated reason,
            // and " " in the ledger would be worse than an empty one — it looks like an answer.
            throw new InvalidModerationRequestException(COMMENT_REQUIRED,
                    "a moderation comment is required: say why this protocol is being changed");
        }
        return comment.trim();
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
    public record ProtocolFilter(String machineNo, String titleContains, LocalDate from, LocalDate to,
                                 String approvalState) {

        /** The stable code a client matches on. English prose from this layer is not an API. */
        public static final String MACHINE_REQUIRED = "MACHINE_REQUIRED_FOR_FILTER";
        /** An approval filter that is neither state. */
        public static final String UNKNOWN_APPROVAL_STATE = "UNKNOWN_APPROVAL_STATE";

        public ProtocolFilter {
            machineNo = blankToNull(machineNo);
            titleContains = blankToNull(titleContains);
            approvalState = blankToNull(approvalState);
            if (machineNo == null && (titleContains != null || from != null || to != null)) {
                throw new InvalidModerationRequestException(MACHINE_REQUIRED,
                        "a title or date filter needs a machine: choose a machine first");
            }
            if (approvalState != null) {
                approvalState = approvalState.toUpperCase(java.util.Locale.ROOT);
                if (!ProtocolApprovalService.APPROVED.equals(approvalState)
                        && !ProtocolApprovalService.UNAPPROVED.equals(approvalState)) {
                    // Refused rather than ignored. A typo silently answering with the unfiltered
                    // corpus would tell a reviewer their queue is the whole corpus.
                    throw new InvalidModerationRequestException(UNKNOWN_APPROVAL_STATE,
                            "approvalState must be APPROVED or UNAPPROVED, not '"
                                    + approvalState + "'");
                }
            }
        }

        public static ProtocolFilter none() {
            return new ProtocolFilter(null, null, null, null, null);
        }

        private static String blankToNull(String value) {
            // An empty query parameter is an empty form field, which is a filter the user did not
            // fill in — not a search for the empty string, and not a reason to refuse the request.
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** A filter combination the endpoint does not accept. Answered as 400 with {@link #code()}. */
    public static class InvalidModerationRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String code;

        public InvalidModerationRequestException(String code, String message) {
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
            int chunkCount,
            /*
             * Returned on EVERY row rather than only on the queue. The moderation list is also
             * where an administrator checks that something they approved is still approved — and
             * after an edit it will not be, because a correction resets it.
             */
            ProtocolApprovalService.Approval approval) {
    }

    /**
     * One page of the archive.
     *
     * @param cap the per-machine ceiling, reported so the view can state it rather than hard-code a
     *            number that would drift the day the constant changes
     */
    public record DeletedProtocolPage(List<ArchivedProtocol> items, int page, int size, long total,
                                      int cap) {
    }

    /**
     * A protocol as the archive shows it: what it was, who filed it, and who removed it and why.
     *
     * @param deletedBy      actor of the DELETE event; null only for a row archived by something
     *                       other than this service, which nothing does today
     * @param deleteComment  the stated reason — the field the whole archive exists to carry
     */
    public record ArchivedProtocol(
            UUID id,
            String machineNo,
            String title,
            String protocolType,
            String errorCode,
            String uploadedBy,
            OffsetDateTime uploadedAt,
            OffsetDateTime deletedAt,
            String deletedBy,
            String deleteComment) {
    }

    private record DeletedProtocol(String title, String sourceFile, UUID machineId, String machineNo) {
    }

    /** What a purge needs to know: which row to drop, which file to remove, what to call it in the log. */
    private record PurgeTarget(UUID id, String title, String sourceFile) {
    }
}
