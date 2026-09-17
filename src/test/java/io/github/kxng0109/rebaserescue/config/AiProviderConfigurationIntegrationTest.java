package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(
		properties = {
				"prcopilot.ai.provider=openai",
				"prcopilot.ai.auto-fallback=false",
				"spring.ai.openai.api-key=test-key"
		}
)
public class AiProviderConfigurationIntegrationTest {

	@Autowired
	private MultiAiConfigurationProperties multiAiConfig;

	@Autowired
	private PrCopilotAnalysisProperties analysisProperties;

	@Autowired
	private PrCopilotAuthProperties authProperties;

	@Autowired
	private PrCopilotSarifProperties sarifProperties;

	@Test
	void contextLoads() {
		assertNotNull(multiAiConfig);
		assertNotNull(analysisProperties);
	}

	@Test
	void shouldLoadPrimaryProviderConfiguration() {
		assertEquals(AiProvider.OPENAI, multiAiConfig.getProvider());
		assertFalse(multiAiConfig.isAutoFallback());
		assertNull(multiAiConfig.getFallbackProvider());
	}

	@Test
	void shouldLoadAnalysisPropertiesWithDefaults() {
		assertEquals(50000, analysisProperties.getMaxDiffChars());
		assertEquals("en", analysisProperties.getDefaultLanguage());
		assertEquals("conventional-commits", analysisProperties.getDefaultStyle());
		assertFalse(analysisProperties.isIncludeRawModelOutput());
	}

	@Test
	void shouldLoadAiGenerationProperties() {
		assertEquals(0.1, multiAiConfig.getTemperature(), 0.001);
		assertEquals(1024, multiAiConfig.getMaxTokens());
		assertEquals(30000L, multiAiConfig.getTimeoutMillis());
	}

	@Test
	void shouldBindAuthModeEnumCaseInsensitively() {
		assertEquals(AuthMode.SELFHOST, authProperties.getMode());
	}

	@Test
	void shouldBindSarifConsumerDefault() {
		assertEquals(SarifConsumer.GITHUB, sarifProperties.getConsumer());
	}
}
