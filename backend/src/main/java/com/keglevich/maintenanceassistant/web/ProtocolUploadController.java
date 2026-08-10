package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolIntakeService;
import com.keglevich.maintenanceassistant.ingestion.UploadContentPolicy;
import com.keglevich.maintenanceassistant.ingestion.UploadRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.util.Map;
import java.util.UUID;

/**
 * Protocol upload. Schichtleiter only.
 *
 * <p>Write access is restricted to one role by decision, not by oversight: DECISIONS.txt makes the
 * Schichtleiter the sole writer as a quality control — a corpus anyone can add to is a corpus
 * nobody trusts. The check is server-side and role-based, so hiding the button in the UI is
 * presentation and this is the actual rule.
 *
 * <p>Returns <b>202 Accepted</b> rather than 201. The protocol exists when this returns, but it is
 * not searchable yet — chunking and embedding happen on a worker (NFR-4: confirmation is
 * immediate). 202 says exactly that, and the returned id is what a client polls with.
 */
@RestController
@RequestMapping("/api/protocols")
@Tag(name = "Ingestion", description = "Upload and indexing of maintenance protocols")
class ProtocolUploadController {

    private final ProtocolIntakeService intake;
    private final UploadRateLimiter rateLimiter;

    ProtocolUploadController(ProtocolIntakeService intake, UploadRateLimiter rateLimiter) {
        this.intake = intake;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SCHICHTLEITER')")
    @Operation(summary = "Upload a protocol text file",
            description = "Stores the document on the volume, records it as RECEIVED and hands it "
                    + "to the indexer. Text files only — PDF and scan extraction is a later phase.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted; indexing runs asynchronously"),
            @ApiResponse(responseCode = "400", description = "Unknown machine, bad field, or not UTF-8 text"),
            @ApiResponse(responseCode = "403", description = "Caller is not a Schichtleiter"),
            @ApiResponse(responseCode = "413", description = "File larger than the configured limit"),
            @ApiResponse(responseCode = "429", description = "Too many uploads; see Retry-After")
    })
    ResponseEntity<Map<String, String>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("machine") String machineNo,
            @RequestParam("type") String protocolType,
            @RequestParam(value = "errorCode", required = false) String errorCode,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "language", required = false) String language,
            @AuthenticationPrincipal Jwt jwt) {

        // The username claim, per ADR-003. There is no user row for this to reference — and it is
        // the same identity the resulting row is attributed to, which is why the limit is keyed on it.
        String uploadedBy = jwt.getClaimAsString("preferred_username");
        // Before anything is read or written: a burst that is going to be refused should be refused
        // at its cheapest point.
        rateLimiter.check(uploadedBy);

        // Everything below happens before a row exists and before a byte reaches the volume, so a
        // rejected upload leaves nothing behind.
        UploadContentPolicy.verify(file.getOriginalFilename(), bytesOf(file));
        String content = readAsText(file);

        UUID id = intake.accept(new ProtocolIntakeService.NewProtocol(
                machineNo,
                protocolType,
                errorCode,
                // A note typed on a tablet often has no title of its own; the file name is what the
                // person actually called it, and is better than an empty column.
                title != null && !title.isBlank() ? title : stripExtension(file.getOriginalFilename()),
                language,
                content,
                uploadedBy));

        return ResponseEntity.accepted().body(Map.of(
                "id", id.toString(),
                "status", "RECEIVED",
                "message", "Protocol stored and queued for indexing"));
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Strict UTF-8. A PDF fails here rather than being stored as bytes that look like text —
     * refusing an unsupported format is more honest than accepting it and indexing noise.
     */
    private static String readAsText(MultipartFile file) {
        try {
            return ProtocolIntakeService.decodeStrictUtf8(file.getBytes());
        } catch (CharacterCodingException e) {
            throw new ProtocolIntakeService.InvalidProtocolException(
                    "only UTF-8 text files are accepted; PDF and scan extraction is not implemented yet");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Protokoll";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /** The caller's mistake, reported as one. */
    @ExceptionHandler(ProtocolIntakeService.InvalidProtocolException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> onInvalid(ProtocolIntakeService.InvalidProtocolException e) {
        return Map.of("error", e.getMessage());
    }

    /**
     * A refused upload, with a stable code beside the sentence.
     *
     * <p>The code is what the frontend translates. Matching on English prose from another layer
     * breaks the first time someone rewords it — the same reasoning as the query path's
     * {@code reason} field, and the same contract.
     */
    @ExceptionHandler(UploadContentPolicy.RejectedUploadException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> onRejected(UploadContentPolicy.RejectedUploadException e) {
        return Map.of("reason", e.code(), "error", e.getMessage());
    }

    /** 429 with {@code Retry-After}, matching the query path exactly. */
    @ExceptionHandler(UploadRateLimiter.UploadRateLimitExceededException.class)
    ResponseEntity<Map<String, String>> onRateLimited(
            UploadRateLimiter.UploadRateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(Map.of("reason", "RATE_LIMITED", "error", e.getMessage()));
    }

}
