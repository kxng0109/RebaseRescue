package io.github.kxng0109.rebaserescue.github;

import io.github.kxng0109.rebaserescue.config.GithubProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryDedupStoreTest {

    private static GithubProperties props() {
        GithubProperties p = new GithubProperties();
        p.getWebhook().setDedupTtl(Duration.ofDays(30));
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

    @Test
    void tryClaim_shouldAdmitExactlyOneWinnerUnderConcurrency() throws Exception {
        DeliveryDedupStore store = new DeliveryDedupStore(props());
        int contenders = 32;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return store.tryClaim("delivery-race");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            AtomicInteger winners = new AtomicInteger();
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS))) {
                    winners.incrementAndGet();
                }
            }
            assertThat(winners.get()).isEqualTo(1);
            assertThat(store.isDuplicate("delivery-race")).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
