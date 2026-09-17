package io.github.kxng0109.rebaserescue.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrCopilotAuthPropertiesTest {

	private static final Validator VALIDATOR =
			Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void defaults_shouldBeSelfhostWithEmptyKey() {
		PrCopilotAuthProperties properties = new PrCopilotAuthProperties();

		assertThat(properties.getMode()).isEqualTo(AuthMode.SELFHOST);
		assertThat(properties.getApiKey()).isEmpty();
		assertThat(properties.getApiKeyHeader()).isEqualTo("Authorization");
		assertThat(VALIDATOR.validate(properties)).isEmpty();
	}

	@Test
	void validate_shouldRejectNullMode() {
		PrCopilotAuthProperties properties = new PrCopilotAuthProperties();
		properties.setMode(null);

		assertThat(VALIDATOR.validate(properties)).hasSize(1);
	}
}
