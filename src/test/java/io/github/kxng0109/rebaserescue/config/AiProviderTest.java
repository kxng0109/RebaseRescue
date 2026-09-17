package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderTest {

	@ParameterizedTest
	@CsvSource({
			"openai, OPENAI",
			"OpenAI, OPENAI",
			"ANTHROPIC, ANTHROPIC",
			"Gemini, GEMINI",
			"OLLAMA, OLLAMA"
	})
	void fromValue_shouldMatchCaseInsensitively(String raw, AiProvider expected) {
		assertThat(AiProvider.fromValue(raw)).isEqualTo(expected);
	}

	@Test
	void fromValue_shouldNotTrim() {
		assertThatThrownBy(() -> AiProvider.fromValue(" openai "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fromValue_shouldMatchWithoutTrimming() {
		assertThat(AiProvider.fromValue("openai")).isEqualTo(AiProvider.OPENAI);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"bogus", "gpt-4"})
	void fromValue_shouldThrow_whenUnknown(String raw) {
		assertThatThrownBy(() -> AiProvider.fromValue(raw))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown AI provider");
	}

	@Test
	void getValue_shouldExposeIdentifier() {
		assertThat(AiProvider.OPENAI.getValue()).isEqualTo("openai");
		assertThat(AiProvider.OLLAMA.getValue()).isEqualTo("ollama");
	}
}
