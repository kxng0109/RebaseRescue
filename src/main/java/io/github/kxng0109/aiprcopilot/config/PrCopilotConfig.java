package io.github.kxng0109.aiprcopilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
		PrCopilotAnalysisProperties.class,
		PrCopilotLoggingProperties.class,
		MultiAiConfigurationProperties.class,
		PrCopilotAuthProperties.class,
		PrCopilotSarifProperties.class,
		GithubProperties.class
})
public class PrCopilotConfig {
}
