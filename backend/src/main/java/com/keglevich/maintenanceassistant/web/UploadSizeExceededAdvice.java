package com.keglevich.maintenanceassistant.web;

import io.swagger.v3.oas.annotations.Hidden;
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
 *
 * <p><b>{@code @Hidden} IS ABOUT THE PUBLISHED DOCUMENT, NOT ABOUT THE RUNTIME.</b> springdoc
 * merges an advice's declared status into every operation the advice can reach, and this advice can
 * reach all of them — so the API published a {@code 413 Content Too Large} on all 19 operations,
 * including thirteen GETs that carry no request body at all. A contract that tells a client to
 * handle a status an endpoint can never return is wrong about that endpoint, which is what the
 * standards' OpenAPI obligation exists to catch. {@code @Hidden} takes this advice out of that
 * merge and changes nothing about when it runs or what it answers; the one operation that CAN
 * answer 413 declares it itself, in {@link ProtocolUploadController}.
 *
 * <p><b>SCOPING WITH {@code assignableTypes} WAS TRIED AND REJECTED, 2026-08-27.</b> It produced
 * exactly the right document and broke the behaviour: {@code assignableTypes} matches on the
 * RESOLVED HANDLER, and by the paragraph above there is no resolved handler when this fires, so the
 * advice simply stopped running and an oversized upload went back to receiving Tomcat's HTML.
 * {@code UploadSizeLimitIT} caught it. The rule that generalises: an advice handling an exception
 * raised before handler resolution cannot be narrowed by handler type, and narrowing what it
 * PUBLISHES is a documentation-generation concern instead.
 */
@Hidden
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
    Map<String, String> onTooLarge(MaxUploadSizeExceededException ignored) {
        return Map.of(
                "reason", "FILE_TOO_LARGE",
                "limit", maxFileSize,
                "error", "The uploaded file is larger than the %s limit.".formatted(maxFileSize));
    }
}
