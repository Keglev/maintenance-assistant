package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolIntakeService;
import com.keglevich.maintenanceassistant.ingestion.UploadContentPolicy;
import com.keglevich.maintenanceassistant.ingestion.UploadRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * Protocol upload. Techniker and Schichtleiter.
 *
 * <p>Write access is restricted by decision, not by oversight, and the decision moved: DECISIONS.txt
 * made the Schichtleiter the sole writer as a quality control — a corpus anyone can add to is a
 * corpus nobody trusts — and decision 3 of 2026-08-11 added the Techniker, because the person who
 * fixed the machine is the person who knows what happened to it. CORRECTING DID NOT MOVE WITH IT
 * and is still the Schichtleiter's alone; see the block comment on the handler. The check is
 * server-side and role-based, so hiding the button in the UI is presentation and this is the actual
 * rule.
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
    /*
     * THE TECHNIKER MAY WRITE NOW — decision 3 of 2026-08-11, and a widening of what this endpoint
     * used to allow rather than a restatement of it.
     *
     * The technician is the person standing at the machine when it is fixed. Requiring them to
     * dictate the protocol to a Schichtleiter is how a plant ends up with protocols written by
     * someone who was not there, and it is exactly the friction that stops them being written at
     * all. So writing moves to the person with the knowledge.
     *
     * What does NOT move is correcting. A Techniker may never edit any protocol, including their
     * own: the value of a correction is that a second person looked. That refusal needs no code
     * here — every edit path lives under /api/moderation, which no shop-floor role can reach — but
     * it is stated here because "may write" and "may fix what they wrote" are the same permission
     * in most systems, and in this one they are deliberately not.
     */
    @PreAuthorize("hasAnyRole('TECHNIKER', 'SCHICHTLEITER')")
    @Operation(summary = "Upload a protocol text file",
            description = "Stores the document on the volume, records it as RECEIVED and hands it "
                    + "to the indexer. Text files only — PDF and scan extraction is a later phase.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted; indexing runs asynchronously"),
            @ApiResponse(responseCode = "400", description = "Unknown machine, bad field, or not UTF-8 text"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is neither a Techniker nor a Schichtleiter"),
            // THE ONLY OPERATION IN THIS API THAT CAN ANSWER 413, and it says so here rather than
            // inheriting it. UploadSizeExceededAdvice must stay GLOBAL — the container refuses an
            // oversized multipart while parsing the request, before a handler is resolved — and
            // springdoc used to merge its status into all 19 operations, thirteen of which are GETs
            // that carry no body. The advice is @Hidden from that merge now, so this declaration is
            // what keeps the status published where it is real. The schema is the advice's own body.
            @ApiResponse(responseCode = "413",
                    description = "File larger than the container limit set by "
                            + "spring.servlet.multipart.max-file-size. The body names the limit, "
                            + "because \"too large\" without a number leaves the writer guessing "
                            + "how much to cut.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = FileTooLarge.class))),
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
        } catch (CharacterCodingException ignored) {
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

    /**
     * The 413 body, for the document only.
     *
     * <p>DOCUMENTATION, NOT A RETURN TYPE. The body is actually built by
     * {@link UploadSizeExceededAdvice} as a {@code Map}, and it must stay a Map there: that advice
     * runs when no handler has been resolved, so it cannot be given this controller's types to work
     * with. This record exists so the published schema shows the three fields a client will
     * actually receive instead of a bare {@code object}, and it is deliberately the only place the
     * two shapes are stated together — if the advice's map changes, this changes with it.
     *
     * @param reason machine-readable, always {@code FILE_TOO_LARGE}; the code the frontend
     *               translates, for the same reason as every other reason code in this API
     * @param limit  the configured limit as configured, e.g. {@code 256KB} rather than 262144
     * @param error  the human sentence, naming the limit
     */
    @Schema(name = "FileTooLarge", description = "The body of a 413 from this endpoint")
    record FileTooLarge(
            @Schema(example = "FILE_TOO_LARGE") String reason,
            @Schema(example = "256KB") String limit,
            @Schema(example = "The uploaded file is larger than the 256KB limit.") String error) {
    }
}
