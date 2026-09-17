package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderConverterTest {

	private final AiProviderConverter converter = new AiProviderConverter();

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"  ", "\t"})
	void convert_shouldReturnNull_whenBlank(String source) {
		assertThat(converter.convert(source)).isNull();
	}

	@Test
	void convert_shouldTrimAndIgnoreCase() {
		assertThat(converter.convert(" openai ")).isEqualTo(AiProvider.OPENAI);
		assertThat(converter.convert("ANTHROPIC")).isEqualTo(AiProvider.ANTHROPIC);
	}

	@Test
	void convert_shouldThrow_whenUnknown() {
		assertThatThrownBy(() -> converter.convert("bogus"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
