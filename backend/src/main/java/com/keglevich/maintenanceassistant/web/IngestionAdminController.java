package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.IngestionBacklogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Operating the indexer: what state the corpus is in, and re-queueing what is not indexed.
 *
 * <p>Admin only. Enqueueing spends money at the provider, so it is the one operation in this
 * application where the authorisation question is "who pays" rather than "who may read".
 *
 * <p>This is also the catch-up path for the seeded corpus: those 150 protocols were written by the
 * seed loader before this pipeline existed and never produced an event, so they sit at
 * {@code RECEIVED} with nothing coming for them. Re-running is safe — indexing replaces a
 * protocol's chunks rather than adding to them.
 */
@RestController
@RequestMapping("/api/ingestion")
@Tag(name = "Ingestion", description = "Upload and indexing of maintenance protocols")
class IngestionAdminController {

    private final IngestionBacklogService backlog;

    IngestionAdminController(IngestionBacklogService backlog) {
        this.backlog = backlog;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('admin', 'schichtleiter')")
    @Operation(summary = "Protocol counts by indexing status")
    Map<String, Object> status() {
        List<IngestionBacklogService.StatusCount> counts = backlog.statusCounts();
        return Map.of(
                "counts", counts.stream().collect(
                        java.util.stream.Collectors.toMap(
                                IngestionBacklogService.StatusCount::status,
                                IngestionBacklogService.StatusCount::count)),
                "total", counts.stream().mapToLong(IngestionBacklogService.StatusCount::count).sum());
    }

    @PostMapping("/backlog")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Queue unindexed protocols for indexing",
            description = "Finds protocols in the given statuses and hands them to the indexer. "
                    + "Idempotent: re-indexing replaces a protocol's chunks rather than adding to them. "
                    + "Defaults to RECEIVED; pass FAILED to retry failures.")
    Map<String, Object> runBacklog(
            @RequestParam(value = "status", defaultValue = "RECEIVED") List<String> statuses,
            @RequestParam(value = "limit", required = false) Integer limit) {

        List<String> requested = statuses.stream().map(String::toUpperCase).toList();
        for (String status : requested) {
            if (!List.of("RECEIVED", "FAILED").contains(status)) {
                throw new IllegalArgumentException(
                        "status must be RECEIVED or FAILED; INDEXED protocols are re-queued by id, not in bulk");
            }
        }
        int enqueued = backlog.enqueue(requested, limit);
        return Map.of("enqueued", enqueued, "statuses", requested);
    }
}
