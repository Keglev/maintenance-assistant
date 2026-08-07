package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The query path, end to end: question in, verifiable answer out.
 *
 * <p>The order of what happens here is the design, so it is worth stating plainly:
 *
 * <ol>
 *   <li><b>rate limit</b> — before any work, because the point of a rate limit is to make abuse
 *       cheap to reject;</li>
 *   <li><b>cache</b> — before the budget, because a cache hit costs no provider call and must
 *       therefore cost no budget either;</li>
 *   <li><b>budget headroom</b> — before the first paid call, reserving enough for the Mode A answer
 *       <em>and</em> the Mode B fall-through, so a question can never start on the last unit of
 *       budget and be unable to finish honestly;</li>
 *   <li><b>embed, retrieve, route</b> — one embedding call, one SQL statement, one comparison
 *       against the threshold;</li>
 *   <li><b>answer</b> — Mode A or Mode B, two separate prompts;</li>
 *   <li><b>validate</b> — citations checked against what was retrieved, and only then cached.</li>
 * </ol>
 *
 * <p>The mode decision itself is one line and deliberately dull: the best hit is compared with the
 * configured threshold. Everything that makes that decision trustworthy — a multilingual embedding
 * model so a German question can reach an English protocol, a threshold measured against the real
 * corpus rather than a spike, a filter that keeps the comparison within one machine — happened
 * before this class was reached.
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final EmbeddingClient embeddingClient;
    private final ChunkRetriever retriever;
    private final ChatClient chatClient;
    private final AnswerAssembler assembler;
    private final ChatBudget budget;
    private final QueryCache cache;
    private final QueryRateLimiter rateLimiter;
    private final QueryProperties properties;

    QueryService(EmbeddingClient embeddingClient, ChunkRetriever retriever, ChatClient chatClient,
                 AnswerAssembler assembler, ChatBudget budget, QueryCache cache,
                 QueryRateLimiter rateLimiter, QueryProperties properties) {
        this.embeddingClient = embeddingClient;
        this.retriever = retriever;
        this.chatClient = chatClient;
        this.assembler = assembler;
        this.budget = budget;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Answers one question.
     *
     * @param question  the user's own words, in their own language
     * @param machineId search scope. Exact machine only in this phase, by decision: "similar
     *                  machines" is a later candidate and pretending to have it would be a worse
     *                  demo than not having it
     * @param role      the answer depth this caller is entitled to (NFR-3), resolved from the token
     *                  by the web layer and never sent by the client
     * @param subject   the token's {@code sub} claim, the rate-limit key
     */
    public QueryAnswer ask(String question, UUID machineId, QueryRole role, String subject) {
        if (question == null || question.isBlank()) {
            throw new InvalidQueryException("question must not be empty");
        }
        if (machineId == null) {
            throw new InvalidQueryException("machineId is required — search is scoped to one machine");
        }

        try {
            rateLimiter.check(subject);
        } catch (QueryRateLimiter.RateLimitExceededException e) {
            throw new RateLimitedException(e.retryAfterSeconds(), e.getMessage());
        }

        Optional<QueryAnswer> cached = cache.get(question, machineId, role);
        if (cached.isPresent()) {
            return cached.get();
        }

        if (!chatClient.isConfigured()) {
            throw new ProviderUnavailableException(
                    "the answer service is not configured (no LLM_API_KEY); retrieval works, answering does not");
        }
        if (!retriever.machineExists(machineId)) {
            throw new InvalidQueryException("unknown machine: " + machineId);
        }

        // Two, not one: a Mode A answer whose citations do not survive validation falls through to
        // Mode B, and that fall-through is a second paid call.
        try {
            budget.checkHeadroom(2);
        } catch (ChatBudget.BudgetExhaustedException e) {
            throw new BudgetExhaustedException(e.getMessage());
        }

        float[] questionVector = embed(question);
        List<RetrievedChunk> hits = retriever.retrieve(machineId, questionVector, properties.topK());

        double best = hits.isEmpty() ? 0.0 : hits.get(0).similarity();
        boolean grounded = !hits.isEmpty() && best >= properties.similarityThreshold();

        log.info("Query on machine {} as {}: {} hits, best similarity {}, threshold {} -> Mode {}",
                machineId, role, hits.size(), String.format(java.util.Locale.ROOT, "%.4f", best),
                properties.similarityThreshold(), grounded ? "A" : "B");

        QueryAnswer answer;
        try {
            answer = grounded
                    ? answerGrounded(question, role, hits)
                    : answerUngrounded(question, role);
        } catch (ChatClient.ChatException e) {
            // Everything the provider can do to us — a rejected key, a timeout, an unreadable or
            // truncated answer — arrives here as one type, and leaves as one status. The detail is
            // in the log and in the message; what the caller needs to know is that retrying later
            // is the right move and that nothing was answered wrongly in the meantime.
            log.warn("Chat call failed: {}", e.getMessage());
            throw new ProviderUnavailableException("the answer service is temporarily unavailable: " + e.getMessage());
        }

        cache.put(question, machineId, role, answer);
        budget.logUsage();
        return answer;
    }

    /**
     * Mode A. Sources above the threshold only — a top-5 that contains two good hits and three weak
     * ones would otherwise invite the model to cite the weak ones, and a citation that does not
     * support its claim is worse than a shorter answer.
     */
    private QueryAnswer answerGrounded(String question, QueryRole role, List<RetrievedChunk> hits) {
        List<GroundedPrompt.LabelledChunk> sources = new ArrayList<>();
        int label = 1;
        for (RetrievedChunk hit : hits) {
            if (hit.similarity() >= properties.similarityThreshold()) {
                sources.add(new GroundedPrompt.LabelledChunk("P" + label++, hit));
            }
        }

        ChatClient.Completion completion = chatClient.complete(new ChatClient.Prompt(
                GroundedPrompt.system(role),
                GroundedPrompt.user(question, sources),
                GroundedPrompt.SCHEMA_NAME,
                GroundedPrompt.schema()));

        Optional<QueryAnswer> assembled = assembler.assembleGrounded(completion.content(), sources);
        if (assembled.isPresent()) {
            return assembled.get();
        }

        // Nothing the model said was attributable to a retrieved source. Retrieval thought it had an
        // answer and generation could not produce a grounded one, so the honest output is the
        // labelled general suggestion rather than a Mode A answer with no claims in it. Rare enough
        // to be worth a warning, and cheap enough to be worth the second call.
        log.warn("Mode A produced no valid citations; falling through to Mode B");
        return answerUngrounded(question, role);
    }

    private QueryAnswer answerUngrounded(String question, QueryRole role) {
        ChatClient.Completion completion = chatClient.complete(new ChatClient.Prompt(
                UngroundedPrompt.system(role),
                UngroundedPrompt.user(question),
                UngroundedPrompt.SCHEMA_NAME,
                UngroundedPrompt.schema()));
        return assembler.assembleUngrounded(completion.content());
    }

    /**
     * One embedding call for one question.
     *
     * <p>Its cost is counted by the embedding client into the ingestion counter, which is correct
     * and deliberate: that counter measures what has been spent on embeddings, wherever it was
     * spent. The query path's own ceiling is {@link ChatBudget}, because chat is what a query
     * actually costs — an embedding call for one short question is a rounding error against an
     * answer from a 70B model.
     */
    private float[] embed(String question) {
        EmbeddingClient.EmbeddingBatch batch;
        try {
            batch = embeddingClient.embed(List.of(question));
        } catch (EmbeddingClient.EmbeddingException e) {
            log.warn("Question embedding failed: {}", e.getMessage());
            throw new ProviderUnavailableException("the search service is temporarily unavailable: " + e.getMessage());
        }
        if (batch.vectors().size() != 1) {
            throw new ProviderUnavailableException("embedding provider returned no vector for the question");
        }
        return batch.vectors().get(0);
    }

    // ---------------------------------------------------------------------------------------
    // What this module can fail with.
    //
    // The guards throw their own types internally — RateLimitExceededException lives next to the
    // buckets, BudgetExhaustedException next to the counter — and are translated here, at the one
    // place the module is entered from outside. That keeps each guard readable on its own terms
    // and still gives the web layer exactly four things to catch, none of which require it to know
    // that a bucket or a counter exists.
    // ---------------------------------------------------------------------------------------

    /** The caller's mistake: an empty question, a missing or unknown machine. Becomes a 400. */
    public static class InvalidQueryException extends RuntimeException {
        InvalidQueryException(String message) {
            super(message);
        }
    }

    /** This user is asking too fast (NFR-7). Becomes a 429 with a Retry-After. */
    public static class RateLimitedException extends RuntimeException {

        private final long retryAfterSeconds;

        RateLimitedException(long retryAfterSeconds, String message) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Today's global answer budget is spent (NFR-7). Becomes a 503, gracefully. */
    public static class BudgetExhaustedException extends RuntimeException {
        BudgetExhaustedException(String message) {
            super(message);
        }
    }

    /** Answering is impossible right now — no key, or the provider is unreachable. Becomes a 503. */
    public static class ProviderUnavailableException extends RuntimeException {
        ProviderUnavailableException(String message) {
            super(message);
        }
    }
}
