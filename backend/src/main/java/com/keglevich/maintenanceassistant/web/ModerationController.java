package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolApprovalService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolEditService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolIntakeService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Moderation: the administrator's view of the whole corpus, and the power to remove from it.
 *
 * <p>This is the admin role's <b>first shop-floor function</b>. Until now it was an IT role with no
 * business in the application, and the reason it has one now is ADR-006: an authorised writer can
 * file a plausible protocol with a wrong Massnahme, Mode A will cite it faithfully, and no limit on
 * size, rate or vocabulary catches that. Traceability was already in place; this is remediation.
 *
 * <p>Its own controller and its own path prefix rather than more methods on
 * {@link ProtocolReadController}, because the authorisation rule is the feature. Everything under
 * {@code /api/moderation} is admin-only <b>with exactly one exception</b>, {@code PUT /{id}}, which
 * is <b>SCHICHTLEITER-only and closed to the administrator</b>: the v1.2 trust chain makes the
 * Schichtleiter the corrector and nobody else, and the reasoning is on that method. It is called out
 * here because a class-level rule with a silent exception is worse than no rule.
 *
 * <p><b>Reading here is not answering.</b> The shop-floor document endpoint is restricted to the
 * three roles that ask questions, because it exists to make a citation checkable. This one exists to
 * let a reviewer read a protocol they are deciding the fate of — a different act, on a path an admin
 * reaches without holding a shop-floor role.
 *
 * <p><b>Two verbs were added by ADR-006's 2026-08-10 revision.</b> {@code PUT} corrects a protocol
 * in place and forces a re-index, because the reason editing was refused — an answer citing text
 * that changed underneath it — assumed stored answers, and this system generates every answer per
 * query. {@code DELETE} became a soft delete into an archive, because a hard one removed the bad
 * protocol <em>and</em> the evidence of who filed it, which is the one thing the insider-threat
 * chain cannot afford to lose. Both require a comment, and neither can be undone.
 */
@RestController
@RequestMapping("/api/moderation/protocols")
@Tag(name = "Moderation", description = "Administrator review and removal of protocols")
@PreAuthorize("hasRole('ADMIN')")
class ModerationController {

    /** The archive ceiling, spelled out in the OpenAPI text so the two cannot disagree. */
    private static final String ARCHIVE_CAP_TEXT = "" + ProtocolModerationService.ARCHIVE_CAP;

    private final ProtocolModerationService moderation;
    private final ProtocolEditService edits;
    private final ProtocolDocumentService documents;
    private final ProtocolApprovalService approvals;

    ModerationController(ProtocolModerationService moderation, ProtocolEditService edits,
                         ProtocolDocumentService documents, ProtocolApprovalService approvals) {
        this.moderation = moderation;
        this.edits = edits;
        this.documents = documents;
        this.approvals = approvals;
    }

