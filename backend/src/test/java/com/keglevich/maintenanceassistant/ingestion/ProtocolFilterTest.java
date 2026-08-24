package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService.InvalidModerationRequestException;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService.ProtocolFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the moderation filter accepts, and what it refuses to guess at.
 *
 * <p>The filter validates itself in its compact constructor, so an unacceptable combination cannot
 * be constructed at all — the endpoint never has to remember to check. Two refusals matter, and both
 * are refusals rather than silent corrections:
 *
 * <ul>
 *   <li>{@code MACHINE_REQUIRED_FOR_FILTER}: a title or date filter without a machine would scan the
 *       whole corpus, which is a different question from the one the reviewer asked.</li>
 *   <li>{@code UNKNOWN_APPROVAL_STATE}: a mistyped approval state answered with the unfiltered
 *       corpus would tell a reviewer their queue is everything.</li>
 * </ul>
 *
 * <p>MACHINE CODES ARE THE CONTRACT. English prose from this layer is not an API, so every refusal
 * asserts the code and not only the type — a test pinning the sentence would break on a wording fix
 * and pass on a code change, which is exactly backwards.
 *
 * <p>Tested as a unit rather than through the endpoint: the record is a pure value with no
 * collaborators, and ModerationFilterValidationIT already owns the wire envelope.
 *
 * <p>SIBLING: ModerationFilterValidationIT, which asserts the same refusals as HTTP responses.
 */
class ProtocolFilterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

    @Test
    void none_isAcceptedAndFiltersNothing() {
        ProtocolFilter filter = ProtocolFilter.none();

        assertThat(filter.machineNo()).isNull();
        assertThat(filter.titleContains()).isNull();
        assertThat(filter.approvalState()).isNull();
    }

    @Test
    void blankFilterValues_areTreatedAsUnfilledRatherThanAsSearches() {
        // An empty query parameter is an empty form field. Searching for the empty string, or
        // refusing the request over one, would both be the UI's blank box changing the answer.
        ProtocolFilter filter = new ProtocolFilter("  ", "   ", null, null, "  ");

        assertThat(filter.machineNo()).isNull();
        assertThat(filter.titleContains()).isNull();
        assertThat(filter.approvalState()).isNull();
    }

    @Test
    void machineNoIsTrimmed_soATrailingSpaceStillMatches() {
        assertThat(new ProtocolFilter(" PR-03 ", null, null, null, null).machineNo()).isEqualTo("PR-03");
    }

    // ---------------------------------------------------------------------------------------
    // MACHINE_REQUIRED_FOR_FILTER
    // ---------------------------------------------------------------------------------------

    @Test
    void titleFilterWithoutAMachine_isRefusedWithTheMachineRequiredCode() {
        assertThatThrownBy(() -> new ProtocolFilter(null, "Druckabfall", null, null, null))
                .isInstanceOf(InvalidModerationRequestException.class)
                .extracting(thrown -> ((InvalidModerationRequestException) thrown).code())
                .isEqualTo(ProtocolFilter.MACHINE_REQUIRED);
    }

    @Test
    void dateFilterWithoutAMachine_isRefusedWithTheMachineRequiredCode() {
        assertThatThrownBy(() -> new ProtocolFilter(null, null, DAY, null, null))
                .isInstanceOf(InvalidModerationRequestException.class)
                .extracting(thrown -> ((InvalidModerationRequestException) thrown).code())
                .isEqualTo(ProtocolFilter.MACHINE_REQUIRED);
    }

    @Test
    void anApprovalStateWithoutAMachine_isAccepted() {
        // The one filter that stands alone, and the reason the machine rule is not simply "any
        // filter needs a machine": the approval queue is a corpus-wide review view by design.
        ProtocolFilter filter = new ProtocolFilter(null, null, null, null, "APPROVED");

        assertThat(filter.approvalState()).isEqualTo("APPROVED");
        assertThat(filter.machineNo()).isNull();
    }

    @Test
    void titleAndDateFilterWithAMachine_isAccepted() {
        ProtocolFilter filter = new ProtocolFilter("PR-03", "Druckabfall", DAY, DAY, null);

        assertThat(filter.machineNo()).isEqualTo("PR-03");
        assertThat(filter.titleContains()).isEqualTo("Druckabfall");
        assertThat(filter.to()).isEqualTo(DAY);
    }

    // ---------------------------------------------------------------------------------------
    // UNKNOWN_APPROVAL_STATE
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "approvalState={0}")
    @ValueSource(strings = {"approved", "UnApProVeD"})
    void anApprovalStateInAnyCase_isAcceptedAndNormalisedUpwards(String state) {
        // Upper-cased rather than matched case-insensitively, because the value travels on to a SQL
        // comparison against a stored uppercase state.
        assertThat(new ProtocolFilter(null, null, null, null, state).approvalState())
                .isEqualTo(state.toUpperCase(java.util.Locale.ROOT));
    }

    @ParameterizedTest(name = "approvalState={0}")
    @ValueSource(strings = {"PENDING", "APPROVE", "DELETED", "APPROVEDD"})
    void anApprovalStateThatIsNeither_isRefusedWithTheUnknownStateCode(String state) {
        // Refused rather than ignored. Both near-misses are here on purpose: a prefix of a valid
        // state and a valid state with a typo'd suffix would each pass a startsWith or a contains
        // check, and each would answer a reviewer's filtered question with the whole corpus.
        assertThatThrownBy(() -> new ProtocolFilter(null, null, null, null, state))
                .isInstanceOf(InvalidModerationRequestException.class)
                .extracting(thrown -> ((InvalidModerationRequestException) thrown).code())
                .isEqualTo(ProtocolFilter.UNKNOWN_APPROVAL_STATE);
    }

    @Test
    void anUnknownApprovalState_isNamedInTheMessageSoTheTypoIsVisible() {
        // The sentence is not the contract, but it is what a developer reads: naming the rejected
        // value is what turns "invalid state" into a one-look fix.
        assertThatThrownBy(() -> new ProtocolFilter(null, null, null, null, "PENDING"))
                .isInstanceOf(InvalidModerationRequestException.class)
                .hasMessageContaining("APPROVED or UNAPPROVED")
                .hasMessageContaining("PENDING");
    }
}
