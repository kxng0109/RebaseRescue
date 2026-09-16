package io.github.kxng0109.aiprcopilot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * GitHub App integration configuration.
 *
 * <p>All secrets (private key, webhook secret, tokens) are env-injected only
 * ({@code GITHUB_*}) and never logged. The integration is opt-in via
 * {@code github.enabled}; when disabled no beans fail and no network calls
 * are attempted. When enabled, {@code app.private-key} and
 * {@code webhook.secret} are required and validated at first use.</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "github")
public class GithubProperties {

    /**
     * Master switch: {@code true} to enable GitHub webhook + API integration.
     */
    private boolean enabled = false;

    @Valid
    private App app = new App();

    @Valid
    private Webhook webhook = new Webhook();

    @Valid
    private Api api = new Api();

    @Valid
    private Sarif sarif = new Sarif();

    @Getter
    @Setter
    public static class App {

        /**
         * GitHub App ID (numeric as string) or client ID (Iv1.*). Used as
         * JWT {@code iss}. GitHub recommends client ID since May 2024;
         * either value is accepted when it matches the app's registration.
         */
        private String appId;

        /**
         * GitHub App client ID (e.g. {@code Iv1.xxxxx}). Preferred over
         * numeric app ID for JWT {@code iss}. When set it takes precedence
         * over {@code appId}.
         */
        private String clientId;

        /**
         * PEM private key: either the raw PEM text (with or without
         * {@code \n} escapes) or a path prefixed {@code file:}.
         * Must be PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) or PKCS#8
         * ({@code BEGIN PRIVATE KEY}).
         */
        private String privateKey;

        /**
         * Installation ID for single-installation mode. Resolved from
         * {@code GITHUB_APP_INSTALLATION_ID}.
         */
        private Long installationId;
    }

    @Getter
    @Setter
    public static class Webhook {

        /**
         * Webhook secret for HMAC-SHA256 verification
         * ({@code X-Hub-Signature-256: sha256=<hex>}). High entropy,
         * 32+ random bytes recommended.
         */
        private String secret;

        /**
         * GitHub delivery GUID dedup store TTL. Covers the 3-day redelivery
         * window with margin.
         */
        private Duration dedupTtl = Duration.ofDays(30);

        /**
         * Maximum number of delivery GUIDs retained.
         */
        private int dedupMaxSize = 100000;

        /**
         * Maximum webhook request body bytes. GitHub pull_request deliveries
         * are metadata only (diff is fetched separately), so 1MB is generous.
         */
        @Min(value = 1024, message = "Maximum webhook request bytes must be at least 1024")
        private long maxRequestBytes = 1048576;
    }

    @Getter
    @Setter
    public static class Api {

        /**
         * GitHub API base URL. Default {@code https://api.github.com}.
         */
        @NotBlank
        private String baseUrl = "https://api.github.com";

        /**
         * GitHub REST API version header. Current stable is
         * {@code 2026-03-10}.
         */
        @NotBlank
        private String apiVersion = "2026-03-10";

        /**
         * Installation token cache TTL. GitHub tokens live 1h;
         * 55m keeps a safety margin before expiry.
         */
        private Duration tokenCacheTtl = Duration.ofMinutes(55);

        /**
         * Maximum cached installation tokens.
         */
        private int tokenCacheMaxSize = 100;
    }

    @Getter
    @Setter
    public static class Sarif {

        /**
         * SARIF category conveyed via {@code runs[0].automationDetails.id}
         * as {@code category/run-id}. Distinct per tool+category avoids
         * GitHub's post-June-2025 multi-run rejection.
         */
        @NotBlank
        private String category = "ai-pr-copilot";
    }
}
