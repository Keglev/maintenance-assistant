package com.keglevich.maintenanceassistant.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the model's JSON into the answer, and enforces the citation rule against what was actually
 * retrieved.
 *
 * <p>This is the layer ADR-002 calls "the application-side enforcement path", and it is the only one
 * of the three that cannot be talked out of its job. The prompt asks the model to cite; the schema
 * makes an uncited claim unrepresentable; and this class checks the citations against the set of
 * chunks <em>this query</em> retrieved — which is the one check neither of the other two can make,
 * because neither the prompt nor the provider knows what came back from the database. A claim citing
 * a label that was not retrieved is dropped, not repaired: a plausible sentence attributed to a
 * source that does not support it is the precise failure this application is built to avoid.
 *
 * <p>Its own {@link JsonMapper} rather than the injected one. Boot 4.1 ships Jackson 3 as the
 * message-converter default with Jackson 2 still on the classpath through other libraries, and
 * DECISIONS.txt records what that cost the first time it was discovered at runtime. Constructing the
 * mapper here says which generation parses this string, in the file where the parsing happens.
 */
@Component
class AnswerAssembler {

    private static final Logger log = LoggerFactory.getLogger(AnswerAssembler.class);

    /**
     * Anything in square brackets. Belt and braces over the schema: the Mode B shape has no source
     * field, but a model that wants to leak a citation badly enough can still write one into a step
     * string, and the spike saw exactly that behaviour on a refusal.
     */
    private static final Pattern BRACKETED = Pattern.compile("\\s*\\[[^\\]]{0,40}\\]");

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Mode A.
     *
     * @param labelled the retrieved sources with the labels the model was given
     * @return the assembled answer, or empty if nothing the model said survived validation — which
     *         the caller treats as "not actually grounded" and answers as Mode B instead
     */
    java.util.Optional<QueryAnswer> assembleGrounded(String content, List<GroundedPrompt.LabelledSource> labelled) {
        GroundedAnswer parsed = parse(content, GroundedAnswer.class);

        Map<String, GroundedPrompt.LabelledSource> byLabel = new LinkedHashMap<>();
        for (GroundedPrompt.LabelledSource source : labelled) {
            byLabel.put(source.label().toUpperCase(Locale.ROOT), source);
        }

        List<QueryAnswer.Claim> claims = new ArrayList<>();
        // Insertion-ordered, so citations come out in the order the answer first refers to them and
        // the view can number them as it renders.
        Map<String, GroundedPrompt.LabelledSource> cited = new LinkedHashMap<>();
        int dropped = 0;

        for (GroundedAnswer.Claim claim : parsed.safeClaims()) {
            String text = claim.text() == null ? "" : stripMarkers(claim.text());
            String label = normaliseLabel(claim.source());
            GroundedPrompt.LabelledSource source = label == null ? null : byLabel.get(label);
            if (text.isBlank() || source == null) {
                dropped++;
                continue;
            }
            claims.add(new QueryAnswer.Claim(text, label));
            cited.putIfAbsent(label, source);
        }

        if (dropped > 0) {
            // Worth a warning rather than a debug line: this is the model citing something it was
            // never shown, and a rise in it is the signal that a prompt or a model swap regressed.
            log.warn("Dropped {} of {} claims citing a source that was not retrieved",
                    dropped, parsed.safeClaims().size());
        }
        if (claims.isEmpty()) {
            return java.util.Optional.empty();
        }

        List<QueryAnswer.Citation> citations = new ArrayList<>();
        for (Map.Entry<String, GroundedPrompt.LabelledSource> entry : cited.entrySet()) {
            GroundedPrompt.LabelledSource source = entry.getValue();
            citations.add(new QueryAnswer.Citation(
                    entry.getKey(), source.protocolId(), source.title(), source.errorCode(),
                    source.incidentDate(), round(source.similarity()), source.lexicalMatches(),
                    source.approved()));
        }

        String prose = claims.stream()
                .map(claim -> "%s [%s]".formatted(claim.text().strip(), claim.source()))
                .reduce((a, b) -> a + " " + b)
                .orElse("");

        return java.util.Optional.of(new QueryAnswer(
                QueryAnswer.AnswerMode.A, prose, language(parsed.answerLanguage()), claims, citations));
    }

    /**
     * Mode B. No citations by construction, and any bracketed text a step contains is stripped
     * before it is shown — see {@link #BRACKETED}.
     */
    QueryAnswer assembleUngrounded(String content) {
        UngroundedAnswer parsed = parse(content, UngroundedAnswer.class);
        List<String> steps = parsed.safeSteps().stream()
                .map(this::stripMarkers)
                .map(String::strip)
                .filter(step -> !step.isBlank())
                .toList();

        if (steps.isEmpty()) {
            throw new ChatClient.ChatException(ChatClient.ChatException.Kind.EMPTY,
                    "model produced no troubleshooting steps");
        }
        // Newline-separated rather than one paragraph: these are steps, and a night-shift reader
        // works down a list. The frontend renders the lines; it does not have to parse them.
        String prose = String.join("\n", steps);
        return new QueryAnswer(QueryAnswer.AnswerMode.B, prose, language(parsed.answerLanguage()),
                List.of(), List.of());
    }

    private <T> T parse(String content, Class<T> type) {
        try {
            T parsed = json.readValue(content, type);
            if (parsed == null) {
                throw new ChatClient.ChatException(ChatClient.ChatException.Kind.UNREADABLE,
                        "model returned a null answer object");
            }
            return parsed;
        } catch (RuntimeException e) {
            // The provider accepted a strict json_schema, so this should be unreachable; if it ever
            // fires, the raw content is the only thing that explains why.
            throw new ChatClient.ChatException(ChatClient.ChatException.Kind.UNREADABLE,
                    "cannot parse the model answer as JSON: " + firstChars(content), e);
        }
    }

    /** {@code "[P1]"}, {@code "p1"} and {@code "P1"} all mean the same label to a model. */
    private static String normaliseLabel(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String cleaned = source.strip().toUpperCase(Locale.ROOT);
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
        }
        return cleaned.isBlank() ? null : cleaned;
    }

    private String stripMarkers(String text) {
        return BRACKETED.matcher(text).replaceAll("");
    }

    /** The model's own answer to the language question, kept honest with an allowlist. */
    private static String language(String reported) {
        if (reported == null) {
            return "de";
        }
        String lower = reported.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("en") ? "en" : "de";
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static String firstChars(String content) {
        if (content == null) {
            return "(null)";
        }
        return content.length() <= 300 ? content : content.substring(0, 300) + "…";
    }

    // -----------------------------------------------------------------------------------------
    // The model's answers, typed. Records bind under either Jackson generation; the annotations
    // package is shared by both, which is what makes @JsonProperty safe here.
    // -----------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroundedAnswer(@JsonProperty("answer_language") String answerLanguage, List<Claim> claims) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Claim(String text, String source) {
        }

        List<Claim> safeClaims() {
            return claims == null ? List.of() : claims;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UngroundedAnswer(@JsonProperty("answer_language") String answerLanguage, List<String> steps) {

        List<String> safeSteps() {
            return steps == null ? List.of() : steps;
        }
    }
}