    @GetMapping
    @Operation(summary = "List every protocol in the corpus, newest first",
            description = "Paged. `size` is clamped to 50 — the corpus grows every time a protocol "
                    + "is uploaded, and an unpaged list of it is a page that gets slower forever. "
                    + "Optionally narrowed by machine, by a case-insensitive title substring and by "
                    + "an upload-date range. `titleContains`, `from` and `to` require `machineNo`: "
                    + "without it they answer with rows from machines the reviewer was not looking "
                    + "at. All four absent is the plain list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of protocols"),
            @ApiResponse(responseCode = "400",
                    description = "A title or date filter was sent without a machine "
                            + "(`reason: MACHINE_REQUIRED_FOR_FILTER`)"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator")
    })
    ProtocolModerationService.ProtocolPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String machineNo,
            @RequestParam(required = false) String titleContains,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            // The approval queue. NOT subject to the machine-first rule the three filters above
            // obey: that rule exists because a title fragment across ten machines answers with rows
            // the reviewer was not looking for, and "everything still waiting for review" is the
            // opposite — a queue is only useful whole.
            @RequestParam(required = false) String approvalState) {
        // The record's constructor is where the machine-first rule lives, so it holds for any caller
        // rather than for this method signature only.
        return moderation.list(page, size, new ProtocolModerationService.ProtocolFilter(
                machineNo, titleContains, from, to, approvalState));
    }

    @GetMapping("/{id}/document")
    @Operation(summary = "Read the original document of any protocol",
            description = "The same file the shop floor sees behind a citation, reachable by an "
                    + "administrator who holds no shop-floor role. Reviewing is not answering.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "No such protocol, or its file is gone")
    })
    ResponseEntity<Resource> document(@PathVariable UUID id) {
        // The live document. An archived protocol answers 404 here, exactly as a stale citation
        // does on the shop-floor path — the archive changed who can still read a removed protocol,
        // not whether the ordinary routes to it keep working.
        return documents.find(id)
                .map(ModerationController::inline)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Served for reading, not downloading, with a filename that still travels for a save. */
    private static ResponseEntity<Resource> inline(ProtocolDocumentService.ProtocolDocument document) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(document.downloadName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(document.resource());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct a protocol in place, forcing a re-index",
            description = "Rewrites the stored document, updates title and fault code, records the "
                    + "edit with its mandatory comment and queues the protocol for re-indexing — so "
                    + "the vector the search uses is always the vector of the text on screen. "
                    + "Machine and protocol type CANNOT be changed: they are the protocol's "
                    + "identity, not its content (ADR-006 revision). Answers 202 like upload, "
                    + "because indexing is asynchronous.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Corrected; re-indexing runs asynchronously"),
            @ApiResponse(responseCode = "400",
                    description = "No comment (`MODERATION_COMMENT_REQUIRED`), an attempt to change "
                            + "machine or type (`PROTOCOL_IDENTITY_LOCKED`), or an empty/oversized text"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is not a Schichtleiter — including an administrator, who "
                            + "approves rather than corrects"),
            @ApiResponse(responseCode = "404", description = "No such protocol"),
            @ApiResponse(responseCode = "409", description = "The protocol is archived (`PROTOCOL_ARCHIVED`)")
    })
    /*
     * THE ONE EXCEPTION TO THIS CONTROLLER'S ADMIN-ONLY RULE, and it is the trust chain's hinge.
     * It is not a widening of the class rule but a REPLACEMENT of it: this method is open to the
     * Schichtleiter and CLOSED TO THE ADMINISTRATOR.
     *
     * Decision 3 of 2026-08-11: the Techniker writes and never corrects, not even their own; a
     * correction is REQUESTED FROM THE SCHICHTLEITER, who performs it; the Admin approves. Three
     * distinct people, one job each — and the cleanest way to say "nobody does two jobs" is that
     * nobody HOLDS two jobs. Carlos's decision after reviewing #53: the admin stops editing.
     *
     * THE ROLE SPLIT IS THE RULE; THE LEDGER CHECK IS THE BELT. ProtocolApprovalService still
     * refuses an approval by whoever filed or last corrected the protocol, and that check is
     * deliberately KEPT even though no administrator can now reach the edit path at all. It costs
     * one query on an act that happens rarely, it is the only guard that survives a future role
     * widening, and it is the only one that could ever catch "the same human did both" — a role
     * cannot express that. A rule enforced in one place only is a rule one @PreAuthorize edit away
     * from being gone.
     */
    @PreAuthorize("hasRole('SCHICHTLEITER')")
    ResponseEntity<Map<String, String>> edit(@PathVariable UUID id,
                                             @RequestBody ProtocolEditService.Correction correction,
                                             @AuthenticationPrincipal Jwt jwt) {
        return edits.edit(id, correction, jwt.getClaimAsString("preferred_username"))
                .map(edited -> ResponseEntity.accepted().body(Map.of(
                        "id", edited.toString(),
                        "status", "RECEIVED",
                        "message", "Protocol corrected and queued for re-indexing")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a protocol from the corpus and archive it",
            description = "Deletes its chunks — which is what takes it out of every answer, "
                    + "instantly and for every role — then marks it deleted and records who removed "
                    + "it and why. The row and the file stay, readable only through the archive "
                    + "below: removing garbage must not destroy the evidence of who produced it. "
                    + "THERE IS NO RESTORE, by design. Beyond " + ARCHIVE_CAP_TEXT + " archived "
                    + "protocols per machine the oldest are purged for good.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed from the corpus and archived"),
            @ApiResponse(responseCode = "400", description = "No comment (`MODERATION_COMMENT_REQUIRED`)"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "No such protocol, or it is already archived")
    })
    ResponseEntity<Void> delete(@PathVariable UUID id,
                                // A body rather than a query parameter, which is the other obvious
                                // shape. A comment can be a sentence about a named colleague's
                                // mistake, and a query string lands in access logs, proxy logs and
                                // browser history — three places nobody audited for it.
                                @RequestBody DeleteRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        // The username rather than the subject: it is what uploaded_by stores, so the deletion log
        // and the authorship it is judging are written in the same identity.
        boolean removed = moderation.delete(id, jwt.getClaimAsString("preferred_username"),
                request == null ? null : request.comment());
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** @param comment why this protocol is being removed. Required; blank counts as missing. */
    record DeleteRequest(String comment) {
    }

    @PutMapping("/{id}/approval")
    @Operation(summary = "Approve a protocol, or withdraw approval",
            description = "Approval is a STATEMENT, not a gate: an unapproved protocol stays "
                    + "searchable and citable, because the administrator may not review at a "
                    + "weekend and the factory does not stop. What approval changes is what the "
                    + "interface can say about a source. FOUR EYES: whoever filed the protocol, and "
                    + "whoever last corrected it, cannot approve it. Idempotent — approving "
                    + "something already approved succeeds and writes no second audit row. A "
                    + "comment is required to WITHDRAW approval and optional to grant it: taking "
                    + "back something a named person vouched for is the direction that needs "
                    + "explaining.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The resulting approval state"),
            @ApiResponse(responseCode = "400",
                    description = "Withdrawing without a comment (`MODERATION_COMMENT_REQUIRED`), or "
                            + "the caller wrote or corrected this protocol (`FOUR_EYES_REQUIRED`)"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "No such protocol"),
            @ApiResponse(responseCode = "409", description = "The protocol is archived (`PROTOCOL_ARCHIVED`)")
    })
    ResponseEntity<ProtocolApprovalService.Approval> setApproval(
            @PathVariable UUID id,
            @RequestBody ApprovalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return approvals.setApproval(id, request != null && request.approved(),
                        jwt.getClaimAsString("preferred_username"),
                        request == null ? null : request.comment())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @param approved the state being asserted, not a verb. PUT of a state rather than
     *                 POST /approve + POST /unapprove, because "make this approved" is idempotent
     *                 by construction and two endpoints would be two places to keep the four-eyes
     *                 rule in step.
     * @param comment  required to withdraw, optional to grant. In the body rather than a query
     *                 parameter for the same reason the delete comment is: it can name a
     *                 colleague's mistake, and a query string lands in access logs.
     */
    record ApprovalRequest(boolean approved, String comment) {
    }

    @GetMapping("/deleted")
    @Operation(summary = "The archive: protocols that were removed, and why",
            description = "Newest deletion first, paged, optionally narrowed to one machine. Each "
                    + "row carries the administrator who removed it and their comment. This is an "
                    + "audit archive, not a recycle bin — there is no restore endpoint and there is "
                    + "not meant to be one (ADR-006 revision).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of the archive"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator")
    })
    ProtocolModerationService.DeletedProtocolPage deleted(
            @RequestParam(required = false) String machineNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // No machine-first rule here, unlike the live list: there is one optional filter rather than
        // four, so there is no combination that could answer with noise.
        return moderation.listDeleted(machineNo, page, size);
    }

    @GetMapping("/deleted/{id}/document")
    @Operation(summary = "Read the original document of an archived protocol",
            description = "What makes the archive evidence rather than a tombstone. The live "
                    + "document endpoints answer 404 for an archived protocol; this is the one door "
                    + "back to its content, and only an administrator has it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404",
                    description = "No such archived protocol — a live one is not served here either")
    })
    ResponseEntity<Resource> archivedDocument(@PathVariable UUID id) {
        return documents.findArchived(id)
                .map(ModerationController::inline)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A refused moderation request, with a stable code beside the sentence.
     *
     * <p>Two statuses out of one exception, decided by the code. Everything here is a 400 — the
     * caller sent something the endpoint does not accept — except an edit of an archived protocol,
     * which is a 409: the protocol exists, the same administrator can read it in the archive, and
     * answering "no such protocol" would be a lie their own screen contradicts. What is wrong is
     * the state, not the address.
     */
    @ExceptionHandler(ProtocolModerationService.InvalidModerationRequestException.class)
    ResponseEntity<Map<String, String>> onInvalidRequest(
            ProtocolModerationService.InvalidModerationRequestException e) {
        HttpStatus status = ProtocolEditService.PROTOCOL_ARCHIVED.equals(e.code())
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("reason", e.code(), "error", e.getMessage()));
    }

    /**
     * The caller's mistake in the corrected content itself — empty, or past the size cap.
     *
     * <p>Reused from the intake path rather than given a moderation-specific exception: it is the
     * same validation, on the same property, for the same reason, and a second exception type would
     * be a second place for the two to drift apart.
     */
    @ExceptionHandler(ProtocolIntakeService.InvalidProtocolException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> onInvalidContent(ProtocolIntakeService.InvalidProtocolException e) {
        return Map.of("reason", "INVALID_CONTENT", "error", e.getMessage());
    }
}
