package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.config.MultiAiConfigurationProperties;
import io.github.kxng0109.rebaserescue.error.CustomApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

	@Mock
	private MultiAiConfigurationProperties properties;

	@Mock
	private ChatClient chatClient;

	@Mock
	private ChatClient.ChatClientRequestSpec requestSpec;

	@Mock
	private ChatClient.CallResponseSpec callSpec;

	@Mock
	private ChatClient.StreamResponseSpec streamSpec;

	@Mock
	private ChatOptions.Builder chatOptions;

	@Mock
	private ChatResponse chatResponse;

	private AiChatService service;

	@AfterEach
	void shutdownExecutor() {
		if (service != null) {
			service.shutdown();
			ExecutorService executor =
					(ExecutorService) ReflectionTestUtils.getField(service, "aiExecutor");
			assertThat(executor.isShutdown()).isTrue();
		}
	}

	private AiChatService newService(long timeoutMillis) {
		lenient().when(properties.getTimeoutMillis()).thenReturn(timeoutMillis);
		service = new AiChatService(properties);
		return service;
	}

	private void stubCallChain() {
		when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
		when(requestSpec.options(any(ChatOptions.Builder.class))).thenReturn(requestSpec);
		when(requestSpec.call()).thenReturn(callSpec);
	}

	private Prompt prompt() {
		return new Prompt("hello");
	}

	@Test
	void callAiModel_shouldReturnResponse_whenHappy() {
		stubCallChain();
		when(callSpec.chatResponse()).thenReturn(chatResponse);

		assertThat(newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isSameAs(chatResponse);
	}

	@Test
	void callAiModel_shouldThrow504_whenTimedOut() {
		stubCallChain();
		when(callSpec.chatResponse()).thenAnswer(invocation -> {
			TimeUnit.MILLISECONDS.sleep(500);
			return chatResponse;
		});

		assertThatThrownBy(() -> newService(50).callAiModel(prompt(), chatClient, chatOptions))
				.isInstanceOf(CustomApiException.class)
				.hasMessageContaining("timed out")
				.extracting(e -> ((CustomApiException) e).getHttpStatus())
				.isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
	}

	@Test
	void callAiModel_shouldThrow502_whenAddressUnresolvable() {
		stubCallChain();
		when(callSpec.chatResponse()).thenThrow(new UnresolvedAddressException());

		assertThatThrownBy(() -> newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isInstanceOf(CustomApiException.class)
				.extracting(e -> ((CustomApiException) e).getHttpStatus())
				.isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	void callAiModel_shouldThrow504_whenSocketTimeout() {
		stubCallChain();
		when(callSpec.chatResponse()).thenThrow(new ResourceAccessException(
				"read timed out", new SocketTimeoutException()));

		assertThatThrownBy(() -> newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isInstanceOf(CustomApiException.class)
				.extracting(e -> ((CustomApiException) e).getHttpStatus())
				.isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
	}

	@Test
	void callAiModel_shouldThrow502_whenResourceAccessWithoutTimeout() {
		stubCallChain();
		when(callSpec.chatResponse()).thenThrow(
				new ResourceAccessException("connection refused"));

		assertThatThrownBy(() -> newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isInstanceOf(CustomApiException.class)
				.extracting(e -> ((CustomApiException) e).getHttpStatus())
				.isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	void callAiModel_shouldRethrowCustomApiException() {
		stubCallChain();
		CustomApiException original =
				new CustomApiException("rate limited", HttpStatus.TOO_MANY_REQUESTS);
		when(callSpec.chatResponse()).thenThrow(original);

		assertThatThrownBy(() -> newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isSameAs(original);
	}

	@Test
	void callAiModel_shouldWrapUnexpectedRuntimeException() {
		stubCallChain();
		when(callSpec.chatResponse()).thenThrow(new IllegalStateException("boom"));

		assertThatThrownBy(() -> newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Unexpected error during remote call")
				.extracting(Throwable::getCause)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void callAiModel_shouldWrapCheckedException() {
		stubCallChain();
		when(callSpec.chatResponse()).thenAnswer(invocation -> {
			throw new IOException("disk gone");
		});

		assertThatThrownBy(() -> newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Unexpected error during remote call");
	}

	@Test
	void callAiModel_shouldHandleNakedCompletionException() {
		stubCallChain();
		when(callSpec.chatResponse()).thenAnswer(invocation -> {
			throw new CompletionException("naked", null);
		});

		assertThatThrownBy(() -> newService(30000).callAiModel(prompt(), chatClient, chatOptions))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Unexpected error during remote call");
	}

	@Test
	void streamAiModel_shouldEmitChunks_whenHappy() {
		when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
		when(requestSpec.options(any(ChatOptions.Builder.class))).thenReturn(requestSpec);
		when(requestSpec.stream()).thenReturn(streamSpec);
		when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse));

		var chunks = newService(30000).streamAiModel(prompt(), chatClient, chatOptions)
		                              .collectList().block(Duration.ofSeconds(5));

		assertThat(chunks).containsExactly(chatResponse);
	}

	@Test
	void streamAiModel_shouldMapTimeoutTo504() {
		when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
		when(requestSpec.options(any(ChatOptions.Builder.class))).thenReturn(requestSpec);
		when(requestSpec.stream()).thenReturn(streamSpec);
		when(streamSpec.chatResponse()).thenReturn(Flux.never());

		assertThatThrownBy(() -> newService(50).streamAiModel(prompt(), chatClient, chatOptions)
		                                       .blockFirst(Duration.ofSeconds(5)))
				.isInstanceOf(CustomApiException.class)
				.extracting(e -> ((CustomApiException) e).getHttpStatus())
				.isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
	}
}
