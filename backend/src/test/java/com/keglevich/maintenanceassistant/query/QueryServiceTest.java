package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decisions the query path makes, tested without a database and without a provider.
 *
 * <p>Everything here is a decision that can be wrong in a way no compiler catches and no integration
 * test isolates: which mode a similarity of exactly the threshold produces, whether an operator's
 * prompt really differs from a technician's, whether a citation the model invented reaches the
 * answer, and whether a cache hit quietly spends money. Fakes rather than mocks for the collaborators
 * that have behaviour, because the assertions are about what was <em>sent</em> to the provider and
 * what came back, not about which methods were called.
 */
class QueryServiceTest {

    private static final UUID MACHINE = UUID.fromString("0f9c5b01-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROTOCOL = UUID.fromString("0f9c5b02-0000-4000-8000-0000000000ff");
    private static final String SUBJECT = "user-sub-1";

    private FakeChatClient chat;
    private StubRetriever retriever;
    private CountingBudget budget;
    private QueryCache cache;
    private QueryService service;
    private QueryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new QueryProperties(0.55, 5, 10, Duration.ofMinutes(10), 100);
        chat = new FakeChatClient();
        retriever = new StubRetriever();
        budget = new CountingBudget();
        cache = new QueryCache(properties);
        service = new QueryService(new FakeEmbeddingClient(), retriever, chat, new AnswerAssembler(),
                budget, cache, new QueryRateLimiter(properties), properties);
    }

    // ---------------------------------------------------------------------------------------
    // Mode routing at the threshold
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("mode routing")
    class ModeRouting {

        @Test
        @DisplayName("a best hit above the threshold is Mode A")
        void aboveThresholdIsGrounded() {
            retriever.returning(hit(0.695));
            chat.replyingGrounded("Der Druck faellt ab.", "P1");

            QueryAnswer answer = ask(QueryRole.TECHNIKER);

            assertThat(answer.mode()).isEqualTo(QueryAnswer.AnswerMode.A);
            assertThat(answer.citations()).hasSize(1);
        }

        @Test
        @DisplayName("a best hit exactly at the threshold is Mode A — the boundary is inclusive")
        void exactlyAtThresholdIsGrounded() {
            // Stated as a test rather than left to the reader of an >= sign, because this is the
            // one line that decides whether a borderline demo case is answered or refused, and
            // ADR-002's cross-language case sits 0.027 above it.
            retriever.returning(hit(0.55));
            chat.replyingGrounded("Belegt.", "P1");

            assertThat(ask(QueryRole.TECHNIKER).mode()).isEqualTo(QueryAnswer.AnswerMode.A);
        }

        @Test
        @DisplayName("a best hit below the threshold is Mode B")
        void belowThresholdIsUngrounded() {
            // The measured Mode-B gap case: 0.502 against a 0.55 threshold.
            retriever.returning(hit(0.502));
            chat.replyingUngrounded("Kein Protokoll deckt diesen Fall ab.", "Dosierpumpe pruefen.");

            QueryAnswer answer = ask(QueryRole.TECHNIKER);

            assertThat(answer.mode()).isEqualTo(QueryAnswer.AnswerMode.B);
            assertThat(answer.citations()).isEmpty();
            assertThat(answer.claims()).isEmpty();
        }

        @Test
        @DisplayName("a machine with no indexed chunks is Mode B, not an error")
        void noHitsAtAllIsUngrounded() {
            retriever.returning();
            chat.replyingUngrounded("Nichts dokumentiert.", "Sichtpruefung.");

            assertThat(ask(QueryRole.TECHNIKER).mode()).isEqualTo(QueryAnswer.AnswerMode.B);
        }

