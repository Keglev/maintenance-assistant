package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.claim;
import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.grounded;
import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.source;
import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.ungrounded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mode B, the reported language, and what happens when the content will not parse.
 *
 * <p>Mode B is the honest answer when nothing was retrieved closely enough to ground one, so what
 * matters here is that it never pretends otherwise: no citations by construction, and any bracketed
 * text a step carries is stripped before a reader sees it. The spike saw a model write a citation
 * into a refusal's step string even though the Mode B schema has no source field, which is why the
 * stripping exists and why it is tested rather than assumed.
 *
 * <p>MESSAGE ASSERTIONS: service layer, so type and message both — this is the only observable a
 * caller has when a model answers in a shape nobody expected.
 *
 * <p><b>ONE TEST PINS A FINDING RATHER THAN A DESIGN</b>, named and commented as such:
 * {@code contentThatIsTheJsonNullLiteral}. The assembler's "null answer object" message cannot reach
 * a caller, because it is thrown inside the {@code try} that catches {@code RuntimeException} and is
 * re-wrapped by its own handler. Written to assert what the code does, with the cause asserted too
 * so it cannot pass for the wrong reason; fixing it is a production change and this PR makes none.
 *
 * <p>OUT OF SCOPE: the Mode A citation rule (AnswerAssemblerTest) and mode ROUTING, which is
 * QueryServiceTest's.
 *
 * <p>SIBLING: AnswerAssemblerTest, sharing AnswerAssemblerFixtures.
 */
class AnswerAssemblerModeBTest {

    private final AnswerAssembler assembler = new AnswerAssembler();

