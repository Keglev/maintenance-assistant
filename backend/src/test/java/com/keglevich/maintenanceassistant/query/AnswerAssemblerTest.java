package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.PROTOCOL;
import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.claim;
import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.grounded;
import static com.keglevich.maintenanceassistant.query.AnswerAssemblerFixtures.source;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The citation rule: which claims survive being checked against what was actually retrieved.
 *
 * <p><b>This is NFR-2's last line.</b> ADR-002 puts three layers between a model and an uncited
 * claim: the prompt asks, the schema makes an uncited claim unrepresentable, and this class checks
 * the citations against the chunks <em>this query</em> retrieved. Only the third can catch a model
 * that cites a label it was never shown, because neither the prompt nor the provider knows what came
 * back from the database. So every drop rule is tested from both sides — the claim that survives and
 * the claim that does not — rather than only on the happy path.
 *
 * <p>Tested directly rather than through {@link QueryService}: the assembler is a pure function from
 * a JSON string plus a label set to an answer, and driving it through the service would need a
 * retriever, a provider and a budget in place merely to reach a null claim text.
 *
 * <p>OUT OF SCOPE: Mode B, the reported language and unparseable content (AnswerAssemblerModeBTest);
 * mode ROUTING, which is QueryServiceTest's.
 *
 * <p>SIBLING: AnswerAssemblerModeBTest, sharing AnswerAssemblerFixtures.
 */
class AnswerAssemblerTest {

    private final AnswerAssembler assembler = new AnswerAssembler();

    @Test
    void assembleGrounded_claimCitingARetrievedLabel_isKept() {
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("Der Druck faellt im Presshub ab.", "P1")), List.of(source("P1")));

        assertThat(answer).isPresent();
        assertThat(answer.get().claims()).hasSize(1);
        assertThat(answer.get().claims().get(0).source()).isEqualTo("P1");
        // The prose carries the marker the frontend numbers its citations from.
        assertThat(answer.get().answer()).isEqualTo("Der Druck faellt im Presshub ab. [P1]");
        assertThat(answer.get().mode()).isEqualTo(QueryAnswer.AnswerMode.A);
    }

    @Test
    void assembleGrounded_claimCitingALabelThatWasNotRetrieved_isDropped() {
        // The failure this whole class exists for: a plausible sentence attributed to a source that
        // was never shown to the model. Dropped, not repaired.
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de",
                        claim("Belegte Aussage.", "P1"),
                        claim("Frei erfundene Aussage.", "P7")),
                List.of(source("P1")));

        assertThat(answer).isPresent();
        assertThat(answer.get().claims()).extracting(QueryAnswer.Claim::source).containsExactly("P1");
        assertThat(answer.get().answer()).doesNotContain("erfundene");
    }

    @Test
    void assembleGrounded_claimWithNullText_isDropped() {
        // A strict json_schema should make this unreachable; it is handled anyway, so it is tested
        // anyway. The null lands in the text branch, not in a NullPointerException.
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                "{\"answer_language\":\"de\",\"claims\":[{\"text\":null,\"source\":\"P1\"}]}",
                List.of(source("P1")));

        assertThat(answer)
                .as("nothing survived, so the caller must fall through to Mode B")
                .isEmpty();
    }

    @Test
    void assembleGrounded_claimWhoseTextIsOnlyACitationMarker_isDroppedAsBlank() {
        // Stripping the marker leaves nothing. A claim of "[P1]" says nothing and must not reach the
        // reader as an empty bullet with a source attached to it.
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("[P1]", "P1")), List.of(source("P1")));

        assertThat(answer).isEmpty();
    }

    @Test
    void assembleGrounded_claimWithNoSourceAtAll_isDropped() {
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                "{\"answer_language\":\"de\",\"claims\":[{\"text\":\"Ohne Quelle.\",\"source\":null}]}",
                List.of(source("P1")));

        assertThat(answer).isEmpty();
    }

    @Test
    void assembleGrounded_claimWithABlankSource_isDropped() {
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("Quelle ist leer.", "   ")), List.of(source("P1")));

        assertThat(answer).isEmpty();
    }

    @Test
    void assembleGrounded_sourceWrittenAsEmptyBrackets_isDropped() {
        // "[]" normalises to blank rather than to a label named "", which would then miss the label
        // map and be dropped for the wrong reason.
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("Klammern ohne Inhalt.", "[]")), List.of(source("P1")));

        assertThat(answer).isEmpty();
    }

    @Test
    void assembleGrounded_sourceWrittenWithBracketsAndLowercase_isAcceptedAsTheSameLabel() {
        // "[p1]", "p1" and "P1" all mean the same label to a model, and a citation rule that dropped
        // a correct claim over punctuation would push honest answers into Mode B.
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("Belegt.", "[p1]")), List.of(source("P1")));

        assertThat(answer).isPresent();
        assertThat(answer.get().claims().get(0).source()).isEqualTo("P1");
    }

    @Test
    void assembleGrounded_sourceWithAnUnclosedBracket_keepsTheBracketAndIsDropped() {
        // Only a matched pair is stripped. "[P1" is not a label, and inventing one out of it would
        // be the assembler repairing a citation instead of refusing it.
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("Halb geklammert.", "[P1")), List.of(source("P1")));

        assertThat(answer).isEmpty();
    }

    @Test
    void assembleGrounded_twoClaimsCitingOneProtocol_produceOneCitation() {
        // The chunk is the search unit; the protocol is the citation unit. Two citations for one
        // protocol reads as two independent pieces of evidence for a claim that has one.
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("Erste Aussage.", "P1"), claim("Zweite Aussage.", "P1")),
                List.of(source("P1")));

        assertThat(answer).isPresent();
        assertThat(answer.get().claims()).hasSize(2);
        assertThat(answer.get().citations()).hasSize(1);
    }

    @Test
    void assembleGrounded_noClaimsAtAll_isEmptyRatherThanAnEmptyModeA() {
        assertThat(assembler.assembleGrounded(
                "{\"answer_language\":\"de\",\"claims\":[]}", List.of(source("P1"))))
                .isEmpty();
    }

    @Test
    void assembleGrounded_claimsFieldMissingEntirely_isEmptyRatherThanANullPointer() {
        // safeClaims()'s null branch: the object arrived without the array.
        assertThat(assembler.assembleGrounded(
                "{\"answer_language\":\"de\"}", List.of(source("P1"))))
                .isEmpty();
    }

    @Test
    void assembleGrounded_citationCarriesTheSourcesOwnMetadata() {
        Optional<QueryAnswer> answer = assembler.assembleGrounded(
                grounded("de", claim("Belegt.", "P1")), List.of(source("P1")));

        assertThat(answer).isPresent();
        QueryAnswer.Citation citation = answer.get().citations().get(0);
        assertThat(citation.protocolId()).isEqualTo(PROTOCOL);
        assertThat(citation.errorCode()).isEqualTo("E-47");
        assertThat(citation.approved()).isTrue();
        // Rounded to four places, so a float artefact does not reach the view as 0.6949999999.
        assertThat(citation.similarity()).isEqualTo(0.6950);
    }
}
