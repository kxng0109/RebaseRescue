package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubAsyncConfigTest {

    @Test
    void executor_shouldBeBoundedWithAbortPolicyOnVirtualThreads() throws Exception {
        GithubAsyncConfig config = new GithubAsyncConfig();
        Executor executor = config.githubWebhookExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        try {
            assertThat(pool.getMaximumPoolSize()).isEqualTo(16);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(pool.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

            AtomicBoolean virtual = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(() -> {
                virtual.set(Thread.currentThread().isVirtual());
                done.countDown();
            });
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(virtual.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void executor_shouldRejectWhenSaturated() {
        GithubAsyncConfig config = new GithubAsyncConfig();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) config.githubWebhookExecutor();
        try {
            CountDownLatch release = new CountDownLatch(1);
            for (int i = 0; i < 116; i++) {
                pool.execute(() -> {
                    try {
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThatThrownBy(() -> pool.execute(() -> {
            })).isInstanceOf(RejectedExecutionException.class);
            release.countDown();
        } finally {
            pool.shutdownNow();
        }
    }
}
