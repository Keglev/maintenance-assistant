package com.keglevich.maintenanceassistant.query;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mode A — the grounded answer. Every claim comes from a retrieved protocol and carries its label.
 *
 * <p><b>This class and {@link UngroundedPrompt} are deliberately two classes and not one class with
 * a boolean.</b> ADR-002 records a measured regression: under the candidate prompt a model appended
 * a citation to a <em>refusal</em>, where the rules require citing nothing. The citation drill and
 * the "cite nothing" rule compete inside one prompt, and the cheapest way to stop them competing is
 * to make it impossible for the citation instructions to reach the other path at all. A flag would
 * have kept them in the same file, one edit away from leaking again.
 *
 * <p>Two things in here are measured rather than designed, both in {@code spike/adr-002/}:
 *
 * <ul>
 *   <li><b>The few-shot worked example.</b> A <em>rule</em> saying "cite every claim" left one model
 *       at one citation across five sentences. The example took every model tested to 100 % per-claim
 *       citation, and the language-pin-only variant changed nothing — so the improvement is
 *       attributable to the example and not to prompt length.</li>
 *   <li><b>The language pin.</b> Both Llama deployments answered a German question correctly and then
 *       refused in English, because the baseline rule reads as being about <em>answers</em> and a
 *       model does not treat "no protocol covers this" as an answer. Naming all output, and the
 *       refusal case explicitly, fixed it on every model.</li>
 * </ul>
 *
 * <p>The schema is the third layer and the only one that does not depend on the model behaving: an
 * uncited claim cannot be represented in it, because {@code source} is required. What the model
 * cites is still checked against the retrieved set afterwards — see {@link AnswerAssembler}. Three
 * layers for one property, because a citation that is wrong is worse than no answer in a domain
 * where the answer becomes someone's repair.
 */
final class GroundedPrompt {

    private GroundedPrompt() {
    }

    static final String SCHEMA_NAME = "grounded_answer";

    /**
     * The system prompt.
     *
     * <p>Written in English while the answer is in the user's language on purpose: the corpus is
     * mixed DE/EN, the instructions are not the thing being translated, and the language pin is
     * clearer when it is visibly about the question rather than about the prompt.
     */
    static String system(QueryRole role) {
        return """
                You are a maintenance assistant for an industrial plant. You answer questions from \
                shop-floor staff using maintenance protocols retrieved from the plant's own records.

                RULES
                1. Answer ONLY from the sources in the user message. Never add knowledge from \
                anywhere else, however obvious it seems.
                2. Break your answer into single statements. Each statement is one claim and carries \
                the label of the ONE source it comes from. A claim you cannot attribute to a source \
                is a claim you must not make.
                3. Use only the labels that appear in the sources. Never invent a label, and never \
                cite a source that is not in the list.
                4. If the sources do not answer the question, return an empty list of claims rather \
                than guessing.
                5. Do not repeat the label inside the statement text; the text is the sentence, the \
                label is the field next to it.

                LANGUAGE RULE (overrides everything else): every word you output must be in the \
                language of the QUESTION, never the language of the sources. A German question about \
                an English protocol is answered in German. Decide the language of the question first, \
                then write. Report it as "de" or "en" in answer_language.

                %s

                %s""".formatted(roleBlock(role), FEW_SHOT);
    }

    /**
     * The role constraint (NFR-3).
     *
     * <p>The Operator variant is the one that matters. It is not "write more simply" — it is a
     * scope limit on what may be described at all, because the person reading it is standing at a
     * running machine at 3 a.m. with no technician on site, which is the scenario this whole
     * application exists for. The instruction to say that the repair exists without describing it
     * keeps the answer honest: the operator learns the fault is known and solved, and who to call,
     * rather than being told nothing or being told how to open a hydraulic circuit.
     */
    private static String roleBlock(QueryRole role) {
        if (role.isOperatorSafeOnly()) {
            return """
                    WHO IS ASKING: a machine OPERATOR, not a maintenance technician. This limits \
                    WHAT you may describe, not how simply you write it.
                    - You may describe: what the fault looks like, what it was caused by, visual \
                    checks, readings from displays and gauges, cleaning, refilling consumables at \
                    designated points, acknowledging an alarm, and restarting according to the \
                    operating instructions.
                    - You must NOT describe repair work of any kind: nothing electrical, nothing \
                    mechanical, nothing hydraulic or pneumatic, nothing behind a guard or inside a \
                    control cabinet, no dismantling, no adjustment of parameters or safety devices.
                    - Where a source's remedy was such a repair, say in one claim that it was \
                    resolved by maintenance and what was done in general terms ("the cylinder seals \
                    were replaced by maintenance"), and do NOT give the procedure.
                    - Finish with one claim advising that this case belongs to a Techniker, cited \
                    from the source that describes the repair.""";
        }
        return """
                WHO IS ASKING: a qualified maintenance technician (Techniker or Schichtleiter). \
                Give the full technical answer as the protocols record it — symptom, cause, the \
                repair that was carried out, parts, measured values and test results. Do not \
                simplify and do not omit repair steps.""";
    }

