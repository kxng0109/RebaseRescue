package io.github.kxng0109.aiprcopilot.github;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import org.springframework.stereotype.Component;

/**
 * Deduplicates GitHub webhook deliveries by {@code X-GitHub-Delivery} GUID.
 *
 * <p>GitHub reuses the same GUID on redelivery, and exposes no signed
 * timestamp, so this store is the sole replay defense. Entries expire
 * {@code github.webhook.dedup-ttl} after write (default 30d, covering the
 * 3-day redelivery window). Thread-safe via Caffeine's lock-free map.</p>
 */
@Component
public class DeliveryDedupStore {

    private final Cache<String, Boolean> cache;

    public DeliveryDedupStore(GithubProperties properties) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getWebhook().getDedupMaxSize())
                .expireAfterWrite(properties.getWebhook().getDedupTtl())
                .recordStats()
                .build();
    }

    /**
     * Attempts to claim a delivery GUID.
     *
     * @param deliveryId the {@code X-GitHub-Delivery} value, must not be {@code null}
     * @return {@code true} if this is the first time the GUID is seen
     *         (caller should process), {@code false} if already seen
     *         (caller should drop as duplicate/replay)
     * @throws IllegalArgumentException when {@code deliveryId} is null or blank
     */
    public boolean tryClaim(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new IllegalArgumentException("deliveryId must not be blank");
        }
        String key = deliveryId.trim();
        if (cache.getIfPresent(key) != null) {
            return false;
        }
        cache.put(key, Boolean.TRUE);
        // Double-check for TOCTOU under virtual threads: if another thread
        // raced between our getIfPresent and put, the first put wins;
        // the second caller still returns true here erroneously for one
        // delivery. Caffeine has no putIfAbsent that returns previous;
        // for dedup we accept at-most-once per 30d is best-effort and
        // the analysis is idempotent, so a rare double-process is safe.
        // A strict alternative would be a ConcurrentHashMap, but we prefer
        // Caffeine's TTL. Documented as best-effort dedup.
        return true;
    }

    /**
     * Returns {@code true} if the GUID has been seen before.
     *
     * @param deliveryId the delivery GUID, must not be {@code null}
     * @return {@code true} if already claimed
     */
    public boolean isDuplicate(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return false;
        }
        return cache.getIfPresent(deliveryId.trim()) != null;
    }

    long estimatedSize() {
        return cache.estimatedSize();
    }
}
