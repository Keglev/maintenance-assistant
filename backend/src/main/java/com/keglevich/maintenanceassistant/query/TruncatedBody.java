package com.keglevich.maintenanceassistant.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * The anatomy of an answer that stopped at the max-tokens cap.
 *
 * <p><b>Why a shape and not a preview.</b> The incident of 2026-08-26 07:23 UTC was logged as
 * "finish_reason=length at the 1200-token cap" with the FIRST characters of the body, and the
 * natural reading of that — a long answer that ran out of room — could not be checked from the log.
 * The measurement that followed made it doubtful: the deployed model REFUSES that question in ten
 * tokens, six times of six, so a 1200-token body is unlikely to have been an answer at all. The
 * standing hypothesis (PROJECT-PHASES, diagnostics wave A4) is a constrained-decoding degeneration
 * AFTER the refusal — under a strict json_schema the whitespace between JSON tokens is unbounded,
 * so a model that has already emitted an empty claims list can run to the cap emitting padding.
 *
 * <p>A first-characters preview cannot tell those two apart, because they have the same first
 * characters. THE TAIL AND THE WHITESPACE RATIO CAN: a ratio near 1 with a tail of spaces is the
 * degeneration, and a tail of German prose is a genuinely long answer that needs a bigger cap.
 * {@link #refusalShaped()} answers the third question — whether what came back was the refusal the
 * model had already decided on.
 *
 * <p>THIS RECORD IS A DIAGNOSIS AND CHANGES NOTHING. It is built beside the throw it describes and
 * logged; the exception, the status and the response body are exactly what they were. Acting on it
 * is W-3's job, and doing it here would be deciding the answer before the evidence is in.
 *
 * @param completionTokens as the provider reported it; the cap it hit is in the message beside this
 * @param characters       length of the partial content
 * @param whitespaceRatio  whitespace characters over total, two decimals
 * @param tail             the last 200 characters, newlines escaped so the log line stays one line
 * @param refusalShaped    whether the partial content is an empty-claims answer with a brace added
 */
record TruncatedBody(long completionTokens, int characters, double whitespaceRatio,
                     String tail, boolean refusalShaped) {

    private static final int TAIL_LENGTH = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Measures a truncated response body so the WARN can be read without the body itself.
     *
     * <p>THE ANATOMY IS THE EVIDENCE FOR A4: a whitespace ratio near 1 with a refusal-shaped tail
     * says the model emitted its empty claims list and then padded to the cap, which a bigger cap
     * would not fix; a tail of real prose says the answer genuinely ran out of room, which it
     * would. Only the last {@code TAIL_LENGTH} characters are kept, because a log line is not a
     * place to put a user's document.
     */
    static TruncatedBody of(String content, long completionTokens) {
        String body = content == null ? "" : content;
        int whitespace = 0;
        for (int i = 0; i < body.length(); i++) {
            if (Character.isWhitespace(body.charAt(i))) {
                whitespace++;
            }
        }
        // Two decimals, ROOT locale: this number is grepped, and a comma decimal separator on a
        // German developer machine would make the same log line unsearchable.
        double ratio = body.isEmpty() ? 0.0
                : Double.parseDouble(String.format(Locale.ROOT, "%.2f", (double) whitespace / body.length()));
        return new TruncatedBody(completionTokens, body.length(), ratio, tailOf(body), isRefusalShaped(body));
    }

    /**
     * Whether the partial body is the refusal the model had already committed to.
     *
     * <p>One closing brace is added when the body does not end in one, because a truncated JSON
     * object is missing exactly that and nothing else when the truncation happened inside trailing
     * whitespace. Anything that needed more repair than a brace is not refusal-shaped, and this
     * deliberately does not try to find out how much more: a repair that guesses would turn the
     * diagnosis into an assumption.
     */
    private static boolean isRefusalShaped(String body) {
        String candidate = body.strip();
        if (candidate.isEmpty()) {
            return false;
        }
        if (!candidate.endsWith("}")) {
            candidate = candidate + "}";
        }
        try {
            JsonNode node = JSON.readTree(candidate);
            JsonNode claims = node.get("claims");
            return claims != null && claims.isArray() && claims.isEmpty();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Unparseable even with the brace: whatever it is, it is not the refusal shape. The
            // exception is swallowed on purpose — this method runs while another exception's
            // message is being assembled, and a diagnosis helper must never replace the failure
            // it was called to describe.
            return false;
        }
    }

    /** Newlines escaped rather than stripped: a log line that wraps is a log line nobody greps. */
    private static String tailOf(String body) {
        String tail = body.length() <= TAIL_LENGTH ? body : body.substring(body.length() - TAIL_LENGTH);
        return tail.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * One line, in the order a reader asks the questions: how much, how empty, what, and was it the
     * refusal.
     *
     * <p>ROOT LOCALE, and it is not a formality — this was written without one first and the test
     * caught it: {@code %.2f} on a German machine prints {@code 0,96}, so the line the ledger tells
     * the next reader to grep for ({@code whitespaceRatio > 0.9}) would not have matched the line
     * this method produces. A diagnosis that is only searchable in some locales is not a diagnosis.
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "completionTokens=%d characters=%d whitespaceRatio=%.2f refusalShaped=%s tail=\"%s\"",
                completionTokens, characters, whitespaceRatio, refusalShaped, tail);
    }
}
