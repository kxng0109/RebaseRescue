package io.github.kxng0109.aiprcopilot.service;

import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffRequest;
import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.aiprcopilot.config.AiProvider;
import io.github.kxng0109.aiprcopilot.config.MultiAiConfigurationProperties;
import io.github.kxng0109.aiprcopilot.config.PrCopilotAnalysisProperties;
import io.github.kxng0109.aiprcopilot.config.PrCopilotLoggingProperties;
import io.github.kxng0109.aiprcopilot.error.CustomApiException;
import io.github.kxng0109.aiprcopilot.error.DiffTooLargeException;
import io.github.kxng0109.aiprcopilot.error.ModelOutputParseException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DiffAnalysisServiceTest {

	@Mock
	private PrCopilotAnalysisProperties analysisProperties;

	@Mock
	private MultiAiConfigurationProperties multiAiConfigurationProperties;

	@Mock
	private PrCopilotLoggingProperties loggingProperties;

	@Mock
	private ChatClient primaryChatClient;

	@Mock
	private ChatOptions.Builder primaryChatOptions;

	@Mock
	private PromptBuilderService promptBuilderService;

	@Mock
	private AiChatService aiChatService;

	@Mock
	private DiffResponseMapperService diffResponseMapperService;

	@Mock
	private io.github.resilience4j.bulkhead.BulkheadRegistry bulkheadRegistry;

	@Mock
	private io.github.resilience4j.bulkhead.Bulkhead bulkhead;

	@Mock
	private com.github.benmanes.caffeine.cache.Cache<String, AnalyzeDiffResponse> analysisCache;

	@Mock
	private AnalysisMetrics analysisMetrics;

	@InjectMocks
	private DiffAnalysisService diffAnalysisService;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setup() {
		lenient().when(analysisProperties.getDefaultLanguage()).thenReturn("en");
		lenient().when(analysisProperties.getMaxDiffChars()).thenReturn(50000);
		lenient().when(analysisProperties.isIncludeRawModelOutput()).thenReturn(false);
		lenient().when(analysisProperties.getDefaultStyle()).thenReturn("conventional-commits");

		lenient().when(multiAiConfigurationProperties.getProvider()).thenReturn(AiProvider.OPENAI);
		lenient().when(multiAiConfigurationProperties.isAutoFallback()).thenReturn(false);
		lenient().when(multiAiConfigurationProperties.getTemperature()).thenReturn(0.1);
		lenient().when(multiAiConfigurationProperties.getMaxTokens()).thenReturn(1024);
		lenient().when(promptBuilderService.templateHash()).thenReturn("testhash");
		lenient().when(bulkheadRegistry.bulkhead(anyString())).thenReturn(bulkhead);
		lenient().doAnswer(i -> ((java.util.function.Supplier<?>) i.getArgument(0)).get())
		         .when(bulkhead).executeSupplier(any());

		diffAnalysisService = new DiffAnalysisService(
				analysisProperties,
				primaryChatClient,
				primaryChatOptions,
				loggingProperties,
				multiAiConfigurationProperties,
				promptBuilderService,
				aiChatService,
				diffResponseMapperService,
				bulkheadRegistry,
				analysisCache,
				analysisMetrics,
				null,
				null
		);
	}

	@Test
	public void analyzeDiff_shouldUseDefaults_whenLanguageAndStyleAreNull() {
		String diff = "a diff sha";
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff(diff)
		                                               .requestId("req-1")
		                                               .build();

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(
				eq("en"),
				eq("conventional-commits"),
				eq(diff),
				isNull(),
				eq("req-1")
		)).thenReturn(mockPrompt);

		ChatResponse mockChatResponse = mockChatResponse();
		when(aiChatService.callAiModel(
				mockPrompt,
				primaryChatClient,
				primaryChatOptions
		)).thenReturn(mockChatResponse);

		AnalyzeDiffResponse expectedResponse = AnalyzeDiffResponse.builder()
		                                                          .title("test title")
		                                                          .summary("test summary")
		                                                          .requestId("req-1")
		                                                          .build();

		when(diffResponseMapperService.mapToAnalyzeDiffResponse(
				eq(mockChatResponse),
				anyLong(),
				eq(diff),
				eq("req-1"),
				eq("openai")
		)).thenReturn(expectedResponse);

		AnalyzeDiffResponse response = diffAnalysisService.analyzeDiff(request);

		assertNotNull(response);
		assertEquals(expectedResponse.title(), response.title());
		assertEquals(expectedResponse.summary(), response.summary());
		assertEquals(expectedResponse.requestId(), response.requestId());

		verify(promptBuilderService).buildDiffAnalysisPrompt(
				"en",
				"conventional-commits",
				diff,
				null,
				"req-1"
		);
		verify(aiChatService).callAiModel(mockPrompt, primaryChatClient, primaryChatOptions);
		verify(diffResponseMapperService).mapToAnalyzeDiffResponse(
				eq(mockChatResponse),
				anyLong(),
				eq(diff),
				eq("req-1"),
				eq("openai")
		);
	}

	@Test
	void analyzeDiff_shouldUseProvidedLanguageAndStyle_whenSpecified() {
		String diff = "diff content";
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff(diff)
		                                               .language("fr")
		                                               .style("gitlab")
		                                               .maxSummaryLength(200)
		                                               .requestId("req-2")
		                                               .build();

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(
				eq("fr"),
				eq("gitlab"),
				eq(diff),
				eq(200),
				eq("req-2")
		)).thenReturn(mockPrompt);

		ChatResponse mockChatResponse = mockChatResponse();
		when(aiChatService.callAiModel(mockPrompt, primaryChatClient, primaryChatOptions))
				.thenReturn(mockChatResponse);

		AnalyzeDiffResponse expectedResponse = AnalyzeDiffResponse.builder()
		                                                          .title("titre de test")
		                                                          .build();

		when(diffResponseMapperService.mapToAnalyzeDiffResponse(
				any(), anyLong(), any(), any(), any()
		)).thenReturn(expectedResponse);

		AnalyzeDiffResponse response = diffAnalysisService.analyzeDiff(request);

		assertNotNull(response);
		verify(promptBuilderService).buildDiffAnalysisPrompt("fr", "gitlab", diff, 200, "req-2");
	}


	@Test
	public void analyzeDiff_shouldThrowDoffTooLargeException_whenDiffExceedsMaxChars() {
		String largeDiff = "x".repeat(analysisProperties.getMaxDiffChars() + 1);
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff(largeDiff)
		                                               .requestId("req-1")
		                                               .build();

		assertThrows(DiffTooLargeException.class, () -> diffAnalysisService.analyzeDiff(request));

		verify(promptBuilderService, never()).buildDiffAnalysisPrompt(any(), any(), any(), any(), any());
		verify(aiChatService, never()).callAiModel(any(), any(), any());
		verify(diffResponseMapperService, never()).mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any());
	}

	@Test
	void analyzeDiff_shouldRethrowModelOutputParseException_whenParsingFails() {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);

		when(aiChatService.callAiModel(any(), any(), any()))
				.thenReturn(mockChatResponse());

		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenThrow(new ModelOutputParseException("Invalid JSON"));

		ModelOutputParseException exception = assertThrows(
				ModelOutputParseException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		);

		assertEquals("Invalid JSON", exception.getMessage());
	}

	@Test
	void analyzeDiff_shouldUseFallback_whenPrimaryFailsAndAutoFallbackEnabled() {
		when(multiAiConfigurationProperties.isAutoFallback()).thenReturn(true);
		when(multiAiConfigurationProperties.getFallbackProvider()).thenReturn(AiProvider.ANTHROPIC);

		ChatClient fallbackChatClient = mock(ChatClient.class);
		ChatOptions.Builder fallbackChatOptions = mock(ChatOptions.Builder.class);

		diffAnalysisService = new DiffAnalysisService(
				analysisProperties,
				primaryChatClient,
				primaryChatOptions,
				loggingProperties,
				multiAiConfigurationProperties,
				promptBuilderService,
				aiChatService,
				diffResponseMapperService,
				bulkheadRegistry,
				analysisCache,
				analysisMetrics,
				fallbackChatClient,
				fallbackChatOptions
		);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);

		when(aiChatService.callAiModel(mockPrompt, primaryChatClient, primaryChatOptions))
				.thenThrow(new RuntimeException("Primary failed"));

		ChatResponse fallbackResponse = mockChatResponse();
		when(aiChatService.callAiModel(mockPrompt, fallbackChatClient, fallbackChatOptions))
				.thenReturn(fallbackResponse);

		AnalyzeDiffResponse expectedResponse = AnalyzeDiffResponse.builder()
		                                                          .title("fallback response")
		                                                          .build();

		when(diffResponseMapperService.mapToAnalyzeDiffResponse(
				eq(fallbackResponse), anyLong(), any(), any(), eq("anthropic")
		)).thenReturn(expectedResponse);

		AnalyzeDiffResponse response = diffAnalysisService.analyzeDiff(request);

		assertNotNull(response);
		assertEquals("fallback response", response.title());

		verify(aiChatService).callAiModel(mockPrompt, primaryChatClient, primaryChatOptions);
		verify(aiChatService).callAiModel(mockPrompt, fallbackChatClient, fallbackChatOptions);
	}

	@Test
	void analyzeDiff_shouldThrowException_whenBothPrimaryAndFallbackFail() {
		when(multiAiConfigurationProperties.isAutoFallback()).thenReturn(true);
		when(multiAiConfigurationProperties.getFallbackProvider()).thenReturn(AiProvider.ANTHROPIC);

		ChatClient fallbackChatClient = mock(ChatClient.class);
		ChatOptions.Builder fallbackChatOptions = mock(ChatOptions.Builder.class);

		diffAnalysisService = new DiffAnalysisService(
				analysisProperties,
				primaryChatClient,
				primaryChatOptions,
				loggingProperties,
				multiAiConfigurationProperties,
				promptBuilderService,
				aiChatService,
				diffResponseMapperService,
				bulkheadRegistry,
				analysisCache,
				analysisMetrics,
				fallbackChatClient,
				fallbackChatOptions
		);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);

		when(aiChatService.callAiModel(mockPrompt, primaryChatClient, primaryChatOptions))
				.thenThrow(new RuntimeException("Primary failed"));

		when(aiChatService.callAiModel(mockPrompt, fallbackChatClient, fallbackChatOptions))
				.thenThrow(new RuntimeException("Fallback also failed"));

		RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		);

		assertThat(exception.getMessage()).contains("Primary failed");
		assertThat(exception.getMessage()).contains("Fallback also failed");
	}

	@Test
	void analyzeDiff_shouldLogPrompt_whenLoggingEnabled() {
		when(loggingProperties.isLogPrompts()).thenReturn(true);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		Prompt mockPrompt = mock(Prompt.class);
		when(mockPrompt.toString()).thenReturn("Mock Prompt Content");
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);

		when(aiChatService.callAiModel(any(), any(), any()))
				.thenReturn(mockChatResponse());

		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(AnalyzeDiffResponse.builder().title("test").build());

		diffAnalysisService.analyzeDiff(request);

		verify(loggingProperties).isLogPrompts();
	}

	@Test
	void analyzeDiff_shouldReturnCachedResponse_whenDiffHashSeenBefore() {
		when(analysisProperties.getCacheMaxSize()).thenReturn(1000);
		AnalyzeDiffResponse cached = AnalyzeDiffResponse.builder()
		                                                .title("cached title")
		                                                .requestId("old-req")
		                                                .build();
		when(analysisCache.getIfPresent(anyString())).thenReturn(cached);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		AnalyzeDiffResponse response = diffAnalysisService.analyzeDiff(request);

		assertNotNull(response);
		assertEquals("cached title", response.title());
		assertEquals("req-1", response.requestId());
		verify(aiChatService, never()).callAiModel(any(), any(), any());
		verify(analysisMetrics).countCacheHit("openai");
	}

	@Test
	void analyzeDiff_shouldThrow429_whenProviderBulkheadFull() {
		doThrow(io.github.resilience4j.bulkhead.BulkheadFullException.createBulkheadFullException(
				io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test")))
				.when(bulkhead).executeSupplier(any());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		CustomApiException exception = assertThrows(
				CustomApiException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		);

		assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, exception.getHttpStatus());
	}

	@Test
	void analyzeDiff_shouldRethrowBlockedDiff_withoutFallback() {
		when(aiChatService.callAiModel(any(), any(), any()))
				.thenThrow(new io.github.kxng0109.aiprcopilot.error.BlockedDiffException("Blocked: test"));

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		assertThrows(
				io.github.kxng0109.aiprcopilot.error.BlockedDiffException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		);
		verify(aiChatService, times(1)).callAiModel(any(), any(), any());
	}

	private DiffAnalysisService serviceWithFallback(ChatClient fallbackClient,
	                                                ChatOptions.Builder fallbackOptions) {
		return new DiffAnalysisService(
				analysisProperties,
				primaryChatClient,
				primaryChatOptions,
				loggingProperties,
				multiAiConfigurationProperties,
				promptBuilderService,
				aiChatService,
				diffResponseMapperService,
				bulkheadRegistry,
				analysisCache,
				analysisMetrics,
				fallbackClient,
				fallbackOptions
		);
	}

	@Test
	void analyzeDiff_shouldSkipCache_whenDisabled() {
		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);
		when(aiChatService.callAiModel(any(), any(), any()))
				.thenReturn(mockChatResponse());
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(AnalyzeDiffResponse.builder().title("t").build());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		assertNotNull(diffAnalysisService.analyzeDiff(request));
		verify(analysisCache, never()).getIfPresent(anyString());
		verify(analysisCache, never()).put(anyString(), any());
	}

	@Test
	void analyzeDiff_shouldRethrowSameCustomApi_whenNoFallback() {
		CustomApiException failure =
				new CustomApiException("p-down", org.springframework.http.HttpStatus.BAD_GATEWAY);
		when(aiChatService.callAiModel(any(), any(), any())).thenThrow(failure);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		assertThat(assertThrows(
				CustomApiException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		)).isSameAs(failure);
	}

	@Test
	void analyzeDiff_shouldWrapGeneric_whenNoFallback() {
		when(aiChatService.callAiModel(any(), any(), any()))
				.thenThrow(new IllegalStateException("weird"));

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		);
		assertThat(exception.getMessage())
				.contains("Could not process diff analysis due to internal error");
	}

	@Test
	void analyzeDiff_shouldCombineMessages_whenFallbackCustomApi() {
		when(multiAiConfigurationProperties.isAutoFallback()).thenReturn(true);
		when(multiAiConfigurationProperties.getFallbackProvider()).thenReturn(AiProvider.ANTHROPIC);
		ChatClient fallbackClient = mock(ChatClient.class);
		ChatOptions.Builder fallbackOptions = mock(ChatOptions.Builder.class);
		diffAnalysisService = serviceWithFallback(fallbackClient, fallbackOptions);

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);
		when(aiChatService.callAiModel(mockPrompt, primaryChatClient, primaryChatOptions))
				.thenThrow(new CustomApiException(
						"p-down",
						org.springframework.http.HttpStatus.BAD_GATEWAY
				));
		when(aiChatService.callAiModel(mockPrompt, fallbackClient, fallbackOptions))
				.thenThrow(new CustomApiException(
						"f-down",
						org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
				));

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		CustomApiException exception = assertThrows(
				CustomApiException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		);
		assertThat(exception.getMessage()).contains("Primary: p-down. Fallback: f-down");
		assertThat(exception.getHttpStatus())
				.isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void analyzeDiff_shouldThrowRuntime_whenFallbackGeneric() {
		when(multiAiConfigurationProperties.isAutoFallback()).thenReturn(true);
		when(multiAiConfigurationProperties.getFallbackProvider()).thenReturn(AiProvider.ANTHROPIC);
		ChatClient fallbackClient = mock(ChatClient.class);
		ChatOptions.Builder fallbackOptions = mock(ChatOptions.Builder.class);
		diffAnalysisService = serviceWithFallback(fallbackClient, fallbackOptions);

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);
		when(aiChatService.callAiModel(mockPrompt, primaryChatClient, primaryChatOptions))
				.thenThrow(new CustomApiException(
						"p-down",
						org.springframework.http.HttpStatus.BAD_GATEWAY
				));
		when(aiChatService.callAiModel(mockPrompt, fallbackClient, fallbackOptions))
				.thenThrow(new IllegalStateException("f-broken"));

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		);
		assertThat(exception.getMessage()).contains("across the two providers");
	}

	@Test
	void analyzeDiff_shouldUseDefaults_whenLanguageAndStyleAreBlank() {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("a diff sha")
		                                               .language("  ")
		                                               .style("")
		                                               .requestId("req-1")
		                                               .build();

		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(
				eq("en"),
				eq("conventional-commits"),
				eq("a diff sha"),
				isNull(),
				eq("req-1")
		)).thenReturn(mockPrompt);
		when(aiChatService.callAiModel(any(), any(), any()))
				.thenReturn(mockChatResponse());
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(AnalyzeDiffResponse.builder().title("t").build());

		assertNotNull(diffAnalysisService.analyzeDiff(request));
		verify(promptBuilderService).buildDiffAnalysisPrompt(
				eq("en"), eq("conventional-commits"), eq("a diff sha"), isNull(), eq("req-1"));
	}

	@Test
	void streamAnalyze_shouldEmitErrorEvent_whenDiffBlank() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("  ")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			assertThat(emitters.constructed()).hasSize(1);
			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).send((SseEmitter.SseEventBuilder) any());
			verify(emitter, timeout(5000).times(1)).complete();
			verify(aiChatService, never()).streamAiModel(any(), any(), any());
		}
	}

	@Test
	void streamAnalyze_shouldEmitErrorEvent_whenDiffTooLarge() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("x".repeat(60000))
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).send((SseEmitter.SseEventBuilder) any());
			verify(emitter, timeout(5000).times(1)).complete();
			verify(aiChatService, never()).streamAiModel(any(), any(), any());
		}
	}

	@Test
	void streamAnalyze_shouldEmitCachedResult_whenHit() throws Exception {
		when(analysisProperties.getCacheMaxSize()).thenReturn(1000);
		AnalyzeDiffResponse cached = AnalyzeDiffResponse.builder()
		                                                .title("cached")
		                                                .requestId("old")
		                                                .build();
		when(analysisCache.getIfPresent(anyString())).thenReturn(cached);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(3)).send((SseEmitter.SseEventBuilder) any());
			verify(emitter, timeout(5000).times(1)).complete();
			verify(aiChatService, never()).streamAiModel(any(), any(), any());
		}
	}

	@Test
	void streamAnalyze_shouldStreamLiveResult_whenHappy() throws Exception {
		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);
		ChatResponse nullResultChunk = mock(ChatResponse.class);
		when(aiChatService.streamAiModel(eq(mockPrompt), eq(primaryChatClient), eq(primaryChatOptions)))
				.thenReturn(Flux.just(
						new ChatResponse(List.of(new Generation(new AssistantMessage("tok1")))),
						nullResultChunk,
						new ChatResponse(List.of(new Generation(new AssistantMessage(""))))
				));
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(AnalyzeDiffResponse.builder().title("live").build());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(4)).send((SseEmitter.SseEventBuilder) any());
			verify(emitter, timeout(5000).times(1)).complete();
			verify(emitter, timeout(500).times(0)).completeWithError(any());
		}
	}

	@Test
	void streamAnalyze_shouldEmitError_whenBulkheadFull() throws Exception {
		doThrow(BulkheadFullException.createBulkheadFullException(
				io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test")))
				.when(bulkhead).executeSupplier(any());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(2)).send((SseEmitter.SseEventBuilder) any());
			verify(emitter, timeout(5000).times(1)).complete();
		}
	}

	@Test
	void streamAnalyze_shouldCompleteWithError_whenNoChunks() {
		when(aiChatService.streamAiModel(any(), any(), any())).thenReturn(Flux.empty());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).completeWithError(
					argThat(e -> e instanceof ModelOutputParseException));
		}
	}

	@Test
	void streamAnalyze_shouldCompleteWithError_whenAiFails() {
		RuntimeException failure = new RuntimeException("ai down");
		when(aiChatService.streamAiModel(any(), any(), any())).thenThrow(failure);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).completeWithError(failure);
		}
	}

	private ChatResponse mockChatResponse() {
		Generation generation = new Generation(
				new AssistantMessage("Some details or message")
		);

		Usage usage = new DefaultUsage(0, 0, 120);

		ChatResponseMetadata chatResponseMetadata = ChatResponseMetadata.builder()
		                                                                .model("gpt-4o")
		                                                                .usage(usage)
		                                                                .build();

		return ChatResponse.builder()
		                   .generations(List.of(generation))
		                   .metadata(chatResponseMetadata)
		                   .build();
	}

	@Test
	void analyzeDiff_shouldPutCache_whenEnabledAndMiss() {
		when(analysisProperties.getCacheMaxSize()).thenReturn(1000);
		when(analysisProperties.getCacheMaxEntryChars()).thenReturn(100000L);
		when(analysisCache.getIfPresent(anyString())).thenReturn(null);
		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);
		when(aiChatService.callAiModel(any(), any(), any()))
				.thenReturn(mockChatResponse());
		AnalyzeDiffResponse mapped = AnalyzeDiffResponse.builder().title("t").build();
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(mapped);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		assertThat(diffAnalysisService.analyzeDiff(request)).isSameAs(mapped);
		verify(analysisCache).put(anyString(), eq(mapped));
		verify(analysisMetrics).countCacheMiss("openai");
	}

	@Test
	void analyzeDiff_shouldSkipCacheStore_whenEntryExceedsBudget() {
		when(analysisProperties.getCacheMaxSize()).thenReturn(1000);
		when(analysisProperties.getCacheMaxEntryChars()).thenReturn(1024L);
		when(analysisCache.getIfPresent(anyString())).thenReturn(null);
		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);
		when(aiChatService.callAiModel(any(), any(), any()))
				.thenReturn(mockChatResponse());
		AnalyzeDiffResponse mapped = AnalyzeDiffResponse.builder()
				.title("t")
				.rawModelOutput("x".repeat(2048))
				.build();
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(mapped);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		assertThat(diffAnalysisService.analyzeDiff(request)).isSameAs(mapped);
		verify(analysisCache, never()).put(anyString(), any());
		verify(analysisMetrics).countCacheMiss("openai");
	}

	@Test
	void analyzeDiff_shouldUseOllamaBulkhead_whenProviderIsOllama() {
		when(multiAiConfigurationProperties.getProvider()).thenReturn(AiProvider.OLLAMA);
		Prompt mockPrompt = mock(Prompt.class);
		when(promptBuilderService.buildDiffAnalysisPrompt(any(), any(), any(), any(), any()))
				.thenReturn(mockPrompt);
		when(aiChatService.callAiModel(any(), any(), any()))
				.thenReturn(mockChatResponse());
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(AnalyzeDiffResponse.builder().title("t").build());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		diffAnalysisService.analyzeDiff(request);

		verify(bulkheadRegistry).bulkhead("ai-ollama");
	}

	@Test
	void analyzeDiff_shouldRethrowSame_whenFallbackEnabledButNoClient() {
		when(multiAiConfigurationProperties.isAutoFallback()).thenReturn(true);
		CustomApiException failure =
				new CustomApiException("p-down", org.springframework.http.HttpStatus.BAD_GATEWAY);
		when(aiChatService.callAiModel(any(), any(), any())).thenThrow(failure);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		assertThat(assertThrows(
				CustomApiException.class,
				() -> diffAnalysisService.analyzeDiff(request)
		)).isSameAs(failure);
	}

	@Test
	void streamAnalyze_shouldUseOllamaBulkhead_whenProviderIsOllama() throws Exception {
		when(multiAiConfigurationProperties.getProvider()).thenReturn(AiProvider.OLLAMA);
		when(aiChatService.streamAiModel(any(), any(), any()))
				.thenReturn(Flux.just(new ChatResponse(
						List.of(new Generation(new AssistantMessage("tok"))))));
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(AnalyzeDiffResponse.builder().title("t").build());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).complete();
		}
		verify(bulkheadRegistry).bulkhead("ai-ollama");
	}

	@Test
	@SuppressWarnings("unchecked")
	void streamAnalyze_shouldCompleteWithError_whenChunksNull() {
		Flux<ChatResponse> flux = mock(Flux.class);
		when(flux.collectList()).thenReturn(mock(Mono.class));
		when(aiChatService.streamAiModel(any(), any(), any())).thenReturn(flux);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).completeWithError(
					argThat(e -> e instanceof ModelOutputParseException));
		}
	}

	@Test
	void streamAnalyze_shouldSkipNullOutputChunks() throws Exception {
		Generation nullOutput = mock(Generation.class);
		when(aiChatService.streamAiModel(any(), any(), any())).thenReturn(Flux.just(
				new ChatResponse(List.of(new Generation(new AssistantMessage("tok")))),
				new ChatResponse(List.of(nullOutput))
		));
		when(diffResponseMapperService.mapToAnalyzeDiffResponse(any(), anyLong(), any(), any(), any()))
				.thenReturn(AnalyzeDiffResponse.builder().title("t").build());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(4)).send((SseEmitter.SseEventBuilder) any());
			verify(emitter, timeout(5000).times(1)).complete();
		}
	}

	@Test
	void streamAnalyze_shouldCompleteWithError_whenExceptionHasNoMessage() {
		when(aiChatService.streamAiModel(any(), any(), any()))
				.thenThrow(new RuntimeException());

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("diff")
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).completeWithError(any(Throwable.class));
		}
	}

	@Test
	void streamAnalyze_shouldEmitErrorEvent_whenDiffNull() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .requestId("req-1")
		                                               .build();

		try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
			diffAnalysisService.streamAnalyze(request);

			SseEmitter emitter = emitters.constructed().getFirst();
			verify(emitter, timeout(5000).times(1)).send((SseEmitter.SseEventBuilder) any());
			verify(emitter, timeout(5000).times(1)).complete();
			verify(aiChatService, never()).streamAiModel(any(), any(), any());
		}
	}
}
