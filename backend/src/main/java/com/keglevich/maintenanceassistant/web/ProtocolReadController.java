package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Reading protocols back: the source document behind a citation, and the fate of one's own uploads.
 *
 * <p>Separate from {@link ProtocolUploadController} because the authorisation rules are genuinely
 * different, not because the paths are. Writing is Schichtleiter-only by decision (DECISIONS.txt:
 * quality, consistency, anti-garbage). Reading a source is open to every shop-floor role, and has to
 * be: Mode A's promise is that each claim names a protocol the reader can open and check, and a
 * citation nobody may follow is a citation they have to take on trust — which is the state this
 * whole application exists to end.
 */
@RestController
@RequestMapping("/api/protocols")
@Tag(name = "Ingestion", description = "Upload and indexing of maintenance protocols")
class ProtocolReadController {

    private final ProtocolDocumentService documents;
    private final ProtocolStatusService statuses;

    ProtocolReadController(ProtocolDocumentService documents, ProtocolStatusService statuses) {
        this.documents = documents;
        this.statuses = statuses;
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("hasAnyRole('OPERATOR', 'TECHNIKER', 'SCHICHTLEITER')")
    @Operation(summary = "Download the original document a citation points at",
            description = "Streams the stored file from the volume. This is what makes a Mode A "
                    + "citation checkable rather than decorative.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document"),
            @ApiResponse(responseCode = "403", description = "Caller holds no shop-floor role"),
            @ApiResponse(responseCode = "404", description = "No such protocol, or its file is gone")
    })
    ResponseEntity<Resource> document(@PathVariable UUID id) {
        return documents.find(id)
                .map(document -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(document.contentType()))
                        // Inline: following a citation should open the source, not start a download.
                        // The filename still travels, so saving it produces something readable.
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                                .filename(document.downloadName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                        .body(document.resource()))
                // A protocol whose row exists but whose file does not is the same 404 as an unknown
                // id. Distinguishing them in the response would describe the database to whoever
                // asked, and there is nothing the caller could do differently either way.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('SCHICHTLEITER')")
    @Operation(summary = "What happened to the protocols you uploaded",
            description = "The 50 most recent uploads of the calling user, newest first, with "
                    + "status RECEIVED / INDEXED / FAILED and the failure reason. Upload answers "
                    + "202, so this is where a document becoming searchable is actually visible.")
    @ApiResponse(responseCode = "403", description = "Caller is not a Schichtleiter")
    List<ProtocolStatusService.UploadStatus> myUploads(@AuthenticationPrincipal Jwt jwt) {
        // The username claim, matching what the upload path writes into uploaded_by (ADR-003).
        // Taken from the token and never from a parameter: "show me someone else's uploads" is not
        // a request this endpoint should be able to express.
        return statuses.findRecentUploadsOf(jwt.getClaimAsString("preferred_username"));
    }
}
