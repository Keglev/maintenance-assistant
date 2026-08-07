package com.keglevich.maintenanceassistant.query;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

/**
 * NFR-7, per user: how often one person may ask.
 *
 * <p>Keyed by the token's {@code sub} claim rather than by username or by IP. {@code sub} is the
 * stable Keycloak user id — a username can be changed in the admin console and an IP is shared by
 * everyone behind the plant's NAT, which would make one impatient operator throttle the whole shift.
 *
 * <p>In-memory, and honestly so: this is one application instance, so a distributed limiter would be
 * infrastructure bought for a topology that does not exist. What that costs is that a restart hands
 * everyone a fresh bucket, which is acceptable because this guard bounds the <em>rate</em> of spend
 * and the guard that bounds the <em>total</em> is {@link ChatBudget}, in Postgres, precisely because
 * that one must survive a restart. If the application is ever run as more than one replica, this
 * class is the thing to move to Redis, and the daily counter already behaves correctly.
 *
 * <p>The bucket map is a Caffeine cache rather than a plain map so idle users are evicted; an
 * unbounded map keyed by user id is a slow leak in a system that anyone with a token can add keys to.
 */
@Component
class QueryRateLimiter {

    private final QueryProperties properties;
    private final ConcurrentMap<String, Bucket> buckets;

    QueryRateLimiter(QueryProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                // Comfortably longer than the refill window, so an active user keeps their bucket
                // and an inactive one is forgotten rather than accumulating.
                .expireAfterAccess(Duration.ofMinutes(10))
                .maximumSize(10_000)
                .<String, Bucket>build()
                .asMap();
    }

    /**
     * Consumes one token for this user.
     *
     * @throws RateLimitExceededException when the user is over their limit; the message says when to
     *                                    come back, because a limit without a "try again in n
     *                                    seconds" reads as an outage
     */
    void check(String subject) {
        Bucket bucket = buckets.computeIfAbsent(subject, key -> newBucket());
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            throw new RateLimitExceededException(retryAfterSeconds,
                    "Too many questions in a short time (limit %d per minute). Try again in %d seconds."
                            .formatted(properties.rateLimitPerMinute(), retryAfterSeconds));
        }
    }

    /**
     * A greedy refill rather than one whole bucket per minute: tokens come back steadily, so a user
     * who has just run out waits seconds for the next question instead of up to a minute for all of
     * them. Same ceiling, far better behaviour for a person.
     */
    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.rateLimitPerMinute())
                        .refillGreedy(properties.rateLimitPerMinute(), Duration.ofMinutes(1))
                        .build())
                .build();
    }

    /** Thrown when a user is over their limit. Becomes a 429. */
    static class RateLimitExceededException extends RuntimeException {

        private final long retryAfterSeconds;

        RateLimitExceededException(long retryAfterSeconds, String message) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
