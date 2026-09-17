package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.config.MultiAiConfigurationProperties;
import io.github.kxng0109.rebaserescue.error.CustomApiException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.*;

/**
 * Service class for interacting with AI models via a client library.
 * <p>
 * Provides methods to call AI models with specific inputs, configurations, and error handling. Calls run on a dedicated
 * virtual-thread executor (never the common ForkJoinPool) with a cancellable timeout so timed-out AI calls do not leak
 * threads or connections.
 */
@Service
@Slf4j
@RequiredArgsConstructor
class AiChatService {

	private final MultiAiConfigurationProperties aiConfigurationProperties;

	private final ExecutorService aiExecutor = Executors.newVirtualThreadPerTaskExecutor();

	/**
	 * Executes a call to an AI model using the specified prompt, client, and options.
	 *
	 * @param prompt      the prompt to send to the AI model, must not be {@code null}
	 * @param chatClient  the {@code ChatClient} used to interact with the AI model, must not be {@code null}
	 * @param chatOptions the options for configuring the AI call, must not be {@code null}
	 * @return the {@code ChatResponse} from the AI model, never {@code null}
	 * @throws CustomApiException if the request fails due to timeouts, address resolution issues, or resource access
	 *                            errors
	 * @throws RuntimeException   if any unexpected errors occur during the call
	 */
	public ChatResponse callAiModel(Prompt prompt, ChatClient chatClient, ChatOptions.Builder chatOptions) {
		log.debug("Request timeout set: {}", aiConfigurationProperties.getTimeoutMillis());
		CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(
				                                                          () ->
						                                                          chatClient.prompt(prompt)
						                                                                    .options(chatOptions)
						                                                                    .call()
						                                                                    .chatResponse(), aiExecutor
		                                                          )
		                                                          .orTimeout(
				                                                          aiConfigurationProperties.getTimeoutMillis(),
				                                                          TimeUnit.MILLISECONDS
		                                                          );
		try {
			return future.join();
		} catch (java.util.concurrent.CompletionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof TimeoutException) {
				future.cancel(true);
				log.error("AI Model timed out after {} milliseconds", aiConfigurationProperties.getTimeoutMillis());
				throw new CustomApiException("AI Model request timed out", HttpStatus.GATEWAY_TIMEOUT, cause);
			}
			return mapFailure(cause);
		}
	}

	/**
	 * Streams an AI model call as a reactive flux of partial responses.
	 *
	 * @param prompt      the prompt to send to the AI model, must not be {@code null}
	 * @param chatClient  the {@code ChatClient} used to interact with the AI model, must not be {@code null}
	 * @param chatOptions the options for configuring the AI call, must not be {@code null}
	 * @return flux of {@code ChatResponse} chunks, never {@code null}
	 */
	public Flux<ChatResponse> streamAiModel(Prompt prompt, ChatClient chatClient, ChatOptions.Builder chatOptions) {
		log.debug("Streaming AI call with timeout {} ms", aiConfigurationProperties.getTimeoutMillis());
		return chatClient.prompt(prompt)
		                 .options(chatOptions)
		                 .stream()
		                 .chatResponse()
		                 .timeout(java.time.Duration.ofMillis(aiConfigurationProperties.getTimeoutMillis()))
		                 .onErrorMap(
				                 TimeoutException.class,
				                 e -> new CustomApiException(
						                 "AI Model request timed out",
						                 HttpStatus.GATEWAY_TIMEOUT, e
				                 )
		                 );
	}

	private ChatResponse mapFailure(Throwable e) {
		if (e instanceof UnresolvedAddressException unresolved) {
			log.error("Failed to resolve remote service address: {}", unresolved.getMessage(), unresolved);
			throw new CustomApiException(
					"Failed to resolve remote service address: " + unresolved.getMessage(),
					HttpStatus.BAD_GATEWAY, unresolved
			);
		}
		if (e instanceof ResourceAccessException resourceAccessException) {
			log.error(
					"Failed to access remote resource: {}", resourceAccessException.getMessage(),
					resourceAccessException
			);
			HttpStatus status = resourceAccessException.getCause() instanceof java.net.SocketTimeoutException
					? HttpStatus.GATEWAY_TIMEOUT
					: HttpStatus.BAD_GATEWAY;
			throw new CustomApiException(
					"Failed to access remote resource: " + resourceAccessException.getMessage(),
					status, resourceAccessException
			);
		}
		if (e instanceof CustomApiException customApiException) {
			throw customApiException;
		}
		if (e instanceof RuntimeException runtimeException) {
			log.error("Unexpected error during remote call: {}", runtimeException.getMessage(), runtimeException);
			throw new RuntimeException(
					"Unexpected error during remote call: " + runtimeException.getMessage(),
					runtimeException
			);
		}
		log.error("Unexpected error during remote call: {}", e.getMessage(), e);
		throw new RuntimeException("Unexpected error during remote call: " + e.getMessage(), e);
	}

	@PreDestroy
	void shutdown() {
		aiExecutor.shutdownNow();
	}
}
