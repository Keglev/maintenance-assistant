package com.keglevich.maintenanceassistant.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One id per request, in every log line the request produces.
 *
 * <p><b>What this is for.</b> Diagnosing the incident of 2026-08-26 07:23 UTC meant lining up a
 * Caddy access line and a backend WARN by their millisecond timestamps across two containers, and
 * hoping no second request had arrived in between. This filter replaces that arithmetic with
 * {@code grep}: every line a request produces carries the same id, and the client gets it back in
 * the response header, so a user reporting "nicht erreichbar" can be asked for one string.
 *
 * <p><b>Generated here, not only accepted from the edge.</b> Caddy is a HAND-DEPLOYED file
 * (OPS RULE 3) and its {@code header_up} rides with the next deploy that has a reason of its own,
 * so an id must exist without it. Reading the header first and generating only when it is absent
 * means correlation works backend-side from the first deploy and gets one hop wider, free, when
 * the Caddyfile lands.
 *
 * <p><b>The inbound header is validated rather than trusted.</b> It reaches the log, and a log
 * line assembled from client-controlled text is where log forging lives — a newline in this value
 * would let a caller write a line of their own into the log. The pattern admits UUIDs and Caddy's
 * own ids and nothing that can break a line; anything else is REPLACED rather than rejected,
 * because a malformed correlation id is not a reason to fail a request that is otherwise fine.
 *
 * <p><b>MDC is cleared in a finally.</b> The container reuses threads, and an id left behind
 * attaches itself to the next request that lands on the same one — which is worse than no id at
 * all, because it is a wrong answer rather than a missing one.
 *
 * <p>ZERO BEHAVIOUR CHANGE: this filter adds a response header and touches nothing else. No status,
 * no body, no route, and it never short-circuits the chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    /**
     * What may be echoed back and written to a log: letters, digits, dot, underscore and hyphen,
     * 8 to 64 characters. A UUID is 36 and Caddy's {@code http.request.uuid} is the same shape.
     */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = acceptableOrGenerated(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        // Set before the chain runs, not after: a handler that commits the response early would
        // otherwise send the headers before this one was added, and the id would be missing from
        // exactly the responses most worth correlating.
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String acceptableOrGenerated(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
