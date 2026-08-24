package com.keglevich.maintenanceassistant.ingestion;

import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient.EmbeddingException;
import com.keglevich.maintenanceassistant.support.ProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.keglevich.maintenanceassistant.ingestion.EmbeddingClientFixtures.clientFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Response shapes the typed records have to survive, and the sentences the client builds from them.
 *
 * <p>The client parses the provider's answer into records rather than navigating a tree, so every
 * "absent" case is a null inside a nested record: no data array, an item whose embedding is null or
 * empty, a usage block naming neither count. Each has a defined answer in the code, and each is a
 * shape this gateway or a proxy in front of it really produces — ADR-002's {@code encoding_format}
 * caveat is exactly a vector arriving as something other than an array of numbers.
 *
 * <p><b>What must never happen is a row of vectors built out of one of these.</b> A half-indexed
 * protocol is unretrievable and silent about it, so every shape here has to fail loudly and, where
 * the provider served it, be counted — the 2026-08 incident was 150 paid calls the budget never saw.
 *
 * <p>MESSAGE ASSERTIONS: service layer, so type and message both — the message is what lands in the
 * protocol's {@code failure_reason} column for a human to read later.
 *
 * <p>OUT OF SCOPE: the retry policy and the unreadable-response rule
 * (IonosEmbeddingClientFailureTest) and the happy paths (IonosEmbeddingClientTest).
 *
 * <p>SIBLINGS: IonosEmbeddingClientTest and IonosEmbeddingClientFailureTest, sharing
 * EmbeddingClientFixtures.
 */
class IonosEmbeddingClientResponseShapeTest {

    private static final String MODEL = "BAAI/bge-m3";

    private ProviderStub provider;
    private EmbeddingBudget budget;

    @BeforeEach
    void startProvider() {
        provider = ProviderStub.start();
        budget = mock(EmbeddingBudget.class);
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    // ---------------------------------------------------------------------------------------
    // Absent pieces of the response
    // ---------------------------------------------------------------------------------------

    @Test
    void embed_bodyThatIsTheJsonNullLiteral_failsAsAnEmptyBodyAndIsStillCounted() {
        provider.enqueueJson(200, "null");

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("provider returned an empty body");

        // Served, so counted — at zero tokens, because there is no usage block to read.
        assertThat(provider.requests()).hasSize(1);
        verify(budget).record(1, 0L);
    }

    @Test
    void embed_dataPresentAsJsonNull_failsNamingBothCounts() {
        provider.enqueueJson(200, EmbeddingClientFixtures.nullData(MODEL));

        // Distinct from an empty array: a null field skips the size comparison, and the count in
        // the message has to come out as 0 rather than as a NullPointerException at the reader.
        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("expected 1 embeddings, provider returned 0");
    }

    @Test
    void embed_itemWhoseVectorIsAnEmptyArray_asksWhetherEncodingFormatIsStillFloat() {
        provider.enqueueJson(200, EmbeddingClientFixtures.emptyVector(MODEL));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                // The question IS the fix: ADR-002's first caveat is the gateway rejecting base64,
                // and an empty vector is what a half-applied encoding_format change looks like.
                .hasMessageContaining("is encoding_format still float?");
    }

    @Test
    void embed_itemWhoseVectorIsJsonNull_failsTheSameWayAsAnEmptyOne() {
        provider.enqueueJson(200, EmbeddingClientFixtures.nullVector(MODEL));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("is encoding_format still float?");

        // Served and unusable, so counted: the provider was paid for this answer.
        verify(budget).record(1, 7L);
    }

    @Test
    void embed_usageReportingZeroPromptTokens_fallsBackToTheTotal() {
        provider.enqueueJson(200, EmbeddingClientFixtures.zeroPromptTokens(MODEL));

        var batch = clientFor(provider, budget, MODEL, 2).embed(List.of("eins"));

        // A zero prompt count is not a usable number, and some models on this gateway report the
        // spend only in total_tokens. Taking the total keeps the daily budget honest instead of
        // letting a whole seeding run bill as free.
        assertThat(batch.promptTokens()).isEqualTo(19L);
        verify(budget).record(1, 19L);
    }

    @Test
    void embed_usageNamingNeitherCount_countsTheCallAtZeroTokens() {
        provider.enqueueJson(200, EmbeddingClientFixtures.emptyUsage(MODEL));

        var batch = clientFor(provider, budget, MODEL, 2).embed(List.of("eins"));

        assertThat(batch.vectors()).hasSize(1);
        // Absent usage costs a metric, not a protocol — the call still counts against the budget.
        assertThat(batch.promptTokens()).isZero();
        verify(budget).record(1, 0L);
    }

    @Test
    void embed_responseThatDoesNotSayWhichModelAnswered_doesNotWarnAboutProvenance() {
        var appender = EmbeddingClientFixtures.captureLog();
        provider.enqueueJson(200, EmbeddingClientFixtures.withoutModelName());

        var batch = clientFor(provider, budget, MODEL, 2).embed(List.of("eins"));

        assertThat(batch.vectors()).hasSize(1);
        // Silence is correct here: the provenance warning exists to catch a DIFFERENT model
        // answering, and a response that names no model is no evidence of that. Warning anyway
        // would train the reader to ignore the one message that matters (ADR-008).
        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().contains("answered as model"));
    }

    // ---------------------------------------------------------------------------------------
    // The sentences built from a provider error body
    // ---------------------------------------------------------------------------------------

    @Test
    void embed_rejectionBodyOnASingleLine_isCarriedWhole() {
        provider.enqueueJson(400, EmbeddingClientFixtures.errorOnOneLine("input exceeds the token limit"));

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding request rejected: 400")
                .hasMessageContaining("input exceeds the token limit");

        verifyNoInteractions(budget);
    }

    @Test
    void embed_rejectionBodySpanningLines_carriesOnlyTheFirstLine() {
        provider.enqueueJson(400, "{ \"error\": \"first line\" }\nsecond line\nthird line");

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("first line")
                // One line, because this sentence goes into failure_reason and a multi-line gateway
                // page pasted into a database column is unreadable wherever it is shown.
                .hasMessageNotContaining("second line");
    }

    @Test
    void embed_rejectionBodyWithAVeryLongFirstLine_isCappedWithAnEllipsis() {
        provider.enqueueJson(400, EmbeddingClientFixtures.errorWithAVeryLongFirstLine());

        assertThatThrownBy(() -> clientFor(provider, budget, MODEL, 2).embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding request rejected: 400")
                // Capped rather than trusted: one runaway provider response should not decide how
                // much text goes into a protocol row.
                .hasMessageContaining("…")
                .satisfies(thrown -> assertThat(thrown.getMessage().length()).isLessThan(400));
    }

    // ---------------------------------------------------------------------------------------
    // Configuration edges
    // ---------------------------------------------------------------------------------------

    @Test
    void embed_withANegativeRetryCount_makesNoCallAndSaysTheCauseIsUnknown() {
        // A misconfiguration, not a supported setting: maxRetries of -1 makes the attempt loop
        // never run, so there is no attempt and no exception to name. Tested because a guard that
        // silently spends nothing looks identical to a provider outage in the log, and "unknown"
        // is what tells the two apart.
        assertThatThrownBy(() -> EmbeddingClientFixtures.clientWithRetries(provider, budget, MODEL, -1)
                .embed(List.of("eins")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("embedding provider unavailable after 0 attempts")
                .hasMessageContaining("unknown");

        assertThat(provider.requests()).isEmpty();
        verifyNoInteractions(budget);
    }
}