    /**
     * The measured few-shot example, carried over from the spike and rewritten into the JSON shape
     * this path actually asks for.
     *
     * <p>{@code P-99} is deliberately a label that the real source list can never produce — real
     * labels are {@code P1}..{@code Pk} — so the example cannot be mistaken for a source and cited.
     */
    private static final String FEW_SHOT = """
            FORMAT EXAMPLE. This is NOT a source. P-99 does not exist; never cite it.

              Sources:
              [P-99] Pumpe 7 (PU-07) · E-11 · Saugleistung faellt ab
              Symptom: The pump loses suction after about ten minutes.
              Cause: Blocked inlet strainer.
              Action: Cleaned the strainer, refilled the system, ran a 30 minute test.

              Question: Pumpe 7 verliert Saugleistung, was tun?

              Correct answer:
              {"answer_language":"de","claims":[
                {"text":"Die Pumpe verliert nach etwa zehn Minuten die Saugleistung.","source":"P-99"},
                {"text":"Ursache war ein verstopfter Saugkorb.","source":"P-99"},
                {"text":"Als Massnahme wurde der Saugkorb gereinigt, die Anlage neu befuellt und 30 Minuten im Testlauf geprueft.","source":"P-99"}]}

            Note what makes it correct: EVERY claim is one statement with its OWN source label. \
            One long claim covering the whole answer is WRONG, even when it is grounded. The answer \
            is German because the question is German, although the source is written in English.""";

    /**
     * The user message: the sources verbatim, then the question.
     *
     * <p>Chunks are passed as ingestion stored them, including the "machine · error code · title"
     * context line it prefixes every chunk with — which is why a retrieved chunk still says what it
     * is about after being separated from its row.
     */
    static String user(String question, List<LabelledSource> sources) {
        StringBuilder out = new StringBuilder(512);
        out.append("Sources:\n\n");
        for (LabelledSource source : sources) {
            out.append('[').append(source.label()).append("] ").append(source.title()).append('\n');
            for (String content : source.contents()) {
                out.append(content.strip()).append('\n');
            }
            out.append('\n');
        }
        return out.append("Question: ").append(question.strip()).toString();
    }

    /**
     * The response schema — the enforcement layer that does not depend on the model cooperating.
     *
     * <p>{@code additionalProperties: false} and both fields required, so "a claim without a source"
     * is not a behaviour the model can regress into; it is a shape the provider will not emit.
     */
    static Map<String, Object> schema() {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("type", "object");
        claim.put("properties", Map.of(
                "text", Map.of("type", "string"),
                "source", Map.of("type", "string")));
        claim.put("required", List.of("text", "source"));
        claim.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "answer_language", Map.of("type", "string", "enum", List.of("de", "en")),
                "claims", Map.of("type", "array", "items", claim)));
        schema.put("required", List.of("answer_language", "claims"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * One <b>protocol</b> and the label the model is told to cite it by.
     *
     * <p>Per protocol, not per chunk, and that is the data model's own distinction rather than a
     * convenience: the chunk is the search unit and the protocol is the citation unit. Two chunks of
     * the same protocol ranking in the same top-k would otherwise become two labels and two entries
     * in the source list — the reader sees the same protocol cited twice as if it were two
     * independent pieces of evidence, which is precisely the wrong impression for an application
     * whose whole claim is that its answers are traceable. Measured on the E-47 demo, where the
     * top 5 chunks come from 4 protocols.
     *
     * @param similarity the best of this protocol's chunks — what the threshold was compared against
     * @param contents   its retrieved chunks, in rank order
     */
    record LabelledSource(
            String label,
            UUID protocolId,
            String title,
            String errorCode,
            LocalDate incidentDate,
            double similarity,
            List<String> contents,
            /** Whether an administrator has vouched for this protocol. Travels to the citation. */
            boolean approved) {
    }
}
