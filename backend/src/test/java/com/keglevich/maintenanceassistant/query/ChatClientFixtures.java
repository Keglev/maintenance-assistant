package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.support.ProviderStub;

import java.time.Duration;
import java.util.Map;

/**
 * Canned chat-provider responses and the client that talks to them.
 *
 * <p>Fixtures only: no assertions, and nothing here decides what a test claims. The bodies are
 * written out as JSON rather than serialised from the typed records the client parses — a fixture
 * built from those same records could not fail when they are wrong, which is exactly the class of
 * defect these tests exist to catch.
 *
 * <p>Consumers: IonosChatClientTest, IonosChatClientFailureTest.
 */
final class ChatClientFixtures {

    /** A reasoning model id, so {@code reasoning_effort} is derived rather than configured. */
    static final String QWEN = "Qwen/Qwen3.5-9B-Instruct";
    /** The production chat model, which is not a reasoning model. */
    static final String LLAMA = "meta-llama/Llama-3.3-70B-Instruct";

    private ChatClientFixtures() {
    }

    /** A client pointed at any base URL — including one nothing is listening on. */
    static IonosChatClient clientForBaseUrl(String baseUrl, ChatBudget budget, String model) {
        return build(baseUrl, budget, model);
    }

    /** A client pointed at the stub, with the small, fast settings every consumer wants. */
    static IonosChatClient clientFor(ProviderStub provider, ChatBudget budget, String model) {
        return build(provider.baseUrl(), budget, model);
        }

    private static IonosChatClient build(String baseUrl, ChatBudget budget, String model) {
        return new IonosChatClient(
                new ChatProperties(
                        baseUrl, "test-key", model, 1200, 0.2,
                        Duration.ofSeconds(5),
                        // One retry and a 1 ms backoff: the retry POLICY is what these tests are
                        // about, and its timing would only buy a slow test and a flaky one.
                        1, Duration.ofMillis(1), 200),
                budget);
    }

    /** The prompt shape the query path builds, reduced to what the client actually reads. */
    static ChatClient.Prompt prompt() {
        return new ChatClient.Prompt(
                "Answer only from the sources.",
                "Was bedeutet E-47 an der Presse 3?",
                "grounded_answer",
                Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string"))));
    }

    /** A complete, usable answer. */
    static String answer(String content) {
        return """
                {
                  "choices": [ { "index": 0, "message": { "role": "assistant", "content": "%s" },
                                 "finish_reason": "stop" } ],
                  "usage": { "prompt_tokens": 812, "completion_tokens": 195, "total_tokens": 1007 }
                }
                """.formatted(content);
    }

    /** Empty content — what a reasoning model without reasoning_effort "none" actually returns. */
    static String emptyContent(String finishReason) {
        return """
                {
                  "choices": [ { "index": 0, "message": { "role": "assistant", "content": "" },
                                 "finish_reason": "%s" } ],
                  "usage": { "prompt_tokens": 812, "completion_tokens": 1200 }
                }
                """.formatted(finishReason);
    }

    /** An answer cut off at the cap: not a shorter answer, an unparseable one. */
    static String truncated() {
        return """
                {
                  "choices": [ { "index": 0, "message": { "role": "assistant",
                                 "content": "{ \\"answer\\": \\"Der Fehler E-47 bedeutet" },
                                 "finish_reason": "length" } ],
                  "usage": { "prompt_tokens": 812, "completion_tokens": 1200 }
                }
                """;
    }

    /** No choices at all — the shape a gateway returns when it filtered the request. */
    static String noChoices() {
        return """
                { "choices": [], "usage": { "prompt_tokens": 12, "completion_tokens": 0 } }
                """;
    }

    /** An answer with no usage block: it costs a metric, not an answer. */
    static String answerWithoutUsage() {
        return """
                {
                  "choices": [ { "message": { "content": "{ \\"answer\\": \\"ok\\" }" },
                                 "finish_reason": "stop" } ]
                }
                """;
    }

    /** A client whose retry count is negative — a misconfiguration, kept buildable on purpose. */
    static IonosChatClient clientWithRetries(ProviderStub provider, ChatBudget budget, int maxRetries) {
        return new IonosChatClient(
                new ChatProperties(provider.baseUrl(), "test-key", LLAMA, 1200, 0.2,
                        Duration.ofSeconds(5), maxRetries, Duration.ofMillis(1), 200),
                budget);
    }

    /** A client with no base URL at all, which is how an unconfigured deployment looks. */
    static IonosChatClient clientWithoutBaseUrl(ChatBudget budget) {
        return new IonosChatClient(
                new ChatProperties(null, "test-key", LLAMA, 1200, 0.2,
                        Duration.ofSeconds(5), 1, Duration.ofMillis(1), 200),
                budget);
    }

    /** {@code choices} present as JSON null rather than as an empty array. */
    static String nullChoices() {
        return "{ \"choices\": null, \"usage\": { \"prompt_tokens\": 12 } }";
    }

    /** A choice with no message object — a gateway shape, not an OpenAI one. */
    static String choiceWithoutMessage() {
        return "{ \"choices\": [ { \"index\": 0, \"finish_reason\": \"stop\" } ] }";
    }

    /** A usage block that is present but names neither token count. */
    static String answerWithEmptyUsage(String content) {
        return """
                {
                  "choices": [ { "message": { "content": "%s" }, "finish_reason": "stop" } ],
                  "usage": { }
                }
                """.formatted(content);
    }

    /** Cut off at the cap after a long run of content, so the preview has to be shortened. */
    static String truncatedLongAnswer() {
        return """
                {
                  "choices": [ { "message": { "content": "%s" }, "finish_reason": "length" } ],
                  "usage": { "prompt_tokens": 812, "completion_tokens": 1200 }
                }
                """.formatted("Der Fehler E-47 bedeutet Druckabfall im Presshub. ".repeat(12));
    }

    /** An error body on one line and with no trailing newline. */
    static String errorOnOneLine(String message) {
        return "{ \"error\": { \"message\": \"%s\" } }".formatted(message);
    }

    /** An error body whose first line alone runs past the 300-character message budget. */
    static String errorWithAVeryLongFirstLine() {
        return "{ \"error\": { \"message\": \"%s\" } }".formatted("gateway rejected the request ".repeat(20));
    }

    /** An error body in the provider's own shape. */
    static String error(String message) {
        return """
                { "error": { "message": "%s", "type": "invalid_request_error" } }
                """.formatted(message);
    }
}
