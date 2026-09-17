package io.github.kxng0109.rebaserescue.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for logging functionality within RebaseRescue.
 * <p>
 * These properties control whether prompts and responses are logged during interactions.
 *
 * <p>Property prefix: {@code prcopilot.logging}.
 *
 * <p>This configuration is typically used in conjunction with {@code PrCopilotConfig}.
 *
 * @see PrCopilotConfig
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "prcopilot.logging")
public class PrCopilotLoggingProperties {

	private boolean logPrompts;

	private boolean logResponses;
}
