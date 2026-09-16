package io.github.kxng0109.rebaserescue.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationValidationTest {

	private static final Validator VALIDATOR =
			Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void multiAi_shouldAcceptBoundaryValues() {
		MultiAiConfigurationProperties properties = validMultiAi();

		assertThat(VALIDATOR.validate(properties)).isEmpty();
	}

	@Test
	void multiAi_shouldRejectOutOfRangeValues() {
		MultiAiConfigurationProperties temperature = validMultiAi();
		temperature.setTemperature(1.5);
		assertThat(VALIDATOR.validate(temperature)).isNotEmpty();

		MultiAiConfigurationProperties tokens = validMultiAi();
		tokens.setMaxTokens(0);
		assertThat(VALIDATOR.validate(tokens)).isNotEmpty();

        MultiAiConfigurationProperties timeout = validMultiAi();
        timeout.setTimeoutMillis(999);
        assertThat(VALIDATOR.validate(timeout)).isNotEmpty();
    }

	@Test
	void analysis_shouldAcceptBoundaryValues() {
		PrCopilotAnalysisProperties properties = validAnalysis();

		assertThat(VALIDATOR.validate(properties)).isEmpty();
	}

	@Test
	void analysis_shouldRejectOutOfRangeValues() {
		PrCopilotAnalysisProperties diffChars = validAnalysis();
		diffChars.setMaxDiffChars(0);
		assertThat(VALIDATOR.validate(diffChars)).isNotEmpty();

		PrCopilotAnalysisProperties requestBytes = validAnalysis();
		requestBytes.setMaxRequestBytes(1023);
		assertThat(VALIDATOR.validate(requestBytes)).isNotEmpty();

		PrCopilotAnalysisProperties outputChars = validAnalysis();
		outputChars.setMaxModelOutputChars(999);
		assertThat(VALIDATOR.validate(outputChars)).isNotEmpty();

		PrCopilotAnalysisProperties risks = validAnalysis();
		risks.setMaxRisks(0);
		assertThat(VALIDATOR.validate(risks)).isNotEmpty();

		PrCopilotAnalysisProperties language = validAnalysis();
		language.setDefaultLanguage("  ");
		assertThat(VALIDATOR.validate(language)).isNotEmpty();
	}

	private static MultiAiConfigurationProperties validMultiAi() {
		MultiAiConfigurationProperties properties = new MultiAiConfigurationProperties();
		properties.setProvider(AiProvider.OPENAI);
		properties.setTemperature(0.1);
		properties.setMaxTokens(1024);
        properties.setTimeoutMillis(30000);
        return properties;
    }

	private static PrCopilotAnalysisProperties validAnalysis() {
		PrCopilotAnalysisProperties properties = new PrCopilotAnalysisProperties();
		properties.setMaxDiffChars(50000);
		properties.setMaxRequestBytes(262144);
		properties.setMaxModelOutputChars(1000000);
		properties.setMaxRisks(200);
		properties.setMaxSuggestedTests(100);
		properties.setMaxTouchedFiles(500);
		properties.setDefaultLanguage("en");
		properties.setDefaultStyle("conventional-commits");
		properties.setCacheMaxSize(1000);
		properties.setCacheTtl(Duration.ofMinutes(30));
		properties.setMinLevel("note");
		return properties;
	}
}
