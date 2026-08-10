package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What happened to the protocols one person uploaded.
 *
 * <p>{@code IngestionAdminController} already answers "is the corpus indexed" as counts by status,
 * and that is the operator's question, not the uploader's. A Schichtleiter who has just uploaded
 * three documents wants to know about <em>those three</em>: which are searchable, which failed, and
 * why. A total of 150 INDEXED and 2 FAILED does not answer it, and neither does a spinner.
 *
 * <p>Scoped to the caller's own uploads rather than to everything, because the upload endpoint
 * returns 202 and this is the other half of that honesty — it exists so a person can see the
 * consequence of what they did. Seeing the whole plant's ingestion history is an admin concern with
 * an admin endpoint.
 */
@Service
public class ProtocolStatusService {

    /**
     * A shift's worth of uploads, generously. Bounded because this is a list rendered without
     * paging: an unbounded query behind a fixed-size view is a page that gets slower every month
     * and a query nobody notices growing.
     */
    private static final int MAX_ROWS = 50;

    private final JdbcClient jdbc;

    ProtocolStatusService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The caller's most recent uploads, newest first.
     *
     * <p>Archived protocols are gone from here too. An uploader seeing a protocol an administrator
     * removed would be told it is INDEXED and searchable, which is false in the way that matters:
     * they would stop looking for the reason their answer never cites it. Being told <em>why</em> it
     * was removed is a notification feature this application does not have; showing it as healthy is
     * worse than showing nothing.
     *
     * @param uploadedBy the Keycloak username claim, which is what {@code protocol.uploaded_by}
     *                   stores — there is no user table to join against (ADR-003)
     */
    public List<UploadStatus> findRecentUploadsOf(String uploadedBy) {
        return jdbc.sql("""
                        SELECT p.id, p.title, p.status, p.failure_reason, p.created_at, p.indexed_at,
                               m.machine_no
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.uploaded_by = :uploadedBy
                          AND p.deleted_at IS NULL
                        ORDER BY p.created_at DESC
                        LIMIT :max
                        """)
                .param("uploadedBy", uploadedBy)
                .param("max", MAX_ROWS)
                .query((rs, rowNum) -> new UploadStatus(
                        rs.getObject("id", UUID.class),
                        rs.getString("machine_no"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getString("failure_reason"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("indexed_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * @param status        RECEIVED, INDEXED or FAILED
     * @param failureReason why it failed; null unless FAILED. Shown to the uploader rather than
     *                      logged only, because "it failed" without a reason makes re-uploading the
     *                      same broken file the obvious next move
     */
    public record UploadStatus(
            UUID id,
            String machineNo,
            String title,
            String status,
            String failureReason,
            OffsetDateTime createdAt,
            OffsetDateTime indexedAt) {
    }
}
