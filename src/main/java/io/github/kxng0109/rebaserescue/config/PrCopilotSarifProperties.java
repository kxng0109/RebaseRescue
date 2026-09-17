package io.github.kxng0109.rebaserescue.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SARIF output settings.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "prcopilot.sarif")
public class PrCopilotSarifProperties {

	/**
	 * Target consumer: {@code GITHUB} (Code Scanning) or {@code SONAR} (bound case-insensitively).
	 */
	@NotNull(message = "SARIF consumer must not be null")
	private SarifConsumer consumer = SarifConsumer.GITHUB;

	/**
	 * SARIF run category ({@code runAutomationDetails.id}).
	 */
	@NotBlank(message = "SARIF category can not be blank")
	private String category = "rebase-rescue";

	/**
	 * Path to the repo-local false-positive suppress file. Empty disables suppression.
	 */
	private String suppressFile = ".ai-review-ignore.yml";

	/**
	 * Maximum serialized SARIF document bytes accepted for upload. Rejects
	 * early, well under GitHub's 10MB gzipped cap, instead of discovering
	 * rejection after minutes of polling.
	 */
	@Min(value = 1024, message = "SARIF max bytes must be at least 1024")
	private long maxBytes = 5000000;
}
