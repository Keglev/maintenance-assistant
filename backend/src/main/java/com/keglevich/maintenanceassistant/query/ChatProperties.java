package com.keglevich.maintenanceassistant.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * Chat provider configuration (ADR-002).
 *
 * <p>Separate from {@code maintenance.embedding.*} although both currently name the same gateway.
 * The two calls differ in every dimension that matters here: an embedding call is background load
 * with a generous timeout and a batch-shaped budget, a chat call is a person waiting inside NFR-4's
 * latency budget. Sharing one property block would mean tuning one by breaking the other.
 *
 * @param baseUrl         OpenAI-compatible endpoint root, <b>including {@code /v1}</b> — the base
 *                        URL deviation ADR-002 records twice, once in Python and once in Java
 * @param apiKey          bearer token; empty disables answering rather than failing at call time
 * @param model           model id, e.g. {@code meta-llama/Llama-3.3-70B-Instruct}
 * @param maxTokens       output cap sent on every request (NFR-7)
 * @param temperature     low: this is grounded answering, not writing
 * @param timeout         per-request read timeout
 * @param maxRetries      retries after the first attempt, for transient failures only
 * @param retryBackoff    initial backoff, doubled per attempt
 * @param dailyCallBudget maximum chat calls per day across the whole application (NFR-7)
 */
@ConfigurationProperties(prefix = "maintenance.chat")
public record ChatProperties(
        String baseUrl,
        String apiKey,
        String model,
        int maxTokens,
        double temperature,
        Duration timeout,
        int maxRetries,
        Duration retryBackoff,
        int dailyCallBudget) {

    /** Answering is inert without a key rather than failing per question at call time. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * ADR-002 caveat 3, as a property of the configured model rather than a flag someone has to
     * remember to set. The Qwen3.5 family are reasoning models: without
     * {@code reasoning_effort="none"} they spend the entire output budget on the reasoning field and
     * return <em>empty content</em> with {@code finish_reason: "length"}. The spike measured that the
     * obvious alternatives — {@code enable_thinking: false}, {@code reasoning_effort: "low"},
     * {@code /no_think} in the prompt — all leave it empty; only this one works.
     *
     * <p>It is deliberately keyed off the model id and not off the provider, because the trap
     * travels with the model: the same id on the Nebius fallback behaves the same way.
     */
    boolean isReasoningModel() {
        return model != null && model.toLowerCase(Locale.ROOT).contains("qwen3.5");
    }
}
