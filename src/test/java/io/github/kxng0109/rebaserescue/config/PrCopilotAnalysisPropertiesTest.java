package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


@SpringBootTest
public class PrCopilotAnalysisPropertiesTest {
	@Autowired
	private PrCopilotAnalysisProperties prCopilotAnalysisProperties;

	@Test
	void shouldBindDefaultsFromApplicationYaml() {
		assertEquals("en", prCopilotAnalysisProperties.getDefaultLanguage());
		assertEquals(50000, prCopilotAnalysisProperties.getMaxDiffChars());
		assertEquals("conventional-commits", prCopilotAnalysisProperties.getDefaultStyle());
		assertFalse(prCopilotAnalysisProperties.isIncludeRawModelOutput());
	}
}
