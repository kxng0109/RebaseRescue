package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubPropertiesTest {

    @Test
    void defaults_shouldBeDisabledWithSensibleValues() {
        GithubProperties props = new GithubProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getApi().getBaseUrl()).isEqualTo("https://api.github.com");
        assertThat(props.getApi().getApiVersion()).isEqualTo("2026-03-10");
        assertThat(props.getSarif().getCategory()).isEqualTo("ai-pr-copilot");
        assertThat(props.getWebhook().getDedupTtl()).isNotNull();
        assertThat(props.getWebhook().getDedupMaxSize()).isGreaterThan(0);
        assertThat(props.getWebhook().getMaxRequestBytes()).isGreaterThanOrEqualTo(1024L);
    }
}
