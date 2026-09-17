package io.github.kxng0109.rebaserescue.config;

/**
 * Single source of truth for fallback-provider guards.
 */
public final class ProviderSupport {

	private ProviderSupport() {
	}

	/**
	 * Returns the fallback provider or throws when auto-fallback is enabled without one.
	 *
	 * @param fallbackProvider the configured fallback; may be {@code null}
	 * @return the fallback provider, never {@code null}
	 * @throws IllegalStateException when no fallback provider is configured
	 */
	public static AiProvider requireFallbackProvider(AiProvider fallbackProvider) {
		if (fallbackProvider == null) {
			throw new IllegalStateException(
					"Auto-fallback is enabled but no fallback provider is configured. Please set PRCOPILOT_AI_FALLBACK_PROVIDER or disable auto-fallback."
			);
		}
		return fallbackProvider;
	}
}
