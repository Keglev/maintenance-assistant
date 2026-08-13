package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolApprovalService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolEditService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolIntakeService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolSimilarityService;
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
 * {@link ProtocolReadController}, because the authorisation rule is the feature.
 *
 * <p><b>THE MATRIX, IN ONE PLACE, because this is the security surface of the whole trust chain and
 * a reader should not have to collect it from seven annotations.</b> The class rule is
 * {@code hasRole('ADMIN')}; every deviation is method-level and listed here.
 *
 * <pre>
 *   GET  /                          ADMIN, SCHICHTLEITER   the corpus list
 *   GET  /{id}/document             ADMIN, SCHICHTLEITER   read one protocol
 *   GET  /{id}/history              ADMIN, SCHICHTLEITER   what was done to it
 *   PUT  /{id}                      SCHICHTLEITER          correct it  (admin refused)
 *   DELETE /{id}                    ADMIN                  archive it
 *   PUT  /{id}/approval             ADMIN                  approve / withdraw
 *   GET  /{id}/similar              ADMIN                  what it may duplicate
 *   GET  /deleted                   ADMIN                  the archive
 *   GET  /deleted/{id}/document     ADMIN                  an archived document
 * </pre>
 *
 * <p><b>The Schichtleiter's three reads exist to serve the one write, and go no further.</b>
 * Correcting is not moderating: you cannot correct what you cannot find, cannot open, or cannot see
 * the recent history of — an edit that silently repeats a correction somebody made last week is the
 * mistake the third one prevents — so the list, the document and the history come with the job, and
 * nothing else does. Removing a protocol from the corpus, approving one, and
 * reading the archive of what was removed are the administrator's, unchanged. The archive in
 * particular holds exactly the protocols somebody decided were unfit to be read, and it stays behind
 * the role that decided it.
 *
 * <p><b>Reading here is not answering.</b> The shop-floor document endpoint is restricted to the
 * three roles that ask questions, because it exists to make a citation checkable. This one exists to
 * let a reviewer read a protocol they are deciding the fate of, or a corrector read the text they
 * are about to rewrite — different acts, on a path an admin reaches without holding a shop-floor
 * role.
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
            @ApiResponse(responseCode = "403",
                    description = "Caller is neither an administrator nor a Schichtleiter")
    })
    /*
     * OPEN TO THE CORRECTOR AS WELL AS THE REVIEWER, and only because the correction needs it.
     *
     * The Schichtleiter has been the only role that may correct a protocol since 2026-08-13, and
     * until 2026-08-14 there was no way for them to FIND one: this list was admin-only, so the
     * permission existed and the path to it did not. A correction endpoint nobody can reach is a
     * correction endpoint that does not exist.
     *
     * It stops here. The list and the document below are what the job needs; delete, approve and the
     * archive stay the administrator's, because correcting is not moderating.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SCHICHTLEITER')")
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
                    + "administrator who holds no shop-floor role, and by the Schichtleiter who is "
                    + "about to correct it. Reviewing is not answering.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is neither an administrator nor a Schichtleiter"),
            @ApiResponse(responseCode = "404", description = "No such protocol, or its file is gone")
    })
    /*
     * The other half of what a correction needs. The edit dialog loads the STORED TEXT rather than
     * reconstructing it from the row — a form pre-filled with anything else would silently replace
     * the document with whatever the form happened to know — so the corrector must be able to read
     * this file before they can safely rewrite it.
     *
     * The Schichtleiter also holds a shop-floor role and could reach the same bytes through
     * /api/protocols/{id}/document. That is not an argument for leaving this one closed: the client
     * would then need two document paths chosen by role, and the reason THIS path exists is that
     * the two differ in who may take them, which is exactly the kind of difference that should stay
     * visible at the call site.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SCHICHTLEITER')")
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

    @GetMapping("/{id}/history")
    @Operation(summary = "What has been done to this protocol, newest act first",
            description = "The moderation ledger for one protocol: corrections, approvals, "
                    + "withdrawals and its removal, each with WHO, WHEN and WHY. Limited "
                    + "SERVER-SIDE to the newest few — the viewer shows a recent history beside a "
                    + "document, not a report — with `total` reported so a client can say that "
                    + "older entries exist rather than presenting the newest as everything. A "
                    + "protocol approved by the V5 seed migration has NO events: the 150 seeded "
                    + "protocols were born approved by a migration and no human act produced them, "
                    + "so the honest answer is an empty list and the approval columns on the "
                    + "protocol itself.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The newest acts, possibly none. An unknown id answers an empty "
                            + "history rather than 404 — the ledger outlives its subject by design, "
                            + "so 'no rows' is a true answer here and not a missing resource"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is neither an administrator nor a Schichtleiter")
    })
    /*
     * THE SAME RULE AS THE DOCUMENT READ, and no wider: ADMIN and SCHICHTLEITER.
     *
     * The corrector gets it for the reason they got the document — you cannot judge a correction
     * you are about to make without knowing what was already done to the protocol, and an edit that
     * silently repeats a correction somebody made last week is the mistake this prevents.
     *
     * IT STOPS THERE, and the shop floor is refused deliberately. Who corrected what, who approved
     * it and who took an approval back is MODERATION information: it names colleagues in connection
     * with mistakes, which is exactly why the delete comment travels in a body rather than a query
     * string (ADR-006). A technician checking a citation needs the protocol's text and its approval
     * STATE, both of which they already have.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SCHICHTLEITER')")
    ProtocolModerationService.ProtocolHistory history(@PathVariable UUID id) {
        return moderation.history(id);
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

    @GetMapping("/{id}/similar")
    @Operation(summary = "Protocols on the same machine that say close to what this one says",
            description = "INFORMATION FOR AN APPROVER, NEVER A GATE. Nothing in this API refuses "
                    + "an approval on a similarity score, and nothing is meant to: the four E-47 "
                    + "protocols on PR-03 are four different root causes behind one fault code, all "
                    + "legitimate, and a threshold sensitive enough to catch a genuine duplicate "
                    + "would flag them as copies of each other. Compared at PROTOCOL level — the "
                    + "mean of a protocol's chunk vectors — against live protocols on the SAME "
                    + "machine; archived ones are excluded. Each candidate carries its OWN approval "
                    + "state, because 'nearly the same as something already vouched for' and "
                    + "'nearly the same as something nobody has reviewed' are different situations. "
                    + "The threshold in force is returned with the result rather than left for a "
                    + "client to hard-code.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The report, possibly with no candidates. `comparable` is false "
                            + "when the protocol has no vectors yet — filed seconds ago, or its "
                            + "indexing failed — which is a different fact from 'nothing similar'"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator")
    })
    /*
     * ADMIN ONLY, deliberately, and it is not an oversight that the corrector cannot reach it. The
     * 2026-08-14 widening opened exactly what a correction needs — the list and the document. This
     * belongs to the approval decision, which is the administrator's, and a corrector who could see
     * it would be reading a signal they have no act to take on.
     *
     * A GET rather than a field on the list row: the comparison is a vector query per protocol, and
     * folding it into the paged list would run it five times per page for four protocols nobody is
     * about to approve.
     */
    ProtocolSimilarityService.SimilarityReport similar(@PathVariable UUID id) {
        return approvals.similarTo(id);
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
     *
     * <p><b>AND IT HAS TO OPT OUT OF THIS CONTROLLER'S CLASS-LEVEL RULE.</b> A class-level
     * {@code @PreAuthorize} covers <em>every</em> method of the class, including this one — so
     * rendering an error for a caller who is not an administrator was itself refused, the resolver
     * treated the handler as unusable, and the original exception escaped as a 500. That made every
     * refused correction by a Schichtleiter — no comment, identity lock, archived, bad content —
     * answer with an empty 500 instead of the documented status and its stable {@code reason} code,
     * which is exactly what the frontend matches on. Found by this branch and fixed here; it has
     * been latent since the Schichtleiter gained the edit in #53.
     *
     * <p>{@code isAuthenticated()} rather than {@code permitAll()}: the caller has already passed
     * the endpoint's own rule to reach the exception, and an error renderer is not an act that
     * needs authorising a second time — but nothing here should be reachable without a token.
     */
    @PreAuthorize("isAuthenticated()")
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
     *
     * <p>Opted out of the class-level rule for the reason spelled out on the handler above: a
     * handler an unauthorised-for-the-class caller may not invoke is a handler that does not run.
     */
    @PreAuthorize("isAuthenticated()")
    @ExceptionHandler(ProtocolIntakeService.InvalidProtocolException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> onInvalidContent(ProtocolIntakeService.InvalidProtocolException e) {
        return Map.of("reason", "INVALID_CONTENT", "error", e.getMessage());
    }
}
