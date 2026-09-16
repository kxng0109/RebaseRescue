package io.github.kxng0109.rebaserescue.config;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded virtual-thread executor for GitHub webhook background processing.
 *
 * <p>Webhook handling must return 202 within GitHub's 10s deadline; the
 * minutes-long AI analysis runs off this executor. An unbounded per-task
 * executor would create one virtual thread per delivery with no backpressure,
 * so this pool bounds outstanding work to 16 running plus 100 queued (116
 * total) on virtual threads. On saturation the pool aborts fast with
 * {@code RejectedExecutionException}, which the controller maps to 429
 * without touching the 10s deadline. Caller-runs is deliberately avoided:
 * running minutes-long analysis inline would blow the deadline.</p>
 */
@Configuration
public class GithubAsyncConfig {

    @Bean
    Executor githubWebhookExecutor() {
        ThreadFactory virtualFactory = Thread.ofVirtual().name("github-webhook-", 0).factory();
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(100);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS, queue, virtualFactory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
