package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class MultiAiConfigurationPropertiesTest {

	@Autowired
	private MultiAiConfigurationProperties multiAiConfigurationProperties;

	@Test
	void shouldBindDefaultsFromApplicationYaml() {
		assertEquals(AiProvider.OPENAI, multiAiConfigurationProperties.getProvider());
		assertNull(multiAiConfigurationProperties.getFallbackProvider());
		assertFalse(multiAiConfigurationProperties.isAutoFallback());
		assertEquals(0.1, multiAiConfigurationProperties.getTemperature());
		assertEquals(1024, multiAiConfigurationProperties.getMaxTokens());
		assertEquals(30000L, multiAiConfigurationProperties.getTimeoutMillis());
	}
}
