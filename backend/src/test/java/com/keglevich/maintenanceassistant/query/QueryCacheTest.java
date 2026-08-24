package com.keglevich.maintenanceassistant.query;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which two questions count as the same question.
 *
 * <p>A cache hit is a question that is never sent and never paid for, so the key decides two things
 * at once: how much a repeated question costs, and — far more importantly — who is allowed to see an
 * answer that was generated for somebody else. An operator served out of a technician's entry would
 * get repair steps they are not cleared for, and nothing in the answer itself would show it.
 *
 * <p>So the normalisation is deliberately narrow: whitespace and case only. Anything cleverer —
 * stripping punctuation, folding umlauts — would start merging questions that are not the same one.
 *
 * <p>OUT OF SCOPE: that the service consults the cache at all, and that a hit costs no budget, both
 * of which are QueryServiceTest's.
 *
 * <p>SIBLING: QueryServiceTest, which exercises this class through the query path.
 */
class QueryCacheTest {

    private static final UUID MACHINE = UUID.fromString("0f9c5b01-0000-4000-8000-000000000001");

    private final QueryCache cache = new QueryCache(
            new QueryProperties(0.55, 5, 10, Duration.ofMinutes(10), 100, 0.15));

    private static QueryAnswer answer(String text) {
        return new QueryAnswer(QueryAnswer.AnswerMode.B, text, "de", List.of(), List.of());
    }

    @Test
    void normalise_aNullQuestion_becomesEmptyRatherThanThrowing() {
        // The key is built before the question is validated, so a null has to survive being turned
        // into one. Failing here would turn a bad request into a 500.
        assertThat(QueryCache.normalise(null)).isEmpty();
    }

    @Test
    void normalise_collapsesWhitespaceAndCase() {
        assertThat(QueryCache.normalise("  Was  bedeutet\tE-47 ?  "))
                .isEqualTo("was bedeutet e-47 ?");
    }

    @Test
    void get_theSameQuestionTypedUntidily_isAHit() {
        cache.put("Was bedeutet E-47?", MACHINE, QueryRole.TECHNIKER, false, answer("Druckabfall."));

        // The same question from a tablet keyboard, with a stray double space and a capital.
        assertThat(cache.get("  was  bedeutet e-47?  ", MACHINE, QueryRole.TECHNIKER, false))
                .contains(answer("Druckabfall."));
    }

    @Test
    void get_theSameQuestionFromAnotherRole_isAMiss() {
        cache.put("Was bedeutet E-47?", MACHINE, QueryRole.TECHNIKER, false, answer("Voller Text."));

        // The failure this prevents is invisible in testing and unacceptable in production: an
        // operator handed a technician's repair steps out of a cache entry.
        assertThat(cache.get("Was bedeutet E-47?", MACHINE, QueryRole.OPERATOR, false)).isEmpty();
    }

    @Test
    void get_theSameQuestionAboutAnotherMachine_isAMiss() {
        cache.put("Was bedeutet E-47?", MACHINE, QueryRole.TECHNIKER, false, answer("Presse 3."));

        assertThat(cache.get("Was bedeutet E-47?", UUID.randomUUID(), QueryRole.TECHNIKER, false))
                .isEmpty();
    }

    @Test
    void get_theSameQuestionWithADifferentApprovalScope_isAMiss() {
        cache.put("Was bedeutet E-47?", MACHINE, QueryRole.TECHNIKER, false, answer("Ganzer Korpus."));

        // approvedOnly changes which protocols were searched, so it changes the answer even when
        // the words are identical.
        assertThat(cache.get("Was bedeutet E-47?", MACHINE, QueryRole.TECHNIKER, true)).isEmpty();
    }

    @Test
    void get_aQuestionNeverAsked_isAMiss() {
        assertThat(cache.get("Nie gestellt.", MACHINE, QueryRole.TECHNIKER, false)).isEmpty();
    }
}
