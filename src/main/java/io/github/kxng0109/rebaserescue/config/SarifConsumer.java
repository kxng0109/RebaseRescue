package io.github.kxng0109.rebaserescue.config;

/**
 * SARIF consumer the output targets.
 *
 * <p>Bound case-insensitively from {@code prcopilot.sarif.consumer}; any other value
 * fails startup binding instead of being silently accepted.
 */
public enum SarifConsumer {

	/**
	 * GitHub Code Scanning.
	 */
	GITHUB,

	/**
	 * SonarQube import.
	 */
	SONAR
}
