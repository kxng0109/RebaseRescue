package io.github.kxng0109.rebaserescue.config;

import com.sun.net.httpserver.HttpServer;
import io.github.kxng0109.rebaserescue.error.CustomApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AppStartupCheckTest {

	@Mock
	private MultiAiConfigurationProperties multiAiConfig;

	private AppStartupCheck appStartupCheck;

	@BeforeEach
	void setup() {
		appStartupCheck = new AppStartupCheck(multiAiConfig);
	}

	@Test
	void validateConfiguration_shouldPass_whenOpenAiConfiguredCorrectly() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
		when(multiAiConfig.isAutoFallback()).thenReturn(false);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid-key");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldFail_whenOpenAiKeyIsMissing() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "");

		CustomApiException exception = assertThrows(
				CustomApiException.class,
				() -> appStartupCheck.validateConfiguration()
		);

		assertTrue(exception.getMessage().contains("OPENAI"));
		assertTrue(exception.getMessage().contains("not configured"));
	}

	@Test
	void validateConfiguration_shouldFail_whenOpenAiKeyIsDefault() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "default-value");

		CustomApiException exception = assertThrows(
				CustomApiException.class,
				() -> appStartupCheck.validateConfiguration()
		);

		assertTrue(exception.getMessage().contains("OPENAI"));
	}

	@Test
	void validateConfiguration_shouldPass_whenAnthropicConfiguredCorrectly() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.ANTHROPIC);
		when(multiAiConfig.isAutoFallback()).thenReturn(false);

		ReflectionTestUtils.setField(appStartupCheck, "anthropicApiKey", "sk-ant-valid-key");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldFail_whenAnthropicKeyIsMissing() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.ANTHROPIC);

		ReflectionTestUtils.setField(appStartupCheck, "anthropicApiKey", null);

		CustomApiException exception = assertThrows(
				CustomApiException.class,
				() -> appStartupCheck.validateConfiguration()
		);

		assertTrue(exception.getMessage().contains("ANTHROPIC"));
	}

	@Test
	void validateConfiguration_shouldPass_whenGeminiConfiguredCorrectly() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.GEMINI);
		when(multiAiConfig.isAutoFallback()).thenReturn(false);

		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiProjectId", "my-gcp-project");
		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiLocation", "us-central1");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldFail_whenGeminiProjectIdMissing() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.GEMINI);

		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiProjectId", "");
		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiLocation", "");
		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiApiKey", "");

		CustomApiException exception = assertThrows(
				CustomApiException.class,
				() -> appStartupCheck.validateConfiguration()
		);

		assertTrue(exception.getMessage().contains("Gemini"));
		assertTrue(exception.getMessage().contains("GOOGLE_GENAI_PROJECT_ID"));
	}

	@Test
	void validateConfiguration_shouldPass_whenFallbackConfiguredCorrectly() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
		when(multiAiConfig.isAutoFallback()).thenReturn(true);
		when(multiAiConfig.getFallbackProvider()).thenReturn(AiProvider.ANTHROPIC);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid");
		ReflectionTestUtils.setField(appStartupCheck, "anthropicApiKey", "sk-ant-valid");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldFail_whenFallbackEnabledButNotConfigured() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
		when(multiAiConfig.isAutoFallback()).thenReturn(true);
		when(multiAiConfig.getFallbackProvider()).thenReturn(null);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid");

		RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> appStartupCheck.validateConfiguration()
		);

		assertTrue(exception.getMessage().contains("Auto-fallback is enabled"));
		assertTrue(exception.getMessage().contains("no fallback provider is configured"));
	}

	@Test
	void validateConfiguration_shouldFail_whenFallbackProviderKeyMissing() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
		when(multiAiConfig.isAutoFallback()).thenReturn(true);
		when(multiAiConfig.getFallbackProvider()).thenReturn(AiProvider.ANTHROPIC);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid");
		ReflectionTestUtils.setField(appStartupCheck, "anthropicApiKey", "");

		CustomApiException exception = assertThrows(
				CustomApiException.class,
				() -> appStartupCheck.validateConfiguration()
		);

		assertTrue(exception.getMessage().contains("ANTHROPIC"));
	}

	@Test
	void validateConfiguration_shouldPass_whenOllamaSelected() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OLLAMA);
		ReflectionTestUtils.setField(appStartupCheck, "ollamaBaseUrl", "http://localhost:11434");
		ReflectionTestUtils.setField(appStartupCheck, "ollamaChatModel", "qwen3:4b");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldWarnButNotFail_whenPingEnabledAndUnreachable() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
		when(multiAiConfig.isAutoFallback()).thenReturn(false);
		when(multiAiConfig.isHealthCheckPing()).thenReturn(true);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid-key");
		ReflectionTestUtils.setField(appStartupCheck, "openAiBaseUrl", "http://127.0.0.1:9");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldSkipPing_whenPingEnabledButNoBaseUrl() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
		when(multiAiConfig.isAutoFallback()).thenReturn(false);
		when(multiAiConfig.isHealthCheckPing()).thenReturn(true);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid-key");
		ReflectionTestUtils.setField(appStartupCheck, "openAiBaseUrl", "");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldReturnEarly_whenSkipStartupCheck() {
		ReflectionTestUtils.setField(appStartupCheck, "skipStartupCheck", true);

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
		verifyNoInteractions(multiAiConfig);
	}

	@Test
	void validateConfiguration_shouldWarn_whenFallbackEqualsPrimary() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
		when(multiAiConfig.isAutoFallback()).thenReturn(true);
		when(multiAiConfig.getFallbackProvider()).thenReturn(AiProvider.OPENAI);

		ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid-key");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldFail_whenOllamaMisconfigured() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.OLLAMA);

		ReflectionTestUtils.setField(appStartupCheck, "ollamaBaseUrl", "");
		ReflectionTestUtils.setField(appStartupCheck, "ollamaChatModel", "qwen3:4b");

		assertThatThrownBy(() -> appStartupCheck.validateConfiguration())
				.isInstanceOf(CustomApiException.class)
				.hasMessageContaining("OLLAMA");
	}

	@Test
	void validateConfiguration_shouldWarn_whenGeminiApiKeyMode() {
		when(multiAiConfig.getProvider()).thenReturn(AiProvider.GEMINI);
		when(multiAiConfig.isAutoFallback()).thenReturn(false);

		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiProjectId", "");
		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiLocation", "");
		ReflectionTestUtils.setField(appStartupCheck, "googleGenAiApiKey", "dev-key");

		assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
	}

	@Test
	void validateConfiguration_shouldPingSuccessfully_whenReachable() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(
				"/", exchange -> {
					byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(200, body.length);
					try (var out = exchange.getResponseBody()) {
						out.write(body);
					}
				}
		);
		server.start();
		try {
			when(multiAiConfig.getProvider()).thenReturn(AiProvider.OPENAI);
			when(multiAiConfig.isAutoFallback()).thenReturn(false);
			when(multiAiConfig.isHealthCheckPing()).thenReturn(true);

			ReflectionTestUtils.setField(appStartupCheck, "openAiApiKey", "sk-valid-key");
			ReflectionTestUtils.setField(
					appStartupCheck, "openAiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());

			assertDoesNotThrow(() -> appStartupCheck.validateConfiguration());
		} finally {
			server.stop(0);
		}
	}
}