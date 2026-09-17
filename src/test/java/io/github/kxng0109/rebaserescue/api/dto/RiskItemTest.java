package io.github.kxng0109.rebaserescue.api.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskItemTest {

	@ParameterizedTest
	@CsvSource({
			"error, error",
			"CRITICAL, error",
			" High , error",
			"blocker, error",
			"warning, warning",
			"MEDIUM, warning",
			" major , warning",
			"note, note",
			"low, note",
			"MINOR, note",
			" Info , note"
	})
	void normalizeLevel_shouldMapAliases(String raw, String expected) {
		assertThat(RiskItem.normalizeLevel(raw)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  ", "bogus", "err"})
	void normalizeLevel_shouldThrow_whenUnrecognized(String raw) {
		assertThatThrownBy(() -> RiskItem.normalizeLevel(raw))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void record_shouldExposeComponents() {
		RiskItem item = new RiskItem("warning", "msg");

		assertThat(item.level()).isEqualTo("warning");
		assertThat(item.message()).isEqualTo("msg");
	}
}
