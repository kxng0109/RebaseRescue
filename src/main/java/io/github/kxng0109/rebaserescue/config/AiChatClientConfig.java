package io.github.kxng0109.rebaserescue.config;

import io.github.kxng0109.rebaserescue.service.DiffGuardrailAdvisor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration class for initializing AI chat clients and options.
 *
 * <p>Integrates with multiple AI providers, including OpenAI, Anthropic, Google GenAI
 * (Vertex mode), and Ollama. Automatically selects and configures the primary and optional fallback clients and options
 * based on {@code MultiAiConfigurationProperties}.
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class AiChatClientConfig {

	private final MultiAiConfigurationProperties multiAiConfigurationProperties;

	private final ObjectProvider<OpenAiChatModel> openAiChatModel;
	private final ObjectProvider<AnthropicChatModel> anthropicChatModel;
	private final ObjectProvider<GoogleGenAiChatModel> googleGenAiChatModel;
	private final ObjectProvider<OllamaChatModel> ollamaChatModel;

	private final DiffGuardrailAdvisor diffGuardrailAdvisor;

	/**
	 * Constructs the primary {@code ChatClient} based on the selected AI provider.
	 *
	 * @return the primary {@code ChatClient} instance, never {@code null}
	 */
	@Primary
	@Bean
	public ChatClient primaryChatClient() {
		ChatModel primaryChatModel = chooseChatModel(multiAiConfigurationProperties.getProvider());
		return ChatClient.builder(primaryChatModel).defaultAdvisors(diffGuardrailAdvisor).build();
	}

	/**
	 * Constructs the primary {@code ChatOptions.Builder} based on the configured AI provider.
	 *
	 * <p>Spring AI 2.0 requires builders (not built instances) for
	 * {@code ChatClient.options(...)}.
	 *
	 * @return the primary options builder, never {@code null}
	 */
	@Primary
	@Bean
	public ChatOptions.Builder primaryChatOptions() {
		return constructChatOption(multiAiConfigurationProperties.getProvider());
	}

	/**
	 * Constructs a fallback {@code ChatClient} when auto-fallback is enabled.
	 *
	 * @return the fallback {@code ChatClient} instance, never {@code null}
	 * @throws IllegalStateException if auto-fallback is enabled but no fallback provider is configured
	 */
	@Bean
	@ConditionalOnProperty(name = "prcopilot.ai.auto-fallback", havingValue = "true")
	public ChatClient fallbackChatClient() {
		ChatModel fallBackChatModel =
				chooseChatModel(ProviderSupport.requireFallbackProvider(multiAiConfigurationProperties.getFallbackProvider()));
		return ChatClient.builder(fallBackChatModel).defaultAdvisors(diffGuardrailAdvisor).build();
	}

	/**
	 * Constructs fallback options when auto-fallback is enabled.
	 *
	 * @return the fallback options builder, never {@code null}
	 * @throws IllegalStateException if auto fallback is enabled but no fallback provider is configured
	 */
	@Bean
	@ConditionalOnProperty(name = "prcopilot.ai.auto-fallback", havingValue = "true")
	public ChatOptions.Builder fallbackChatOptions() {
		return constructChatOption(
				ProviderSupport.requireFallbackProvider(multiAiConfigurationProperties.getFallbackProvider()));
	}

	/**
	 * Selects the auto-configured chat model for the given provider.
	 *
	 * @param provider the {@code AiProvider} to select; must not be {@code null}
	 * @return the corresponding {@code ChatModel}, never {@code null}
	 * @throws IllegalArgumentException if the provider is not configured
	 */
	private ChatModel chooseChatModel(AiProvider provider) {
		return switch (provider) {
			case OPENAI -> {
				OpenAiChatModel model = openAiChatModel.getIfAvailable();
				if (model == null) {
					throw new IllegalArgumentException(
							"OpenAI provider is selected but not configured. Set OPENAI_API_KEY." +
									"Check .env.example for more details."
					);
				}

				yield model;
			}

			case ANTHROPIC -> {
				AnthropicChatModel model = anthropicChatModel.getIfAvailable();
				if (model == null) {
					throw new IllegalArgumentException(
							"Anthropic provider is selected but not configured. Set ANTHROPIC_API_KEY." +
									"Check .env.example for more details."
					);
				}

				yield model;
			}

			case GEMINI -> {
				GoogleGenAiChatModel model = googleGenAiChatModel.getIfAvailable();
				if (model == null) {
					throw new IllegalArgumentException(
							"Gemini provider is selected but not configured. Set GOOGLE_GENAI_PROJECT_ID/LOCATION with ADC, or GOOGLE_GENAI_API_KEY for prototyping."
									+
									"Check .env.example for more details."
					);
				}

				yield model;
			}

			case OLLAMA -> {
				OllamaChatModel model = ollamaChatModel.getIfAvailable();
				if (model == null) {
					throw new IllegalArgumentException(
							"Ollama provider is selected but not configured. Set up OLLAMA_MODEL and ensure Ollama is running."
									+
									"Check .env.example for more details.");
				}

				yield model;
			}
		};
	}

	/**
	 * Builds provider-specific options as a {@link ChatOptions.Builder}.
	 *
	 * @param provider the {@code AiProvider} to build options for; must not be {@code null}
	 * @return an options builder, never {@code null}
	 */
	private ChatOptions.Builder constructChatOption(AiProvider provider) {
		return switch (provider) {
			case OPENAI -> OpenAiChatOptions.builder()
			                                .temperature(multiAiConfigurationProperties.getTemperature())
			                                .maxTokens(multiAiConfigurationProperties.getMaxTokens());

			case ANTHROPIC -> AnthropicChatOptions.builder()
			                                      .temperature(multiAiConfigurationProperties.getTemperature())
			                                      .maxTokens(multiAiConfigurationProperties.getMaxTokens());

			case GEMINI -> GoogleGenAiChatOptions.builder()
			                                     .temperature(multiAiConfigurationProperties.getTemperature())
			                                     .maxOutputTokens(multiAiConfigurationProperties.getMaxTokens());

			case OLLAMA -> OllamaChatOptions.builder()
			                                .temperature(multiAiConfigurationProperties.getTemperature())
			                                .numPredict(multiAiConfigurationProperties.getMaxTokens());
		};
	}
}
