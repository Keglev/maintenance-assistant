/**
 * Query module — everything that turns a question into a verifiable answer.
 *
 * <p>Responsibility: embed the incoming question, run the machine-filtered nearest-neighbour
 * search over the chunk table in a single SQL statement (ADR-004), apply the similarity threshold
 * that decides between the two answer modes, build the prompt including the caller's role
 * constraint, and assemble the answer together with its citations.
 *
 * <p>The two modes are runtime behaviour, not stored state (NFR-2): hits above the threshold
 * produce Mode A "Belegte Antwort" with a citation per claim; no hit above it produces Mode B
 * "Allgemeiner Vorschlag — keine Quelle im Bestand", explicitly labelled as ungrounded. Role-based
 * answer filtering happens here and on the server only — an Operator never receives electrical or
 * mechanical repair steps (NFR-3).
 *
 * <p>The NFR-7 cost guards (per-user rate limit, daily budget counter, capped answer length, query
 * cache) also belong to this module.
 *
 * <p>Reading order, if you are here for the first place a decision was made:
 * {@link com.keglevich.maintenanceassistant.query.QueryService} is the whole path in one method;
 * {@link com.keglevich.maintenanceassistant.query.ChunkRetriever} is the ADR-004 single-statement
 * search; {@link com.keglevich.maintenanceassistant.query.GroundedPrompt} and
 * {@link com.keglevich.maintenanceassistant.query.UngroundedPrompt} are the two modes, separate by
 * design rather than by accident; and
 * {@link com.keglevich.maintenanceassistant.query.AnswerAssembler} is where a citation the model
 * invented stops being part of the answer.
 */
package com.keglevich.maintenanceassistant.query;
