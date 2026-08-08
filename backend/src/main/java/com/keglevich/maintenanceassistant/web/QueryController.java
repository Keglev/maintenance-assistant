package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.query.QueryAnswer;
import com.keglevich.maintenanceassistant.query.QueryRole;
import com.keglevich.maintenanceassistant.query.QueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.Map;
import java.util.UUID;

/**
 * Asking a question about one machine (US-1/US-2, NFR-2).
 *
 * <p>Open to the three shop-floor roles. {@code admin} is deliberately not among them: ADR-003 makes
 * it a Keycloak IT role, and an IT role has no maintenance question to ask — granting it access
 * "because it is the most powerful role" would be exactly the reflex this project avoids elsewhere.
 *
 * <p><b>The role is read from the token, never from the request.</b> What an Operator may be told
 * differs from what a Techniker may be told (NFR-3), so the role is an input to the prompt that
 * produces the answer. A client-supplied role would make that safety boundary a suggestion.
 */
@RestController
@RequestMapping("/api/query")
@Tag(name = "Query", description = "Question answering over the indexed maintenance protocols")
class QueryController {

    private final QueryService queries;

    QueryController(QueryService queries) {
        this.queries = queries;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'TECHNIKER', 'SCHICHTLEITER')")
    @Operation(summary = "Ask a question about one machine",
            description = "Embeds the question, retrieves the closest protocol chunks for that "
                    + "machine and answers in one of two modes. Mode A is grounded: every claim "
                    + "carries the source it came from, validated against what was actually "
                    + "retrieved. Mode B is a labelled general suggestion, returned when no stored "
                    + "protocol is close enough. The answer is written in the language of the "
                    + "question, and its depth depends on the caller's role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answered, in Mode A or Mode B"),
            @ApiResponse(responseCode = "400", description = "Empty question or unknown machine"),
            @ApiResponse(responseCode = "403", description = "Caller holds no shop-floor role"),
            @ApiResponse(responseCode = "429", description = "Per-user rate limit exceeded (NFR-7)"),
            @ApiResponse(responseCode = "503", description = "Provider unavailable or daily budget spent")
    })
    QueryAnswer ask(@RequestBody QueryRequest request,
                    Authentication authentication,
                    @AuthenticationPrincipal Jwt jwt) {

        return queries.ask(
                request.question(),
                request.machineId(),
                roleOf(authentication),
                // The stable Keycloak user id. A username can be renamed in the admin console and
                // would silently hand someone a fresh rate-limit bucket.
                jwt.getSubject());
    }

    /**
     * Four realm roles collapse to three answer depths, and the mapping is stated here rather than
     * inside the query module so that module needs no Spring Security on its classpath.
     *
     * <p>The order matters: someone holding both {@code techniker} and {@code operator} gets the
     * fuller answer, because the constraint exists to protect people who are <em>only</em> operators.
     */
    private static QueryRole roleOf(Authentication authentication) {
        var authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        if (authorities.contains("ROLE_SCHICHTLEITER")) {
            return QueryRole.SCHICHTLEITER;
        }
        if (authorities.contains("ROLE_TECHNIKER")) {
            return QueryRole.TECHNIKER;
        }
        return QueryRole.OPERATOR;
    }

    /**
     * The request.
     *
     * @param question  the user's own words, in their own language. Nothing is translated anywhere
     *                  in this system; the multilingual embedding model is what bridges DE and EN
     * @param machineId search scope, required. Phase 1 searches one machine exactly — a question
     *                  answered from another machine's protocols would look like a better feature
     *                  and be a worse answer
     */
    record QueryRequest(String question, UUID machineId) {
    }

    // ---------------------------------------------------------------------------------------
    // Failures, as statuses a client can act on.
    // ---------------------------------------------------------------------------------------

    @ExceptionHandler(QueryService.InvalidQueryException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> onInvalid(QueryService.InvalidQueryException e) {
        return Map.of("error", e.getMessage());
    }

    /**
     * 429 with {@code Retry-After}. The message says how long to wait rather than only that a limit
     * exists, because a limit with no "try again in n seconds" reads to a user as an outage.
     */
    @ExceptionHandler(QueryService.RateLimitedException.class)
    ResponseEntity<Map<String, String>> onRateLimited(QueryService.RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * 503 for both "no budget left today" and "the provider is not answering". Different causes,
     * the same status: correct answers are temporarily unavailable either way, and the honest
     * response is to say so rather than to serve an ungrounded one.
     *
     * <p>They differ in the one field a client can act on. {@code reason} is a stable code, not
     * prose: to a person, "today's limit" means come back tomorrow and "provider unreachable" means
     * try again in a minute, and a UI that showed one sentence for both would make a spent budget
     * look like an outage. A code rather than a message match, because matching on English prose
     * from another layer breaks the first time someone rewords it.
     */
    @ExceptionHandler(QueryService.BudgetExhaustedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> onBudgetExhausted(QueryService.BudgetExhaustedException e) {
        return Map.of(
                "reason", "BUDGET_EXHAUSTED",
                "error", "The assistant has reached today's answer limit. Retrieval and upload still "
                        + "work; answering resumes tomorrow.",
                "detail", e.getMessage());
    }

    @ExceptionHandler(QueryService.ProviderUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> onProviderUnavailable(QueryService.ProviderUnavailableException e) {
        return Map.of("reason", "PROVIDER_UNAVAILABLE", "error", e.getMessage());
    }
}
