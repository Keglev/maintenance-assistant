package com.keglevich.maintenanceassistant.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Turns an oversized upload into JSON the client can read.
 *
 * <p><b>Why this is global advice and not an {@code @ExceptionHandler} on the upload controller.</b>
 * The container refuses an oversized multipart while <em>parsing the request</em>, which happens
 * before the dispatcher has resolved a handler — so there is no controller for a controller-local
 * handler to belong to, and one written there is never invoked. Global advice is consulted even when
 * the handler is null, which is the only reason this fires at all.
 *
 * <p>Without it the answer is the container's own HTML error page: the upload view would receive a
 * blob of markup where it expects a body it can read, and would show its generic failure message for
 * a problem that has a precise, actionable explanation. The limit is stated with a number because
 * "too large" without one leaves the writer guessing how much to cut.
 *
 * <p>This is the CONTAINER's ceiling ({@code spring.servlet.multipart.max-file-size}). The decoded
 * text has a second, lower ceiling in the ingestion module which reports itself as a 400; both are
 * real and they guard different things — bytes on the wire, and characters reaching the embedder.
 */
@RestControllerAdvice
class UploadSizeExceededAdvice {

    /**
     * The configured limit, as configured — {@code 256KB} rather than {@code 262144}.
     *
     * <p>Read from the property rather than from the exception, and measured rather than assumed:
     * {@code MaxUploadSizeExceededException.getMaxUploadSize()} returns <b>-1</b> here, so the
     * obvious implementation produced "larger than the -1 byte limit". The property is also the
     * friendlier number — a person cutting a file down thinks in KB.
     */
    private final String maxFileSize;

    UploadSizeExceededAdvice(@Value("${spring.servlet.multipart.max-file-size}") String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    // CONTENT_TOO_LARGE, not PAYLOAD_TOO_LARGE: same 413, and the older constant is deprecated as of
    // Spring 7 (the RFC renamed the status).
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    Map<String, String> onTooLarge(MaxUploadSizeExceededException e) {
        return Map.of(
                "reason", "FILE_TOO_LARGE",
                "limit", maxFileSize,
                "error", "The uploaded file is larger than the %s limit.".formatted(maxFileSize));
    }
}
