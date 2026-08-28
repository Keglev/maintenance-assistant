package com.keglevich.maintenanceassistant.query;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * NFR-7's cheapest guard: the same question is paid for once.
 *
 * <p>A hit costs no provider call, so it counts against neither the daily budget nor the day's
 * tokens — which is the point, and is why the lookup happens before the budget check rather than
 * after it. It does still cost a rate-limit token, because the rate limit answers a different
 * question: how hard one person may hammer the endpoint, cached or not.
 *
 * <p><b>The role is part of the key, and that is a correctness requirement rather than a nicety.</b>
 * An Operator and a Techniker asking the identical question about the identical machine must get
 * different answers (NFR-3) — one of them omits the repair. A key without the role would serve a
 * technician's answer, repair steps and all, to the next operator who asked the same thing. That is
 * the sort of cache bug that is invisible in testing and unacceptable in production.
 *
 * <p>The question is normalised — trimmed, collapsed whitespace, lower-cased — so "E-47?" and
 * "e-47 ?" are one entry. Deliberately no further cleverness: stemming or fuzzy matching would make
 * two different questions share an answer, and the saving is measured in fractions of a cent.
 *
 * <p>Bounded by size and by TTL. The TTL is what keeps a freshly uploaded protocol from being
 * invisible for the rest of the day, and it is short enough that "upload, then ask again" works
 * within a shift.
 */
@Component
class QueryCache {

    private static final Logger log = LoggerFactory.getLogger(QueryCache.class);

    private final Cache<Key, QueryAnswer> cache;

    QueryCache(QueryProperties properties) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.cacheMaxEntries())
                .expireAfterWrite(properties.cacheTtl())
                .recordStats()
                .build();
    }

    /**
     * The cached answer for this exact question, machine, role and scope.
     *
     * <p>ROLE AND SCOPE ARE PART OF THE KEY, and that is a correctness rule rather than a tuning
     * one: an operator must never be served the technician's answer to the same question (NFR-3),
     * and an approved-only search must never see a hit produced over the whole corpus.
     */
    Optional<QueryAnswer> get(String question, UUID machineId, QueryRole role, boolean approvedOnly) {
        QueryAnswer hit = cache.getIfPresent(new Key(normalise(question), machineId, role, approvedOnly));
        if (hit != null) {
            log.debug("Query cache hit for machine {} as {}", machineId, role);
        }
        return Optional.ofNullable(hit);
    }

    /**
     * Stores an answer under the same four-part key {@link #get} reads.
     *
     * <p>Entries expire by TTL and are never invalidated by an edit, which is deliberate: the
     * corrector's re-index makes the corpus right within seconds and the window in which a stale
     * answer can be served is the TTL, not forever. Invalidating per protocol would need a
     * question-to-protocol index this cache does not keep.
     */
    void put(String question, UUID machineId, QueryRole role, boolean approvedOnly, QueryAnswer answer) {
        cache.put(new Key(normalise(question), machineId, role, approvedOnly), answer);
    }

    /** Test seam and an operational one: a cache nobody can clear is a cache nobody can debug. */
    void clear() {
        cache.invalidateAll();
    }

    long size() {
        return cache.estimatedSize();
    }

    /**
     * Folds the spelling differences that do not change the question.
     *
     * <p>Trim, collapse runs of whitespace, lower-case under ROOT. Two shop-floor users typing the
     * same fault with different spacing or capitalisation should share one answer and one LLM
     * call, which is what makes this cache worth having. ROOT rather than the default locale so
     * the key does not depend on the server's language settings.
     */
    static String normalise(String question) {
        return question == null ? "" : question.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * What makes two queries the same query.
     *
     * <p>All four parts are required for the answer to be interchangeable: the normalised question,
     * the machine it was asked about, the ROLE it was answered for, and whether the search was
     * limited to approved protocols. Dropping either of the last two would let one user read an
     * answer assembled under rules that do not apply to them.
     */
    private record Key(String question, UUID machineId, QueryRole role, boolean approvedOnly) {
    }
}
