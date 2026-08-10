package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
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
 * {@code /api/moderation} is admin-only, and a reader can see that from the path.
 *
 * <p><b>Reading here is not answering.</b> The shop-floor document endpoint is restricted to the
 * three roles that ask questions, because it exists to make a citation checkable. This one exists to
 * let a reviewer read a protocol they are deciding the fate of — a different act, on a path an admin
 * reaches without holding a shop-floor role.
 */
@RestController
@RequestMapping("/api/moderation/protocols")
@Tag(name = "Moderation", description = "Administrator review and removal of protocols")
@PreAuthorize("hasRole('ADMIN')")
class ModerationController {

    private final ProtocolModerationService moderation;
    private final ProtocolDocumentService documents;

    ModerationController(ProtocolModerationService moderation, ProtocolDocumentService documents) {
        this.moderation = moderation;
        this.documents = documents;
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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // The record's constructor is where the machine-first rule lives, so it holds for any caller
        // rather than for this method signature only.
        return moderation.list(page, size,
                new ProtocolModerationService.ProtocolFilter(machineNo, titleContains, from, to));
    }

    /**
     * A filter combination the endpoint does not accept, reported with a stable code.
     *
     * <p>The code is what the frontend matches on — the same contract as the upload guards and the
     * query path's {@code reason} field, and for the same reason: rewording an English sentence on
     * the server must not silently change what the German interface says.
     */
    @ExceptionHandler(ProtocolModerationService.InvalidFilterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> onInvalidFilter(ProtocolModerationService.InvalidFilterException e) {
        return Map.of("reason", e.code(), "error", e.getMessage());
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
        return documents.find(id)
                .map(document -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(document.contentType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                                .filename(document.downloadName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                        .body(document.resource()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a protocol from the corpus, permanently",
            description = "Deletes its chunks, its row and its file. There is no undo and no soft "
                    + "delete: correcting a protocol is delete-then-reupload, so that no answer can "
                    + "cite text that changed underneath it (ADR-006).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "No such protocol")
    })
    ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        // The username rather than the subject: it is what uploaded_by stores, so the deletion log
        // and the authorship it is judging are written in the same identity.
        boolean removed = moderation.delete(id, jwt.getClaimAsString("preferred_username"));
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
