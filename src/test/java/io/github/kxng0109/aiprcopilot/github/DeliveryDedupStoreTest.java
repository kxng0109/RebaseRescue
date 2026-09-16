package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryDedupStoreTest {

    private static GithubProperties props() {
        GithubProperties p = new GithubProperties();
        p.getWebhook().setDedupTtl(java.time.Duration.ofDays(30));
        p.getWebhook().setDedupMaxSize(100000);
        return p;
    }

    @Test
    void tryClaim_shouldSucceedFirstTimeAndRejectSecond() {
        DeliveryDedupStore store = new DeliveryDedupStore(props());

        assertThat(store.tryClaim("delivery-1")).isTrue();
        assertThat(store.tryClaim("delivery-1")).isFalse();
        assertThat(store.isDuplicate("delivery-1")).isTrue();
        assertThat(store.isDuplicate("delivery-2")).isFalse();
        assertThat(store.estimatedSize()).isEqualTo(1);
    }

    @Test
    void tryClaim_shouldTrimAndRejectBlank() {
        DeliveryDedupStore store = new DeliveryDedupStore(props());

        assertThat(store.tryClaim("  delivery-2  ")).isTrue();
        assertThat(store.isDuplicate("delivery-2")).isTrue();
        assertThatThrownBy(() -> store.tryClaim("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.tryClaim(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isDuplicate_shouldHandleNullBlankGracefully() {
        DeliveryDedupStore store = new DeliveryDedupStore(props());

        assertThat(store.isDuplicate(null)).isFalse();
        assertThat(store.isDuplicate("  ")).isFalse();
    }
}
