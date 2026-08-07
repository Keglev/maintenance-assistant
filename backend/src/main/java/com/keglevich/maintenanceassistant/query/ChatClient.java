package com.keglevich.maintenanceassistant.query;

import java.util.Map;

/**
 * The one call this application makes to the chat provider.
 *
 * <p>Narrow, and narrow for the same reason {@code EmbeddingClient} is: what the query path needs is
 * a single {@code POST /v1/chat/completions} with a system prompt, a user prompt, an output cap and
 * a response schema. DECISIONS.txt closed Spring AI for the embedding path on proportion rather than
 * on compatibility, and the chat path was named there as the place that decision would be revisited
 * — the place where advisors, chat memory and tool calling would earn their weight. This
 * implementation uses none of them: there is no conversation to remember (every question is
 * answered from the retrieved chunks alone), no tool to call, and the one advisor-shaped concern —
 * citation enforcement — is deliberately done in the application against the retrieved set, because
 * a framework cannot know which sources <em>this</em> query actually retrieved. So the same
 * reasoning lands in the same place, and it is recorded rather than assumed.
 *
 * <p><b>Implementations record their own usage</b> in {@code ChatBudget}, per request, as the
 * provider serves it — not the caller on success. That is the ingestion lesson carried over
 * verbatim: the first live embedding run made 150 paid calls the counter never saw, because every
 * one of them failed while converting the response and only the success path did the counting.
 */
interface ChatClient {

    /** The configured model id. Reported in logs and used to explain a refusal to answer. */
    String model();

    /** Whether a key and a base URL are present. Without them the query path answers 503. */
    boolean isConfigured();

    /**
     * One completion, constrained to a JSON schema.
     *
     * <p>The schema is not a convenience: it is the application-side citation enforcement path from
     * ADR-002. A Mode A answer is a list of {text, source} pairs in which {@code source} is
     * required, so an uncited claim is <em>unrepresentable</em> rather than merely discouraged, and
     * a Mode B answer has no source field at all, so the citation leakage the spike observed on a
     * refusal cannot be expressed either.
     *
     * @throws ChatException on anything that stops an answer being produced
     */
    Completion complete(Prompt prompt);

    /**
     * @param system       system prompt; Mode A and Mode B pass two entirely different strings
     * @param user         the sources block and the question
     * @param schemaName   name the provider attaches to the schema
     * @param schema       JSON Schema the answer must satisfy
     */
    record Prompt(String system, String user, String schemaName, Map<String, Object> schema) {
    }

    /**
     * @param content           the model's message content — JSON matching the requested schema
     * @param promptTokens      input tokens as reported by the provider
     * @param completionTokens  output tokens as reported by the provider
     * @param durationMillis    wall clock of the provider call, which is most of NFR-4's budget
     */
    record Completion(String content, long promptTokens, long completionTokens, long durationMillis) {
    }

    /** Anything that stops a question being answered. */
    class ChatException extends RuntimeException {
        ChatException(String message) {
            super(message);
        }

        ChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
