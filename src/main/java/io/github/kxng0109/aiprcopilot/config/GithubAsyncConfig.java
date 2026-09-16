package io.github.kxng0109.aiprcopilot.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Virtual-thread executor for GitHub webhook background processing.
 *
 * <p>Webhook handling must return 202 within GitHub's 10s deadline; the
 * minutes-long AI analysis runs off this executor. Uses JDK 21+ virtual
 * threads for cheap concurrency without sizing a platform pool.</p>
 */
@Configuration
public class GithubAsyncConfig {

    @Bean
    Executor githubWebhookExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
