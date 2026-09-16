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
 * 3-day redelivery window). Claims are strictly atomic via the cache's
 * concurrent map view, so concurrent redeliveries of the same GUID yield
 * exactly one winner even on virtual threads.</p>
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
     * Attempts to claim a delivery GUID atomically.
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
        return cache.asMap().putIfAbsent(key, Boolean.TRUE) == null;
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
