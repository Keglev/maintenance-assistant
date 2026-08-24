package com.keglevich.maintenanceassistant.query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Model answers in the shape the provider really returns them, and the labelled sources to check
 * them against.
 *
 * <p>Fixtures only: no assertions, and nothing here decides what a test claims. The bodies are
 * written out as JSON rather than serialised from the records the assembler parses — a fixture built
 * from those same records could not fail when they are wrong, which is the class of defect these
 * suites exist to catch.
 *
 * <p>Consumers: AnswerAssemblerTest, AnswerAssemblerModeBTest.
 */
final class AnswerAssemblerFixtures {

    /** The one protocol every labelled source in these suites points at. */
    static final UUID PROTOCOL = UUID.fromString("0f9c5b03-0000-4000-8000-00000000000a");

    private AnswerAssemblerFixtures() {
    }

    /** A Mode A body: the language the model reports, plus the claims it made. */
    static String grounded(String language, String... claims) {
        return "{\"answer_language\":\"%s\",\"claims\":[%s]}"
                .formatted(language, String.join(",", claims));
    }

    /** One Mode A claim, cited. */
    static String claim(String text, String source) {
        return "{\"text\":\"%s\",\"source\":\"%s\"}".formatted(text, source);
    }

    /** A Mode B body: the refusal plus its troubleshooting steps. */
    static String ungrounded(String language, String... steps) {
        StringBuilder quoted = new StringBuilder();
        for (int i = 0; i < steps.length; i++) {
            quoted.append(i == 0 ? "" : ",").append('"').append(steps[i]).append('"');
        }
        return "{\"answer_language\":\"%s\",\"steps\":[%s]}".formatted(language, quoted);
    }

    /** A retrieved source, carrying the label the model was shown. */
    static GroundedPrompt.LabelledSource source(String label) {
        return new GroundedPrompt.LabelledSource(label, PROTOCOL, "E-47 Druckabfall im Presshub",
                "E-47", LocalDate.of(2024, 10, 8), 0.6950, 0, List.of("Symptom: kein Druck."), true);
    }
}
