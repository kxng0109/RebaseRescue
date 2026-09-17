package io.github.kxng0109.rebaserescue.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for managing multiple AI providers in RebaseRescue.
 *
 * <p>Defines settings for primary and fallback providers, token usage limits,
 * request timeouts, and behavior for automatic fallback.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "prcopilot.ai")
public class MultiAiConfigurationProperties {

	@NotNull
	private AiProvider provider = AiProvider.OPENAI;

	private AiProvider fallbackProvider;

	private boolean autoFallback = false;

	@Min(value = 0, message = "Temperature must be between 0.0 and 1.0 inclusive")
	@Max(value = 1, message = "Temperature must be between 0.0 and 1.0 inclusive")
	private double temperature = 0.1;

	@Min(value = 1, message = "Max tokens can not be less than 1")
	private int maxTokens = 1024;

	@Min(value = 1000, message = "Request timeout must be at least 1000ms")
	private long timeoutMillis = 30000;

	/**
	 * Opt-in startup reachability ping of the primary/fallback provider base URLs. Warn-only: a failed ping logs a
	 * warning but never fails startup, so transient network blips cannot take the service down.
	 */
	private boolean healthCheckPing = false;
}
