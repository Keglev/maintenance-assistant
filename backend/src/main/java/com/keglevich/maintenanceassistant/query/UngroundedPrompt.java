package com.keglevich.maintenanceassistant.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mode B — "Allgemeiner Vorschlag, keine Quelle im Bestand": labelled, ungrounded troubleshooting.
 *
 * <p><b>Nothing in this file mentions citations, sources, labels or protocols as things to point
 * at</b>, and that is the entire design. ADR-002 recorded citation leakage into a refusal, so the
 * fix here is structural rather than instructional: this prompt never teaches a citation format, and
 * the schema it asks for has no {@code source} field, so a citation is not a rule the model might
 * break — it is a shape it cannot emit. See {@link GroundedPrompt} for the other half of that
 * reasoning.
 *
 * <p>Mode B is <em>not</em> a refusal in the sense of saying nothing. DECISIONS.txt defines it as a
 * general suggestion that is honest about being one: the night shift with no technician on site is
 * better served by "no protocol covers this, here is what is generally worth checking, and here is
 * who to call" than by a blank page. The visual distinction that keeps that honest is the frontend's
 * job; making the label unavoidable in the text is this prompt's.
 */
final class UngroundedPrompt {

    private UngroundedPrompt() {
    }

    static final String SCHEMA_NAME = "general_suggestion";

    /**
     * The system prompt for the ungrounded path, narrowed to what this role may be told.
     *
     * <p>ROLE-DEPENDENT BECAUSE MODE B IS THE RISKIER MODE: nothing here is backed by a protocol,
     * so an operator gets operator-safe steps and escalation advice only (NFR-3, ADR-006). The
     * grounded path can lean on citations a reader checks; this one cannot, which is why the
     * narrowing lives in the prompt rather than in a filter over the answer.
     */
    static String system(QueryRole role) {
        return """
                You are a maintenance assistant for an industrial plant. The plant's records contain \
                NO protocol covering the question you are about to answer. You are answering from \
                general engineering knowledge only.

                RULES
                1. Your FIRST step must state plainly that no protocol in the plant's records covers \
                this case, so the reader knows the rest is a general suggestion and not documented \
                plant experience.
                2. The remaining steps are general troubleshooting, ordered from the simplest and \
                safest to the most involved.
                3. Never refer to a protocol, a document, a record, a case, a source or an \
                identifier of any kind. There are none. Do not invent one and do not write anything \
                in square brackets.
                4. Be concrete and short. One action per step, one or two sentences each. Give \
                FOUR TO SIX steps in total, including the statement in step 1 — so at least three \
                actual troubleshooting steps after it. Do not pad the list; four good steps beat \
                ten vague ones.

                LANGUAGE RULE (overrides everything else): every word you output must be in the \
                language of the QUESTION. This includes the statement in step 1 that nothing is \
                documented — a German question gets a German answer throughout, with no exception. \
                Decide the language of the question first, then write. Report it as "de" or "en" in \
                answer_language.

                %s

                %s""".formatted(roleBlock(role), FORMAT_EXAMPLE);
    }

    /**
     * The role constraint (NFR-3), and the stricter of the two paths for the Operator.
     *
     * <p>Mode A at least has a protocol saying that a given repair solved this fault on this
     * machine. Here there is nothing: an ungrounded repair procedure handed to an operator would be
     * a guess about live equipment, which is the one output this application must never produce. So
     * for an Operator, Mode B is checks and escalation, full stop — the request's own words for it.
     */
    private static String roleBlock(QueryRole role) {
        if (role.isOperatorSafeOnly()) {
            return """
                    WHO IS ASKING: a machine OPERATOR, not a maintenance technician. Nothing here is \
                    documented, so the limit is absolute:
                    - Steps may ONLY be things an operator does in normal operation: looking, \
                    listening, reading displays and gauges, checking that guards and covers are \
                    closed, checking supply of material or consumables, cleaning accessible \
                    surfaces, acknowledging an alarm, and restarting according to the operating \
                    instructions.
                    - Give NO repair procedure of any kind, not even a general one, and no \
                    instruction that involves electrics, hydraulics, pneumatics, tools, dismantling, \
                    parameters or anything behind a guard.
                    - The LAST step must be to escalate: stop, secure the machine if the operating \
                    instructions say so, and inform a Techniker or the Schichtleiter with what was \
                    observed.""";
        }
        return """
                WHO IS ASKING: a qualified maintenance technician (Techniker or Schichtleiter). \
                Technical diagnostic steps are appropriate — measurements, component checks, likely \
                causes to eliminate in order. Keep it a diagnostic path rather than a repair \
                instruction, since nothing here is documented for this machine.""";
    }

