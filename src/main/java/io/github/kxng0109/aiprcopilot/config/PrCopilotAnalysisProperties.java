package io.github.kxng0109.aiprcopilot.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties for pull request copilot analysis.
 * <p>
 * Provides configurable options for analyzing pull request diffs, including constraints on maximum diff size, default
 * language, default style, and whether to include raw model output.
 *
 * <p>Must be loaded using {@code @ConfigurationProperties} with the prefix {@code prcopilot.analysis}.
 *
 * <p>Validation constraints ensure valid property values during initialization.
 *
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "prcopilot.analysis")
public class PrCopilotAnalysisProperties {

	@Min(value = 1, message = "Maximum diff characters must be greater than 1")
	private int maxDiffChars;

	@Min(value = 1024, message = "Maximum request bytes must be at least 1024")
	private long maxRequestBytes = 262144;

	@NotBlank(message = "Default language can not be blank")
	private String defaultLanguage;

	@NotBlank(message = "Default style can not be blank")
	private String defaultStyle;

	private boolean includeRawModelOutput;

	@Min(value = 1000, message = "Maximum model output characters must be at least 1000")
	private int maxModelOutputChars = 1000000;

	@Min(value = 1, message = "Maximum risks from model output must be at least 1")
	private int maxRisks = 200;

	@Min(value = 1, message = "Maximum suggested tests from model output must be at least 1")
	private int maxSuggestedTests = 100;

	@Min(value = 1, message = "Maximum touched files from model output must be at least 1")
	private int maxTouchedFiles = 500;

	@Min(value = 0, message = "Diff cache max size must not be negative (0 disables the cache)")
	private int cacheMaxSize = 1000;

	/**
	 * Per-entry cache admission budget in characters. Responses estimating
	 * above this are served but not stored, bounding worst-case heap to
	 * entries times budget regardless of model output size. A skipped put
	 * is always safe: the next identical request simply misses.
	 */
	@Min(value = 1024, message = "Diff cache max entry characters must be at least 1024")
	private long cacheMaxEntryChars = 100000;

	private Duration cacheTtl = Duration.ofMinutes(30);

	@NotBlank(message = "Minimum level can not be blank (note|warning|error)")
	private String minLevel = "note";
}
