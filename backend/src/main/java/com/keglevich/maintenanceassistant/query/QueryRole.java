package com.keglevich.maintenanceassistant.query;

/**
 * Who is asking, expressed as the only thing the answer depends on: how much of a repair the reader
 * is qualified to carry out.
 *
 * <p>Not a copy of the Keycloak realm roles. {@code admin} is an IT role with no question to ask,
 * and Techniker and Schichtleiter differ in <em>write</em> access, not in what they may be told —
 * so four realm roles collapse to two answer depths here, and the web layer does the mapping. That
 * keeps the query module free of Spring Security and, more usefully, keeps the decision
 * "which roles get full answers" in one readable place instead of spread across prompt strings.
 *
 * <p>This is a safety boundary, not a presentation preference (NFR-3). An Operator is a machine
 * operator: qualified to look, read, clean, refill and restart, and not qualified to work on
 * hydraulics, electrics or anything behind a guard. So the constraint is applied server-side, in the
 * prompt that produces the answer, rather than by hiding text the client already received.
 */
public enum QueryRole {

    /** Operator-safe steps and escalation only — never electrical or mechanical repair. */
    OPERATOR,

    /** Qualified maintenance staff: the full answer, including the repair as it was carried out. */
    TECHNIKER,

    /** The Schichtleiter is a Techniker who may also write; the answer depth is identical. */
    SCHICHTLEITER;

    /** True where the answer must stop at checks and escalation. */
    boolean isOperatorSafeOnly() {
        return this == OPERATOR;
    }
}
