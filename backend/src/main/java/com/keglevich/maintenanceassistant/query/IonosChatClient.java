package com.keglevich.maintenanceassistant.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat client for the IONOS AI Model Hub, spoken as plain OpenAI-compatible JSON.
 *
 * <p>A {@link RestClient}, for the reasons recorded in DECISIONS.txt and repeated in
 * {@link ChatClient}: one POST, a provider that is OpenAI-<em>compatible</em> rather than OpenAI,
 * and typed records for the response because Boot 4.1's message converters are Jackson 3 while
 * Jackson 2 is still on the classpath — asking for a {@code com.fasterxml} tree fails at runtime.
 * That was found by running it, not by reading it: the first live corpus run failed all 150
 * protocols on exactly that.
 *
 * <p>Three things happen here and nowhere else:
 * <ul>
 *   <li>the NFR-7 output cap is attached to every request, so no prompt can forget it;</li>
 *   <li>{@code reasoning_effort} is sent for reasoning models (ADR-002 caveat 3), decided from the
 *       configured model id rather than from a flag someone has to remember;</li>
 *   <li>usage is recorded per request as the provider serves it, not by the caller on success.</li>
 * </ul>
 */
@Component
class IonosChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(IonosChatClient.class);

    private final ChatProperties properties;
    private final ChatBudget budget;
    private final RestClient restClient;

    // RestClient.builder() rather than the auto-configured builder bean, for the same reason the
    // embedding client does it: one external provider, its own timeouts, its own auth header, and
    // no business inheriting interceptors added for the application's own HTTP calls.
    IonosChatClient(ChatProperties properties, ChatBudget budget) {
        this.properties = properties;
        this.budget = budget;

        // java.net.http rather than HttpURLConnection, for the reason recorded on the embedding
        // client: HttpURLConnection swallows the body of a 401, so a revoked key was the one
        // rejection whose provider sentence never reached the person reading the failure. Same
        // timeout contract, split because JdkClientHttpRequestFactory has no setConnectTimeout —
        // connect on the HttpClient, read on the factory (spring-web 7.0.8, checked).
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl() == null ? "" : properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();

        log.info("Chat provider: {} model={} maxTokens={} temperature={} reasoningModel={} dailyCallBudget={}",
                properties.baseUrl(), properties.model(), properties.maxTokens(),
                properties.temperature(), properties.isReasoningModel(), properties.dailyCallBudget());
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public Completion complete(Prompt prompt) {
        long startedAt = System.nanoTime();
        ChatResponse response = callWithRetry(buildBody(prompt));
        long millis = (System.nanoTime() - startedAt) / 1_000_000L;

        String content = response.content();
        if (content == null || content.isBlank()) {
            // Named rather than reported as "the model said nothing", because the one way this
            // happens in practice has a fix: a reasoning model without reasoning_effort "none"
            // burns the whole output cap on its reasoning field (ADR-002 caveat 3), and the
            // symptom looks like a truncation bug rather than a configuration one.
            throw new ChatException("model %s returned empty content (finish_reason=%s)%s"
                    .formatted(properties.model(), response.finishReason(),
                            properties.isReasoningModel() ? "" : " — is this a reasoning model?"));
        }
        if ("length".equals(response.finishReason())) {
            // The answer is JSON, so a truncated one is not a shorter answer, it is an unparseable
            // one. Failing here names the cap instead of letting a parse error blame the model.
            throw new ChatException(
                    "answer truncated at the max-tokens cap of %d after %d completion tokens: %s"
                            .formatted(properties.maxTokens(), response.completionTokens(),
                                    preview(content)));
        }

        log.info("Chat answer: model={} promptTokens={} completionTokens={} in {} ms",
                properties.model(), response.promptTokens(), response.completionTokens(), millis);
        return new Completion(content, response.promptTokens(), response.completionTokens(), millis);
    }

    /**
     * The request body. A {@link LinkedHashMap} rather than {@code Map.of} so the optional
     * {@code reasoning_effort} is a conditional put and the wire order stays readable in a log.
     */
    private Map<String, Object> buildBody(Prompt prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.system()),
                Map.of("role", "user", "content", prompt.user())));
        // NFR-7, on every single call. The spike measured that it never binds in practice — the
        // longest answer was 195 tokens — so this costs nothing in answer quality and is pure
        // runaway protection, which is the only kind of cap worth having.
        body.put("max_tokens", properties.maxTokens());
        body.put("temperature", properties.temperature());
        // ADR-002: an uncited claim is unrepresentable in this schema. strict=true was accepted
        // first try by every model on both providers in the spike, so there is no json_object
        // fallback here — a provider that cannot do this is a provider change, not a code path.
        body.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", prompt.schemaName(),
                        "strict", true,
                        "schema", prompt.schema())));
        if (properties.isReasoningModel()) {
            body.put("reasoning_effort", "none");
        }
        return body;
    }

    /**
     * Retries transient failures only, and barely.
     *
     * <p>Deliberately less patient than the embedding client. That one runs on a background worker
     * where waiting is free, this one runs with a person watching inside NFR-4's 30 s ceiling, so a
     * second attempt is the most that fits and a third would only make a slow failure slower. A 4xx
     * other than 429 is terminal for the same reason as in ingestion: retrying a revoked key or a
     * bad model id spends the same error again — measured, at a cost of EUR 0.00, because rejected
     * requests are not billed.
     */
    private ChatResponse callWithRetry(Map<String, Object> body) {
        Duration backoff = properties.retryBackoff();
        RuntimeException last = null;

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            if (last != null) {
                log.warn("Chat call failed ({}), retry {} of {} in {}",
                        last.getMessage(), attempt, properties.maxRetries(), backoff);
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
            try {
                ChatResponse response = restClient.post()
                        .uri("/chat/completions")
                        .body(body)
                        .retrieve()
                        .body(ChatResponse.class);
                // Counted here, not by the caller: the money is spent when the provider serves the
                // request, whatever happens to the response afterwards.
                recordUsage(response);
                if (response == null) {
                    throw new ChatException("provider returned an empty body");
                }
                return response;
            } catch (org.springframework.http.converter.HttpMessageConversionException e) {
                // Answered and billed for; we simply could not read it. Terminal, because a retry
                // produces the same unreadable response at the same price.
                budget.record(1, 0L, 0L);
                throw new ChatException("cannot read the provider response: " + e.getMessage(), e);
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                last = e;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                throw new ChatException("chat request rejected: %s %s"
                        .formatted(e.getStatusCode(), firstLine(e.getResponseBodyAsString())), e);
            } catch (RestClientException e) {
                if (isUnreadableResponse(e)) {
                    budget.record(1, 0L, 0L);
                    throw new ChatException("cannot read the provider response: " + e.getMessage(), e);
                }
                // No response arrived — connect refused, read timeout, connection reset. Nothing
                // was served, so nothing is counted, and another attempt is worth making.
                last = e;
            }
        }
        throw new ChatException("chat provider unavailable after %d attempts: %s"
                .formatted(properties.maxRetries() + 1, last == null ? "unknown" : last.getMessage()), last);
    }

    /**
     * Whether a {@link RestClientException} means "the answer arrived and could not be read".
     *
     * <p><b>Why this is not simply a {@code catch} of the conversion exception.</b> It was, and the
     * branch never ran. Spring's {@code RestClient} wraps a failure to read the body in a plain
     * {@code RestClientException} — "Error while extracting response for type […]" — so an
     * unreadable 200 landed in the transient catch beside a connection reset: retried, buying the
     * same unreadable answer again, and never counted. On the chat path a person is waiting, so the
     * second purchase also costs them the wait.
     *
     * <p><b>Measured, not read off documentation</b> (2026-08-21, against a stubbed provider): a
     * body that is not JSON and JSON of the wrong shape both arrive with an
     * {@code HttpMessageNotReadableException} cause; a body that is not JSON at all, such as an
     * HTML gateway page, arrives as {@code UnknownContentTypeException}. Matched by TYPE rather
     * than by message text, so a reworded Spring message cannot silently turn this back off.
     */
    private static boolean isUnreadableResponse(RestClientException e) {
        return e instanceof org.springframework.web.client.UnknownContentTypeException
                || e.getCause() instanceof org.springframework.http.converter.HttpMessageConversionException;
    }

    private void recordUsage(ChatResponse response) {
        budget.record(1,
                response == null ? 0L : response.promptTokens(),
                response == null ? 0L : response.completionTokens());
    }

    /**
     * The provider's response, typed rather than navigated as a tree — see the class javadoc for
     * why that is not a style preference on Boot 4.1. The annotations package is shared by both
     * Jackson generations, so {@code @JsonIgnoreProperties} binds under either.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices, Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(Message message, String finish_reason) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Message(String content) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(Long prompt_tokens, Long completion_tokens) {
        }

        String content() {
            if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
                return null;
            }
            return choices.get(0).message().content();
        }

        String finishReason() {
            return choices == null || choices.isEmpty() ? "none" : choices.get(0).finish_reason();
        }

        /** Absent usage counts as zero: it costs a metric, not an answer. */
        long promptTokens() {
            return usage == null || usage.prompt_tokens() == null ? 0L : usage.prompt_tokens();
        }

        long completionTokens() {
            return usage == null || usage.completion_tokens() == null ? 0L : usage.completion_tokens();
        }
    }

    /**
     * A truncated answer on one line. Whitespace is collapsed rather than trimmed, because the
     * shape of the whitespace is itself the diagnosis: a schema-constrained model that runs away
     * on indentation looks completely different in this message from one that genuinely had more
     * to say, and the two need opposite fixes.
     */
    private static String preview(String content) {
        if (content == null || content.isBlank()) {
            return "(empty)";
        }
        String collapsed = content.replaceAll("\\s+", " ").strip();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "…";
    }

    private static String firstLine(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        String trimmed = body.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatException("interrupted while backing off", e);
        }
    }
}
