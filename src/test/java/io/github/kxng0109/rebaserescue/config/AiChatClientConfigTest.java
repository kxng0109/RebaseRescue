package io.github.kxng0109.rebaserescue.config;

import io.github.kxng0109.rebaserescue.service.DiffGuardrailAdvisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatClientConfigTest {

	@Mock
	private MultiAiConfigurationProperties properties;

	@Mock
	private ObjectProvider<OpenAiChatModel> openAiModels;

	@Mock
	private ObjectProvider<AnthropicChatModel> anthropicModels;

	@Mock
	private ObjectProvider<GoogleGenAiChatModel> geminiModels;

	@Mock
	private ObjectProvider<OllamaChatModel> ollamaModels;

	@Mock
	private DiffGuardrailAdvisor advisor;

	@Mock
	private OpenAiChatModel openAiModel;

	@Mock
	private AnthropicChatModel anthropicModel;

	@Mock
	private GoogleGenAiChatModel geminiModel;

	@Mock
	private OllamaChatModel ollamaModel;

	private AiChatClientConfig config() {
		return new AiChatClientConfig(
				properties, openAiModels, anthropicModels, geminiModels, ollamaModels, advisor);
	}

	@Test
	void primaryChatClient_shouldBuildOpenAi() {
		when(properties.getProvider()).thenReturn(AiProvider.OPENAI);
		when(openAiModels.getIfAvailable()).thenReturn(openAiModel);

		ChatClient client = config().primaryChatClient();

		assertThat(client).isNotNull();
	}

	@Test
	void primaryChatClient_shouldThrow_whenOpenAiMissing() {
		when(properties.getProvider()).thenReturn(AiProvider.OPENAI);
		when(openAiModels.getIfAvailable()).thenReturn(null);

		assertThatThrownBy(() -> config().primaryChatClient())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("OPENAI_API_KEY");
	}

	@Test
	void primaryChatClient_shouldBuildAnthropic() {
		when(properties.getProvider()).thenReturn(AiProvider.ANTHROPIC);
		when(anthropicModels.getIfAvailable()).thenReturn(anthropicModel);

		assertThat(config().primaryChatClient()).isNotNull();
	}

	@Test
	void primaryChatClient_shouldThrow_whenAnthropicMissing() {
		when(properties.getProvider()).thenReturn(AiProvider.ANTHROPIC);
		when(anthropicModels.getIfAvailable()).thenReturn(null);

		assertThatThrownBy(() -> config().primaryChatClient())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ANTHROPIC_API_KEY");
	}

	@Test
	void primaryChatClient_shouldBuildGemini() {
		when(properties.getProvider()).thenReturn(AiProvider.GEMINI);
		when(geminiModels.getIfAvailable()).thenReturn(geminiModel);

		assertThat(config().primaryChatClient()).isNotNull();
	}

	@Test
	void primaryChatClient_shouldThrow_whenGeminiMissing() {
		when(properties.getProvider()).thenReturn(AiProvider.GEMINI);
		when(geminiModels.getIfAvailable()).thenReturn(null);

		assertThatThrownBy(() -> config().primaryChatClient())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("GOOGLE_GENAI_PROJECT_ID");
	}

	@Test
	void primaryChatClient_shouldBuildOllama() {
		when(properties.getProvider()).thenReturn(AiProvider.OLLAMA);
		when(ollamaModels.getIfAvailable()).thenReturn(ollamaModel);

		assertThat(config().primaryChatClient()).isNotNull();
	}

	@Test
	void primaryChatClient_shouldThrow_whenOllamaMissing() {
		when(properties.getProvider()).thenReturn(AiProvider.OLLAMA);
		when(ollamaModels.getIfAvailable()).thenReturn(null);

		assertThatThrownBy(() -> config().primaryChatClient())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("OLLAMA_MODEL");
	}

	@Test
	void primaryChatOptions_shouldBuildPerProvider() {
		when(properties.getTemperature()).thenReturn(0.1);
		when(properties.getMaxTokens()).thenReturn(1024);

		when(properties.getProvider()).thenReturn(AiProvider.OPENAI);
		assertThat(config().primaryChatOptions()).isNotNull();
		when(properties.getProvider()).thenReturn(AiProvider.ANTHROPIC);
		assertThat(config().primaryChatOptions()).isNotNull();
		when(properties.getProvider()).thenReturn(AiProvider.GEMINI);
		assertThat(config().primaryChatOptions()).isNotNull();
		when(properties.getProvider()).thenReturn(AiProvider.OLLAMA);
		assertThat(config().primaryChatOptions()).isNotNull();
	}

	@Test
	void fallbackChatClient_shouldBuild_whenConfigured() {
		when(properties.getFallbackProvider()).thenReturn(AiProvider.ANTHROPIC);
		when(anthropicModels.getIfAvailable()).thenReturn(anthropicModel);

		assertThat(config().fallbackChatClient()).isNotNull();
	}

	@Test
	void fallbackChatClient_shouldThrow_whenMissing() {
		when(properties.getFallbackProvider()).thenReturn(null);

		assertThatThrownBy(() -> config().fallbackChatClient())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PRCOPILOT_AI_FALLBACK_PROVIDER");
	}

	@Test
	void fallbackChatOptions_shouldBuild_whenConfigured() {
		when(properties.getFallbackProvider()).thenReturn(AiProvider.OLLAMA);
		when(properties.getTemperature()).thenReturn(0.1);
		when(properties.getMaxTokens()).thenReturn(1024);

		ChatOptions.Builder options = config().fallbackChatOptions();

		assertThat(options).isNotNull();
	}

	@Test
	void fallbackChatOptions_shouldThrow_whenMissing() {
		when(properties.getFallbackProvider()).thenReturn(null);

		assertThatThrownBy(() -> config().fallbackChatOptions())
				.isInstanceOf(IllegalStateException.class);
	}
}
