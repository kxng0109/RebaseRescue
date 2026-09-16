package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSupportTest {

	@ParameterizedTest
	@EnumSource(AiProvider.class)
	void requireFallbackProvider_shouldEcho_whenPresent(AiProvider provider) {
		assertThat(ProviderSupport.requireFallbackProvider(provider)).isEqualTo(provider);
	}

	@Test
	void requireFallbackProvider_shouldThrow_whenNull() {
		assertThatThrownBy(() -> ProviderSupport.requireFallbackProvider(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PRCOPILOT_AI_FALLBACK_PROVIDER");
	}
}
