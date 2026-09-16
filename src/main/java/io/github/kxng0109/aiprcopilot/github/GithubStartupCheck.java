package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fail-closed startup validation for the opt-in GitHub integration.
 *
 * <p>When {@code github.enabled} is true, a missing webhook secret or a
 * non-allowlisted API base URL aborts startup instead of surfacing as a
 * per-request 403 or, worse, sending installation credentials to an
 * unintended host. When the integration is disabled, no validation runs.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GithubStartupCheck {

    private final GithubProperties properties;

    /**
     * Validates GitHub configuration after the context refreshes.
     *
     * @throws IllegalStateException when the integration is enabled but
     *         misconfigured
     */
    @EventListener(ApplicationStartedEvent.class)
    public void validateConfiguration() {
        if (!properties.isEnabled()) {
            log.debug("GitHub integration disabled; skipping GitHub startup checks");
            return;
        }
        if (properties.getWebhook().getSecret() == null
                || properties.getWebhook().getSecret().isBlank()) {
            throw new IllegalStateException(
                    "github.enabled=true requires github.webhook.secret (GITHUB_WEBHOOK_SECRET)");
        }
        try {
            String baseUrl = properties.getApi().getBaseUrl() == null
                    || properties.getApi().getBaseUrl().isBlank()
                    ? GithubApiHostPolicy.DEFAULT_BASE_URL
                    : properties.getApi().getBaseUrl();
            GithubApiHostPolicy.validate(baseUrl, properties.getApi().getAllowedHosts());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid github.api configuration: " + e.getMessage());
        }
        for (String entry : properties.getApi().getAllowedHosts()) {
            if (entry != null && GithubApiHostPolicy.isPrivateIpv4(entry.trim())) {
                log.warn("Allowed GitHub API host '{}' is a private-network address; ensure this is intended",
                        entry.trim());
            }
        }
        log.info("GitHub integration enabled and configuration valid");
    }
}
