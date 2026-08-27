package com.keglevich.maintenanceassistant.query;

import com.keglevich.maintenanceassistant.ingestion.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * @param approvedOnly restrict the answer to protocols an administrator has vouched for.
     *                     <b>Defaults to false at every caller, by decision of 2026-08-11.</b> The
     *                     admin may not review at a weekend and the factory does not stop, so the
     *                     protocol about the fault happening right now has to be findable before
     *                     anyone signs it off. This is an option a reader may ask for, never a
     *                     default that quietly hides the newest knowledge in the plant.
     */
    public QueryAnswer ask(String question, UUID machineId, QueryRole role, String subject,
                           boolean approvedOnly) {
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

        // approvedOnly is part of the cache key, not an afterthought: the same question against the
        // same machine has two legitimate answers depending on it, and a cache that conflated them
        // would serve the wider answer to a caller who asked for the reviewed subset.
        Optional<QueryAnswer> cached = cache.get(question, machineId, role, approvedOnly);
        if (cached.isPresent()) {
            return cached.get();
        }

        if (!chatClient.isConfigured()) {
            // Silent until now, and it is the one failure here that is not the provider's fault:
            // a deployment reaches this line with no key and every question 503s. Logged so the
            // log says "misconfigured" instead of leaving it to look like an outage.
            log.warn("Chat call refused before it was made: the chat client is not configured "
                    + "(no api-key resolved); retrieval works, answering does not");
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
        List<String> lexicalTerms = LexicalTerms.extract(question);
        List<RetrievedChunk> hits = retriever.retrieve(machineId, questionVector, properties.topK(),
                approvedOnly, lexicalTerms, properties.lexicalWeight());

        // MAX, not hits.get(0). Before ADR-009 the two were the same thing, because the list was
        // ordered by similarity; now the lexical weight can order a chunk first that is not the most
        // similar one. Reading the head would then hand the gate a LOWER number than before and turn
        // a question that has always been Mode A into Mode B — a regression caused entirely by
        // re-ordering. The gate asks "is anything here similar enough", which is a property of the
        // set and not of its first element.
        double best = hits.stream().mapToDouble(RetrievedChunk::similarity).max().orElse(0.0);
        boolean exactTermPresent = hits.stream().anyMatch(hit -> hit.lexicalMatches() > 0);
        boolean grounded = best >= properties.similarityThreshold() || exactTermPresent;

        log.info("Query on machine {} as {}: {} hits, best similarity {}, threshold {}, "
                        + "terms {} matched-in {} chunk(s) -> Mode {}",
                machineId, role, hits.size(), String.format(java.util.Locale.ROOT, "%.4f", best),
                properties.similarityThreshold(), lexicalTerms,
                hits.stream().filter(hit -> hit.lexicalMatches() > 0).count(), grounded ? "A" : "B");

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
            // THE KIND AND THE CAUSE, because this line was the whole log of the 2026-08-26
            // incident and it carried neither: a message with no class to group it by, and no
            // stack, so the failing throw site had to be inferred from the wording. The exception
            // is the LAST argument, which is how slf4j knows to log the cause chain rather than
            // formatting it into the message.
            log.warn("Chat call failed: kind={} {}", e.kind(), e.getMessage(), e);
            throw new ProviderUnavailableException("the answer service is temporarily unavailable: " + e.getMessage());
        }

        cache.put(question, machineId, role, approvedOnly, answer);
        budget.logUsage();
        return answer;
    }

    /**
     * Mode A. Sources above the threshold only — a top-5 that contains two good hits and three weak
     * ones would otherwise invite the model to cite the weak ones, and a citation that does not
     * support its claim is worse than a shorter answer.
     */
    private QueryAnswer answerGrounded(String question, QueryRole role, List<RetrievedChunk> hits) {
        List<GroundedPrompt.LabelledSource> sources = labelByProtocol(hits);

        ChatClient.Completion completion;
        try {
            completion = chatClient.complete(new ChatClient.Prompt(
                    GroundedPrompt.system(role),
                    GroundedPrompt.user(question, sources),
                    GroundedPrompt.SCHEMA_NAME,
                    GroundedPrompt.schema()));
        } catch (ChatClient.ChatException e) {
            if (e.kind() != ChatClient.ChatException.Kind.TRUNCATED) {
                throw e;
            }
            // A GROUNDED ANSWER THAT HIT THE CAP IS NOT AN OUTAGE, and reporting it as one is the
            // defect of 2026-08-26: the provider answered, was paid, and the user was shown "nicht
            // erreichbar". A truncated Mode A body is unparseable JSON, so there is no grounded
            // answer to salvage — but the ungrounded answer is still worth having, and it is the
            // same fall-through this method already takes when Mode A produces no attributable
            // citation. Reused rather than duplicated: two paths to Mode B would be two places to
            // fix the next time the ungrounded call changes.
            //
            // CAUGHT HERE AND NOT AT ask()'s CATCH SITE, deliberately. "Was this a Mode A call" is
            // structural at this depth and would have to become a flag one level up — a second
            // source of truth for something the call stack already knows.
            //
            // THE COST IS A SECOND PAID CALL, accepted: the Mode B call counts against the daily
            // chat budget exactly like the no-citations fall-through above, and headroom for two
            // calls per question is already what checkHeadroom(2) reserves at the top of ask().
            log.warn("Mode A truncated ({}, tokens={}, wsRatio={}); degrading to Mode B",
                    classify(e.truncation()),
                    e.truncation() == null ? "unknown" : e.truncation().completionTokens(),
                    e.truncation() == null ? "unknown" : e.truncation().whitespaceRatio());
            return answerUngrounded(question, role)
                    .degradedFrom(QueryAnswer.DegradedFrom.TRUNCATED);
        }

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

    /**
     * Which of the two truncation shapes this was, in one word for the log line.
     *
     * <p>The distinction is the open question of the diagnostics wave (A4): a refusal followed by
     * unbounded JSON whitespace and an answer that genuinely ran out of room look the same in a
     * status code and need opposite fixes. The degradation is identical either way — there is no
     * grounded answer to salvage in both cases — so this word decides nothing here. It is what makes
     * the hypothesis answerable once these lines exist in production.
     */
    private static String classify(TruncatedBody truncation) {
        if (truncation == null) {
            return "unknown";
        }
        return truncation.refusalShaped() ? "refusalShaped" : "overrun";
    }

    /**
     * Groups the hits above the threshold into one labelled source per protocol, in rank order.
     *
     * <p>Chunk is the search unit, protocol is the citation unit — so two chunks of one protocol in
     * the same top-k are one source with two passages, not two sources. Measured on the E-47 demo,
     * where the top 5 chunks come from 4 protocols and the naive labelling cited one of them twice
     * as though it were two independent pieces of evidence.
     */
    private List<GroundedPrompt.LabelledSource> labelByProtocol(List<RetrievedChunk> hits) {
        LinkedHashMap<UUID, List<RetrievedChunk>> byProtocol = new LinkedHashMap<>();
        for (RetrievedChunk hit : hits) {
            // Above the threshold, OR carrying an exact term the question asked for. The second
            // clause has to be here as well as at the gate: a question grounded ONLY by its code —
            // "Was bedeutet KOM-04?", whose protocol scores 0.4288 — would otherwise be routed to
            // Mode A and then offered no sources at all, produce no citation, and fall through to
            // Mode B anyway. The gate and the source list must agree on what counts as evidence.
            if (hit.similarity() >= properties.similarityThreshold() || hit.lexicalMatches() > 0) {
                byProtocol.computeIfAbsent(hit.protocolId(), key -> new ArrayList<>()).add(hit);
            }
        }
        List<GroundedPrompt.LabelledSource> sources = new ArrayList<>();
        int label = 1;
        for (List<RetrievedChunk> chunks : byProtocol.values()) {
            // The head carries the protocol's identity — title, code, date and approval are the same
            // on every chunk of one protocol — but NOT its score. Since ADR-009 the list is ordered
            // by the fused score, so the two numbers a reader decomposes are taken across the
            // protocol's chunks rather than off whichever one happened to rank first.
            RetrievedChunk head = chunks.get(0);
            double similarity = chunks.stream().mapToDouble(RetrievedChunk::similarity).max().orElseThrow();
            int lexicalMatches = chunks.stream().mapToInt(RetrievedChunk::lexicalMatches).max().orElse(0);
            sources.add(new GroundedPrompt.LabelledSource(
                    "P" + label++, head.protocolId(), head.title(), head.errorCode(),
                    head.incidentDate(), similarity, lexicalMatches,
                    chunks.stream().map(RetrievedChunk::content).toList(),
                    head.approved()));
        }
        return sources;
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
            log.warn("Question embedding failed: {}", e.getMessage(), e);
            throw new ProviderUnavailableException("the search service is temporarily unavailable: " + e.getMessage());
        }
        if (batch.vectors().size() != 1) {
            // Also silent until now, and the count is the whole diagnosis: a batch of one that
            // came back empty is a different provider fault from one that came back with three,
            // and neither is visible from the 503 the caller receives.
            log.warn("Question embedding returned {} vectors for 1 input; expected exactly 1",
                    batch.vectors().size());
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