    @Test
    void assembleUngrounded_steps_areNewlineSeparatedAndUncited() {
        QueryAnswer answer = assembler.assembleUngrounded(
                ungrounded("de", "Sichtpruefung.", "Dosierpumpe pruefen."));

        assertThat(answer.mode()).isEqualTo(QueryAnswer.AnswerMode.B);
        // Newline-separated rather than one paragraph: these are steps, and a night-shift reader
        // works down a list.
        assertThat(answer.answer()).isEqualTo("Sichtpruefung.\nDosierpumpe pruefen.");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.claims()).isEmpty();
    }

    @Test
    void assembleUngrounded_bracketedMarkerInAStep_isStripped() {
        QueryAnswer answer = assembler.assembleUngrounded(
                ungrounded("de", "Kein Protokoll deckt das ab [P-03].", "Pruefen."));

        assertThat(answer.answer()).doesNotContain("[").doesNotContain("P-03");
    }

    @Test
    void assembleUngrounded_stepThatIsOnlyAMarker_isDroppedRatherThanLeftBlank() {
        // Stripping can empty a step. A blank line in a night-shift checklist is a step the reader
        // stops at, so it is filtered rather than rendered.
        QueryAnswer answer = assembler.assembleUngrounded(
                ungrounded("de", "[P-03]", "Dosierpumpe pruefen."));

        assertThat(answer.answer()).isEqualTo("Dosierpumpe pruefen.");
    }

    @Test
    void assembleUngrounded_noStepsAtAll_failsRatherThanReturningAnEmptyAnswer() {
        assertThatThrownBy(() -> assembler.assembleUngrounded(ungrounded("de")))
                .isInstanceOf(ChatClient.ChatException.class)
                .hasMessageContaining("no troubleshooting steps");
    }

    @Test
    void assembleUngrounded_stepsFieldMissingEntirely_failsRatherThanNullPointer() {
        // safeSteps()'s null branch: the object arrived without the array.
        assertThatThrownBy(() -> assembler.assembleUngrounded("{\"answer_language\":\"de\"}"))
                .isInstanceOf(ChatClient.ChatException.class)
                .hasMessageContaining("no troubleshooting steps");
    }

    @Test
    void assembleUngrounded_everyStepBlankAfterStripping_failsRatherThanAnswering() {
        assertThatThrownBy(() -> assembler.assembleUngrounded(ungrounded("de", "[P1]", "   ")))
                .isInstanceOf(ChatClient.ChatException.class)
                .hasMessageContaining("no troubleshooting steps");
    }

    // ---------------------------------------------------------------------------------------
    // The reported language, kept honest with an allowlist
    // ---------------------------------------------------------------------------------------

    @Test
    void assembleGrounded_englishLanguage_isReportedAsEn() {
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("en-GB", claim("The pressure drops.", "P1")), List.of(source("P1")));

        assertThat(answer).isPresent();
        // An allowlist, not the model's string: "en-GB" is a tag the frontend has no namespace for.
        assertThat(answer.get().language()).isEqualTo("en");
    }

    @Test
    void assembleGrounded_anUnknownLanguage_fallsBackToGerman() {
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("klingon", claim("Belegt.", "P1")), List.of(source("P1")));

        assertThat(answer).isPresent();
        assertThat(answer.get().language()).isEqualTo("de");
    }

    @Test
    void assembleGrounded_noLanguageField_fallsBackToGerman() {
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                "{\"claims\":[{\"text\":\"Belegt.\",\"source\":\"P1\"}]}", List.of(source("P1")));

        assertThat(answer).isPresent();
        // German is the shop-floor default; guessing English for a missing field would hand a
        // night-shift reader an answer in the wrong language.
        assertThat(answer.get().language()).isEqualTo("de");
    }

    // ---------------------------------------------------------------------------------------
    // Content that will not parse
    // ---------------------------------------------------------------------------------------

    @Test
    void assembleGrounded_contentThatIsNotJson_failsQuotingWhatArrived() {
        // The provider accepted a strict json_schema, so this should be unreachable. If it ever
        // fires, the raw content is the only thing that explains why — so the message carries it
        // rather than saying "invalid JSON".
        assertThatThrownBy(() -> assembler.assembleGrounded("not json at all", List.of(source("P1"))))
                .isInstanceOf(ChatClient.ChatException.class)
                .hasMessageContaining("cannot parse the model answer as JSON")
                .hasMessageContaining("not json at all");
    }

    @Test
    void assembleGrounded_nullContent_saysSoRatherThanPrintingTheWordNull() {
        // "(null)" rather than a bare "null" in the sentence: a message reading "cannot parse the
        // model answer as JSON: null" is indistinguishable from a model that literally answered
        // the four characters n-u-l-l, and the two are diagnosed differently.
        assertThatThrownBy(() -> assembler.assembleGrounded(null, List.of(source("P1"))))
                .isInstanceOf(ChatClient.ChatException.class)
                .hasMessageContaining("cannot parse the model answer as JSON: (null)");
    }

    @Test
    void assembleGrounded_contentThatIsTheJsonNullLiteral_reportsTheParseMessageNotTheNullOne() {
        // FINDING, PINNED (see this suite's header). parse() throws its own "model returned a null
        // answer object" for this input and then catches it: the throw sits inside the try,
        // ChatException IS a RuntimeException, and the handler is catch (RuntimeException). So that
        // sentence can never reach a caller — it is re-wrapped as the generic parse message with
        // itself as the cause.
        //
        // Pinned rather than asserted as intended, because the fix is a production change and this
        // PR makes none. Asserting the CAUSE as well as the message is what keeps this honest: it
        // proves the null branch really did run, so the test cannot pass for the wrong reason if
        // someone later makes the null check unreachable outright.
        assertThatThrownBy(() -> assembler.assembleGrounded("null", List.of(source("P1"))))
                .isInstanceOf(ChatClient.ChatException.class)
                .hasMessageContaining("cannot parse the model answer as JSON")
                .hasMessageNotContaining("null answer object")
                .cause()
                .isInstanceOf(ChatClient.ChatException.class)
                .hasMessage("model returned a null answer object");
    }

    @Test
    void assembleUngrounded_veryLongUnparseableContent_isTruncatedInTheMessage() {
        String tooLong = "x".repeat(400);

        assertThatThrownBy(() -> assembler.assembleUngrounded(tooLong))
                .isInstanceOf(ChatClient.ChatException.class)
                // Truncated with an ellipsis: the whole runaway body in an exception message is
                // what turns a log line into a page nobody reads.
                .hasMessageContaining("…")
                .hasMessageNotContaining("x".repeat(310));
    }
}
