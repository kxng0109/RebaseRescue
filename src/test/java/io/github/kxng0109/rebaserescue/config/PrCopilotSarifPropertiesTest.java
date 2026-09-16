package io.github.kxng0109.rebaserescue.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrCopilotSarifPropertiesTest {

	private static final Validator VALIDATOR =
			Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void defaults_shouldTargetGithub() {
		PrCopilotSarifProperties properties = new PrCopilotSarifProperties();

		assertThat(properties.getConsumer()).isEqualTo(SarifConsumer.GITHUB);
		assertThat(properties.getCategory()).isEqualTo("rebase-rescue");
		assertThat(properties.getSuppressFile()).isEqualTo(".ai-review-ignore.yml");
		assertThat(VALIDATOR.validate(properties)).isEmpty();
	}

	@Test
	void validate_shouldRejectNullConsumerAndBlankCategory() {
		PrCopilotSarifProperties properties = new PrCopilotSarifProperties();
		properties.setConsumer(null);
		properties.setCategory("  ");

		assertThat(VALIDATOR.validate(properties)).hasSize(2);
	}
}