        @Test
        @DisplayName("only hits at or above the threshold are offered to the model as sources")
        void weakHitsAreNotOfferedAsSources() {
            // A top-5 that contains one good hit and four weak ones must not invite the model to
            // cite the weak ones: a citation that does not support its claim is worse than a
            // shorter answer.
            retriever.returning(hit(0.71), hit(0.54), hit(0.51));
            chat.replyingGrounded("Belegt.", "P1");

            ask(QueryRole.TECHNIKER);

            assertThat(chat.lastUserPrompt()).contains("[P1]").doesNotContain("[P2]");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Role filtering (NFR-3)
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("role filtering")
    class RoleFiltering {

        @Test
        @DisplayName("an operator and a techniker are sent different Mode A prompts")
        void operatorAndTechnikerPromptsDiffer() {
            retriever.returning(hit(0.7));
            chat.replyingGrounded("Belegt.", "P1");

            ask(QueryRole.OPERATOR);
            String operatorPrompt = chat.lastSystemPrompt();

            chat.replyingGrounded("Belegt.", "P1");
            ask2(QueryRole.TECHNIKER, "eine andere frage");
            String technikerPrompt = chat.lastSystemPrompt();

            assertThat(operatorPrompt).isNotEqualTo(technikerPrompt);
            assertThat(operatorPrompt)
                    .as("the operator constraint is a scope limit, and it has to be in the prompt "
                            + "that produces the answer rather than applied to the answer afterwards")
                    .contains("OPERATOR")
                    .contains("must NOT describe repair work");
            assertThat(technikerPrompt)
                    .contains("qualified maintenance technician")
                    .doesNotContain("must NOT describe repair work");
        }

        @Test
        @DisplayName("Mode B for an operator is checks and escalation only")
        void modeBForAnOperatorIsEscalationOnly() {
            retriever.returning(hit(0.40));
            chat.replyingUngrounded("Nicht dokumentiert.", "Sichtpruefung.");

            ask(QueryRole.OPERATOR);

            assertThat(chat.lastSystemPrompt())
                    .as("nothing is documented here, so an ungrounded repair step would be a guess "
                            + "about live equipment handed to someone not qualified to act on it")
                    .contains("Give NO repair procedure of any kind")
                    .contains("The LAST step must be to escalate");
        }

        @Test
        @DisplayName("the citation few-shot never reaches the Mode B prompt")
        void theFewShotDoesNotLeakIntoModeB() {
            // The spike observed a model appending a citation to a refusal. The fix is structural:
            // the example cannot leak into a prompt it is not part of.
            retriever.returning(hit(0.40));
            chat.replyingUngrounded("Nicht dokumentiert.", "Sichtpruefung.");

            ask(QueryRole.TECHNIKER);

            assertThat(chat.lastSystemPrompt())
                    .doesNotContain("P-99")
                    .doesNotContain("FORMAT EXAMPLE")
                    .doesNotContainIgnoringCase("cite");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Citation validation
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("citation validation")
    class CitationValidation {

        @Test
        @DisplayName("a claim citing a source that was not retrieved is stripped")
        void uncitedSourcesAreStripped() {
            retriever.returning(hit(0.7));
            chat.replying("""
                    {"answer_language":"de","claims":[
                      {"text":"Belegte Aussage.","source":"P1"},
                      {"text":"Erfundene Aussage.","source":"P7"}]}""");

            QueryAnswer answer = ask(QueryRole.TECHNIKER);

            assertThat(answer.claims()).hasSize(1);
            assertThat(answer.claims().get(0).text()).isEqualTo("Belegte Aussage.");
            assertThat(answer.answer()).doesNotContain("Erfundene");
            assertThat(answer.citations()).extracting(QueryAnswer.Citation::label).containsExactly("P1");
        }

        @Test
        @DisplayName("citations name only the protocols the answer actually used")
        void citationsCoverOnlyWhatWasCited() {
            retriever.returning(hit(0.71), hit(0.66));
            chat.replyingGrounded("Nur die erste Quelle.", "P1");

            QueryAnswer answer = ask(QueryRole.TECHNIKER);

            assertThat(answer.citations())
                    .as("a source list longer than the answer's sources invites the reader to "
                            + "believe a claim is backed by a protocol nobody quoted")
                    .hasSize(1);
        }

        @Test
        @DisplayName("if nothing survives validation the answer falls through to Mode B")
        void anAnswerWithNoValidCitationsBecomesModeB() {
            retriever.returning(hit(0.7));
            chat.replying("""
                    {"answer_language":"de","claims":[{"text":"Frei erfunden.","source":"P9"}]}""");
            chat.thenReplyingUngrounded("Kein Protokoll deckt das ab.", "Allgemein pruefen.");

            QueryAnswer answer = ask(QueryRole.TECHNIKER);

            assertThat(answer.mode())
                    .as("retrieval thought it had an answer and generation could not ground one; "
                            + "the honest output is the labelled suggestion, not an empty Mode A")
                    .isEqualTo(QueryAnswer.AnswerMode.B);
            assertThat(chat.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("bracketed markers are stripped out of Mode B text")
        void modeBTextCarriesNoCitationMarkers() {
            retriever.returning(hit(0.3));
            chat.replying("""
                    {"answer_language":"de","steps":["Kein Protokoll deckt diesen Fall ab [P-03].",
                    "Sichtpruefung durchfuehren."]}""");

            QueryAnswer answer = ask(QueryRole.TECHNIKER);

            assertThat(answer.answer()).doesNotContain("[P-03]").doesNotContain("[");
        }
    }

    // ---------------------------------------------------------------------------------------
    // NFR-7 guards
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("NFR-7 guards")
    class Guards {

        @Test
        @DisplayName("the daily budget stops a question before it is paid for")
        void theBudgetBlocksAtTheLimit() {
            budget.exhausted("daily chat budget reached: 400 of 400 calls used today");
            retriever.returning(hit(0.7));

            assertThatThrownBy(() -> ask(QueryRole.TECHNIKER))
                    .isInstanceOf(QueryService.BudgetExhaustedException.class)
                    .hasMessageContaining("daily chat budget");
            assertThat(chat.calls())
                    .as("the guard has to stop the spend, not report it afterwards")
                    .isZero();
        }

        @Test
        @DisplayName("a cache hit reaches neither the provider nor the budget")
        void cacheHitBypassesProviderAndBudget() {
            retriever.returning(hit(0.7));
            chat.replyingGrounded("Belegt.", "P1");

            QueryAnswer first = ask(QueryRole.TECHNIKER);
            int callsAfterFirst = chat.calls();
            int headroomChecksAfterFirst = budget.headroomChecks();

            QueryAnswer second = ask(QueryRole.TECHNIKER);

            assertThat(second).isEqualTo(first);
            assertThat(chat.calls()).isEqualTo(callsAfterFirst);
            assertThat(budget.headroomChecks())
                    .as("a hit costs no provider call, so it must cost no budget either")
                    .isEqualTo(headroomChecksAfterFirst);
        }

        @Test
        @DisplayName("the cache key includes the role, so an operator never gets a technician's answer")
        void cacheIsKeyedByRole() {
            // The failure this prevents is invisible in testing and unacceptable in production:
            // the same question from an operator served out of a technician's cache entry, repair
            // steps and all.
            retriever.returning(hit(0.7));
            chat.replyingGrounded("Voller technischer Text.", "P1");
            ask(QueryRole.TECHNIKER);

            chat.replyingGrounded("Operator-sichere Antwort.", "P1");
            QueryAnswer operatorAnswer = ask(QueryRole.OPERATOR);

            assertThat(operatorAnswer.answer()).contains("Operator-sichere");
            assertThat(chat.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("the per-user rate limit refuses the eleventh question in a minute")
        void theRateLimitStopsAScript() {
            retriever.returning(hit(0.3));
            for (int i = 0; i < properties.rateLimitPerMinute(); i++) {
                chat.replyingUngrounded("Nichts.", "Pruefen.");
                ask2(QueryRole.TECHNIKER, "frage nummer " + i);
            }
            chat.replyingUngrounded("Nichts.", "Pruefen.");

            assertThatThrownBy(() -> ask2(QueryRole.TECHNIKER, "eine frage zu viel"))
                    .isInstanceOf(QueryService.RateLimitedException.class)
                    .hasMessageContaining("Try again in");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bad input
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("an unknown machine is the caller's mistake, not an empty answer")
    void unknownMachineIsRejected() {
        retriever.machineMissing();

        assertThatThrownBy(() -> ask(QueryRole.TECHNIKER))
                .isInstanceOf(QueryService.InvalidQueryException.class)
                .hasMessageContaining("unknown machine");
    }

    @Test
    @DisplayName("an empty question is rejected before anything is spent")
    void emptyQuestionIsRejected() {
        assertThatThrownBy(() -> service.ask("   ", MACHINE, QueryRole.TECHNIKER, SUBJECT))
                .isInstanceOf(QueryService.InvalidQueryException.class);
        assertThat(chat.calls()).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private QueryAnswer ask(QueryRole role) {
        return service.ask("Presse kommt nicht auf Druck, E-47", MACHINE, role, SUBJECT);
    }

    private QueryAnswer ask2(QueryRole role, String question) {
        return service.ask(question, MACHINE, role, SUBJECT);
    }

    private static RetrievedChunk hit(double similarity) {
        return new RetrievedChunk(UUID.randomUUID(), OTHER_PROTOCOL,
                "PR-03 · E-47 · Druckabfall\nSymptom: Presse kommt nicht auf Druck.",
                "E-47 Druckabfall im Presshub", "E-47", "de", LocalDate.of(2024, 10, 8), similarity);
    }

    /** Deterministic and meaningless: this test is about routing, not about retrieval quality. */
    private static final class FakeEmbeddingClient implements EmbeddingClient {
        @Override
        public int dimensions() {
            return 1024;
        }

        @Override
        public EmbeddingBatch embed(List<String> texts) {
            return new EmbeddingBatch(texts.stream().map(t -> new float[1024]).toList(), 1, 10L);
        }
    }

    /** Retrieval replaced by a list, so a similarity can be stated exactly. */
    private static final class StubRetriever extends ChunkRetriever {

        private List<RetrievedChunk> hits = List.of();
        private boolean machineExists = true;

        StubRetriever() {
            super(null);
        }

        void returning(RetrievedChunk... chunks) {
            this.hits = List.of(chunks);
        }

        void machineMissing() {
            this.machineExists = false;
        }

        @Override
        List<RetrievedChunk> retrieve(UUID machineId, float[] questionVector, int topK) {
            return hits.stream().limit(topK).toList();
        }

        @Override
        boolean machineExists(UUID machineId) {
            return machineExists;
        }
    }

    /** Counts what the guards are supposed to prevent, rather than verifying interactions. */
    private static final class CountingBudget extends ChatBudget {

        private final AtomicInteger headroomChecks = new AtomicInteger();
        private String exhaustedMessage;

        CountingBudget() {
            super(null, null);
        }

        void exhausted(String message) {
            this.exhaustedMessage = message;
        }

        int headroomChecks() {
            return headroomChecks.get();
        }

        @Override
        void checkHeadroom(int estimatedCalls) {
            headroomChecks.incrementAndGet();
            if (exhaustedMessage != null) {
                throw new BudgetExhaustedException(exhaustedMessage);
            }
        }

        @Override
        void record(int calls, long promptTokens, long completionTokens) {
            // counted by the real client against the real table; irrelevant here
        }

        @Override
        void logUsage() {
            // needs a database
        }
    }

    /** The provider, replaced by a script — and a record of what it was actually sent. */
    private static final class FakeChatClient implements ChatClient {

        private final List<String> responses = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private String lastSystemPrompt;
        private String lastUserPrompt;

        void replying(String json) {
            responses.clear();
            responses.add(json);
        }

        void thenReplyingUngrounded(String first, String... rest) {
            responses.add(ungroundedJson(first, rest));
        }

        void replyingGrounded(String text, String source) {
            replying("""
                    {"answer_language":"de","claims":[{"text":"%s","source":"%s"}]}"""
                    .formatted(text, source));
        }

        void replyingUngrounded(String first, String... rest) {
            replying(ungroundedJson(first, rest));
        }

        private static String ungroundedJson(String first, String... rest) {
            StringBuilder steps = new StringBuilder("\"").append(first).append('"');
            for (String step : rest) {
                steps.append(",\"").append(step).append('"');
            }
            return "{\"answer_language\":\"de\",\"steps\":[%s]}".formatted(steps);
        }

        int calls() {
            return calls.get();
        }

        String lastSystemPrompt() {
            return lastSystemPrompt;
        }

        String lastUserPrompt() {
            return lastUserPrompt;
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Completion complete(Prompt prompt) {
            lastSystemPrompt = prompt.system();
            lastUserPrompt = prompt.user();
            int index = Math.min(calls.getAndIncrement(), responses.size() - 1);
            return new Completion(responses.get(index), 100L, 50L, 5L);
        }
    }
}