    /**
     * A format example — and note what is <em>not</em> in it: no source, no label, no protocol, no
     * square brackets. This is the shape rule only, and it exists because of a measured failure
     * rather than by symmetry with {@link GroundedPrompt}.
     *
     * <p>MEASURED 2026-08-07 on the Mode B demo case. Llama-3.3-70B emitted
     * {@code { "answer_language": "de",} and then spent its entire output budget on whitespace,
     * twice, at two different caps — a schema-constrained decoder with nothing telling it what
     * "finished" looks like. Mode A never did this, and the only structural difference between the
     * two prompts was that Mode A carries a worked example of compact JSON. Adding one here fixed
     * it. Raising the cap did not, and could not: the answer was never getting longer, only later.
     */
    private static final String FORMAT_EXAMPLE = """
            FORMAT EXAMPLE, for shape only — the machine and the steps below are invented and have \
            nothing to do with the question. Answer in ONE LINE of compact JSON, exactly like this:

            {"answer_language":"de","steps":["Zu dieser Frage liegt kein Protokoll im Bestand vor; \
            die folgenden Schritte sind ein allgemeiner Vorschlag.","Anlage im Stillstand auf \
            sichtbare Leckagen und lose Verbindungen prüfen.","Betriebsdaten am Display mit den \
            Sollwerten vergleichen.","Bei unverändertem Verhalten den Techniker mit den \
            beobachteten Werten informieren."]}

            Stop after the closing bracket. Do not indent, do not add blank lines, and do not \
            write anything before or after the JSON.""";

    /**
     * The user turn: the question, with the instruction to answer in its own language.
     *
     * <p>The answer is pinned to the QUESTION's language and never to the corpus's — a German
     * question gets a German suggestion even where the model was steered by English text. Kept
     * separate from {@link #system(QueryRole)} so the role rules and the per-question part cannot
     * be edited into one string that drifts.
     */
    static String user(String question) {
        return "Question: " + question.strip();
    }

    /**
     * The schema, and the reason a leaked citation is unrepresentable here: there is nowhere to put
     * one. Steps are plain strings; the object has no source field and forbids extra properties.
     */
    static Map<String, Object> schema() {
        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("type", "array");
        steps.put("items", Map.of("type", "string"));
        // The same rule as step 4 of the prompt, stated where it binds. MEASURED 2026-08-07:
        // asked in prose for at most six steps, Llama-3.3-70B ran past a 1200-token cap on the
        // Mode B demo case and came back as truncated JSON — twice. Mode A never did, and the
        // difference is that Mode A's few-shot example shows the model what "done" looks like
        // while an open-ended list of general advice has no natural end. Capping it in the schema
        // makes the limit a shape rather than a request.
        steps.put("maxItems", 6);
        // A floor as well as a ceiling. Step 1 is the "nothing is documented" statement, so an
        // answer of one or two steps is a refusal wearing a list's clothes — technically valid
        // against the schema and useless to the night shift this mode exists for. Four is the
        // disclaimer plus three real steps.
        steps.put("minItems", 4);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "answer_language", Map.of("type", "string", "enum", List.of("de", "en")),
                "steps", steps));
        schema.put("required", List.of("answer_language", "steps"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
