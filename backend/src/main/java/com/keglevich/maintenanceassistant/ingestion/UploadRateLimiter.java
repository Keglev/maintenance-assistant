package com.keglevich.maintenanceassistant.ingestion;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

/**
 * NFR-7, burst case: how often one person may upload.
 *
 * <p>Keyed by {@code preferred_username} rather than by {@code sub}, deliberately and unlike
 * {@link com.keglevich.maintenanceassistant.query.QueryRateLimiter}: the username is the identity
 * that ends up in {@code protocol.uploaded_by}, so the limit is enforced against exactly the name
 * the resulting rows are attributed to. A limit keyed on one identity while the audit trail records
 * another is a limit nobody can reconcile afterwards.
 *
 * <p><b>In memory, and this bucket is not the real ceiling.</b> One application instance runs today,
 * so a distributed limiter would be infrastructure bought for a topology that does not exist — but
 * that means a restart hands everyone a fresh bucket, and two replicas would each grant the full
 * allowance. What actually bounds total damage across restarts and instances is the daily embedding
 * budget in Postgres ({@link EmbeddingBudget}), which is durable and shared. This class bounds the
 * <em>burst</em>; that one bounds the <em>total</em>. If this ever runs as more than one replica,
 * this is the class to move to Redis, and the daily counter already behaves correctly.
 *
 * <p>The bucket map is a Caffeine cache rather than a plain map so idle users are evicted; an
 * unbounded map keyed by a name anyone with a token can supply is a slow leak.
 */
@Component
public class UploadRateLimiter {

    private final IngestionProperties properties;
    private final ConcurrentMap<String, Bucket> buckets;

    UploadRateLimiter(IngestionProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .maximumSize(10_000)
                .<String, Bucket>build()
                .asMap();
    }

    /**
     * Consumes one token for this user.
     *
     * @throws UploadRateLimitExceededException when the user is over their limit; the message says
     *                                          when to come back, because a limit without a "try
     *                                          again in n seconds" reads as an outage
     */
    public void check(String username) {
        Bucket bucket = buckets.computeIfAbsent(username, key -> newBucket());
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            throw new UploadRateLimitExceededException(retryAfterSeconds,
                    "Too many uploads in a short time (limit %d per minute). Try again in %d seconds."
                            .formatted(properties.uploadsPerMinute(), retryAfterSeconds));
        }
    }

    /**
     * A greedy refill rather than one whole bucket per minute: tokens come back steadily, so a
     * Schichtleiter filing a stack of protocols at the end of a shift waits seconds for the next one
     * rather than up to a minute for all of them. Same ceiling, far better behaviour for a person
     * doing exactly what the feature is for.
     */
    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.uploadsPerMinute())
                        .refillGreedy(properties.uploadsPerMinute(), Duration.ofMinutes(1))
                        .build())
                .build();
    }

    /** Thrown when a user is over their upload limit. Becomes a 429 with {@code Retry-After}. */
    public static class UploadRateLimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final long retryAfterSeconds;

        UploadRateLimitExceededException(long retryAfterSeconds, String message) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
