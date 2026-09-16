package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
public class PrCopilotLoggingPropertiesTest {
	@Autowired
	private PrCopilotLoggingProperties prcopilotLoggingProperties;

	@Test
	void shouldDefaultLoggingFlagsToFalse() {
		assertFalse(prcopilotLoggingProperties.isLogPrompts());
		assertFalse(prcopilotLoggingProperties.isLogResponses());
	}
}
