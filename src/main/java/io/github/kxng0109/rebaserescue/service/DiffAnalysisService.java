package io.github.kxng0109.rebaserescue.service;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffRequest;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.AiProvider;
import io.github.kxng0109.rebaserescue.config.MultiAiConfigurationProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotAnalysisProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotLoggingProperties;
import io.github.kxng0109.rebaserescue.error.BlockedDiffException;
import io.github.kxng0109.rebaserescue.error.CustomApiException;
import io.github.kxng0109.rebaserescue.error.DiffTooLargeException;
import io.github.kxng0109.rebaserescue.error.ModelOutputParseException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiffAnalysisService {

	private final PrCopilotAnalysisProperties analysisProperties;
	private final ChatClient primaryChatClient;
	private final ChatOptions.Builder primaryChatOptions;
	private final PrCopilotLoggingProperties loggingProperties;
	private final MultiAiConfigurationProperties multiAiConfigurationProperties;
	private final PromptBuilderService promptBuilderService;
	private final AiChatService aiChatService;
	private final DiffResponseMapperService diffResponseMapperService;
	private final BulkheadRegistry bulkheadRegistry;
	private final Cache<String, AnalyzeDiffResponse> analysisCache;
	private final AnalysisMetrics analysisMetrics;

	@Qualifier("fallbackChatClient")
	@Nullable
	private final ChatClient fallbackChatClient;

	@Qualifier("fallbackChatOptions")
	@Nullable
	private final ChatOptions.Builder fallbackChatOptions;

	/**
	 * Analyzes a code diff and generates a structured response with analysis details.
	 *
	 * @param request the {@code AnalyzeDiffRequest} containing the diff content and associated parameters, must not be
	 *                {@code null}
	 * @return the {@code AnalyzeDiffResponse} containing the analysis result, never {@code null}
	 * @throws DiffTooLargeException     if the diff exceeds the maximum allowed size
	 * @throws IllegalArgumentException  if the request is {@code null} or contains invalid parameters
	 * @throws ModelOutputParseException if the AI model output could not be parsed
	 * @throws RuntimeException          if an internal error occurs and both primary and fallback providers fail
	 */
	public AnalyzeDiffResponse analyzeDiff(AnalyzeDiffRequest request) {
		String diff = request.diff();
		if (log.isDebugEnabled()) {
			log.debug("Diff received: {} chars (requestId={})", diff.length(), request.requestId());
		}
		int maxDiffChars = analysisProperties.getMaxDiffChars();
		log.debug("Max diff chars set to: {}", maxDiffChars);
		if (diff.length() > maxDiffChars) {
			throw new DiffTooLargeException(
					String.format(
							"Diff exceeded maximum allowed size of %d characters",
							maxDiffChars
					)
			);
		}

		String language = useDefaultIfBlank(request.language(), analysisProperties.getDefaultLanguage());
		String style = useDefaultIfBlank(request.style(), analysisProperties.getDefaultStyle());
		Integer maxSummaryLength = request.maxSummaryLength();

		AiProvider provider = multiAiConfigurationProperties.getProvider();
		String cacheKey = cacheKey(diff, language, style, maxSummaryLength, provider);
		if (analysisProperties.getCacheMaxSize() > 0) {
			AnalyzeDiffResponse cached = analysisCache.getIfPresent(cacheKey);
			if (cached != null) {
				analysisMetrics.countCacheHit(provider.getValue());
				log.debug("Diff-hash cache hit for requestId {}", request.requestId());
				return cached.toBuilder().requestId(request.requestId()).build();
			}
			analysisMetrics.countCacheMiss(provider.getValue());
		}

		Prompt prompt = promptBuilderService.buildDiffAnalysisPrompt(
				language,
				style,
				diff,
				maxSummaryLength,
				request.requestId()
		);

		if (loggingProperties.isLogPrompts() && log.isInfoEnabled()) {
			log.info("Prompt built for requestId {} ({} chars)", request.requestId(), prompt.toString().length());
		}

		try {
			log.debug("Attempting to use primary provider: {}", multiAiConfigurationProperties.getProvider());

			AnalyzeDiffResponse response = guardedCall(
					provider,
					() -> callAiAndBuildResponse(
							request,
							diff,
							prompt,
							primaryChatClient,
							primaryChatOptions,
							multiAiConfigurationProperties.getProvider().getValue()
					),
					request.requestId()
			);
			putCache(cacheKey, response);
			return response;
		} catch (ModelOutputParseException e) {
			log.warn("Model output could not be parsed for requestId '{}': {}", request.requestId(), e.getMessage());
			throw e;
		} catch (BlockedDiffException e) {
			log.warn("Diff blocked by guardrail for requestId '{}': {}", request.requestId(), e.getMessage());
			throw e;
		} catch (Exception primaryException) {
			if (primaryException instanceof CustomApiException) {
				log.error(
						"An error occurred while using primary provider '{}' for requestId {}: {}",
						multiAiConfigurationProperties.getProvider().getValue(),
						request.requestId(),
						primaryException.getMessage(),
						primaryException
				);
			} else {
				log.error(
						"Unexpected error in diff analysis for requestId '{}' while using primary provider: {}. {}",
						request.requestId(),
						multiAiConfigurationProperties.getProvider().getValue(),
						primaryException.getMessage(),
						primaryException
				);
			}

			if (multiAiConfigurationProperties.isAutoFallback() && fallbackChatClient != null) {
				try {
					log.debug(
							"Attempting to use fallback chat client: {}",
							multiAiConfigurationProperties.getFallbackProvider()
					);

					AnalyzeDiffResponse fallbackResponse = guardedCall(
							multiAiConfigurationProperties.getFallbackProvider(),
							() -> callAiAndBuildResponse(
									request,
									diff,
									prompt,
									fallbackChatClient,
									fallbackChatOptions,
									multiAiConfigurationProperties.getFallbackProvider().getValue()
							),
							request.requestId()
					);
					putCache(cacheKey, fallbackResponse);
					return fallbackResponse;
				} catch (Exception fallBackException) {
					if (fallBackException instanceof CustomApiException) {
						log.error(
								"An error occurred while using primary provider '{}' for requestId {}: {}",
								multiAiConfigurationProperties.getFallbackProvider().getValue(),
								request.requestId(),
								fallBackException.getMessage(),
								fallBackException
						);

						throw new CustomApiException(
								String.format(
										"An error occurred. Primary: %s. Fallback: %s",
										primaryException.getMessage(),
										fallBackException.getMessage()
								),
								((CustomApiException) fallBackException).getHttpStatus(),
								fallBackException
						);
					} else {
						log.error(
								"Unexpected error in diff analysis for requestId '{}' while using fallback provider: {}",
								request.requestId(),
								multiAiConfigurationProperties.getFallbackProvider().getValue(),
								fallBackException
						);

						throw new RuntimeException(
								String.format(
										"Could not process diff analysis due to internal error across the two providers. %s. %s",
										primaryException.getMessage(),
										fallBackException.getMessage()
								), fallBackException
						);
					}
				}
			}

			log.debug("No fallback available. Auto-fallback is disabled or no fallback client is configured.");

			if (primaryException instanceof CustomApiException) {
				throw primaryException;
			}
			throw new RuntimeException(
					String.format(
							"Could not process diff analysis due to internal error. %s",
							primaryException.getMessage()
					), primaryException
			);
		}
	}

	/**
	 * Streams an analysis over SSE: emits {@code started}, per-chunk {@code token}, final {@code result}, then
	 * {@code done} events. Reuses the diff-hash cache — cache hits emit the stored result immediately without an AI
	 * call.
	 *
	 * @param request the request containing the diff, must not be {@code null}
	 * @return a started {@code SseEmitter}, never {@code null}
	 */
	public SseEmitter streamAnalyze(
			AnalyzeDiffRequest request) {
		long emitterTimeout = multiAiConfigurationProperties.getTimeoutMillis() + 30_000L;
		SseEmitter emitter =
				new SseEmitter(emitterTimeout);

		Thread.ofVirtual().start(() -> {
			try {
				String diff = request.diff();
				if (diff == null || diff.isBlank()) {
					emitter.send(event("error", "Diff must not be blank"));
					emitter.complete();
					return;
				}
				if (diff.length() > analysisProperties.getMaxDiffChars()) {
					emitter.send(event("error", "Diff exceeded maximum allowed size"));
					emitter.complete();
					return;
				}
				String language = useDefaultIfBlank(request.language(), analysisProperties.getDefaultLanguage());
				String style = useDefaultIfBlank(request.style(), analysisProperties.getDefaultStyle());
				AiProvider provider = multiAiConfigurationProperties.getProvider();
				String cacheKey = cacheKey(diff, language, style, request.maxSummaryLength(), provider);

				if (analysisProperties.getCacheMaxSize() > 0) {
					AnalyzeDiffResponse cached = analysisCache.getIfPresent(cacheKey);
					if (cached != null) {
						analysisMetrics.countCacheHit(provider.getValue());
						emitter.send(event("started", request.requestId()));
						emitter.send(event("result", cached.toBuilder().requestId(request.requestId()).build()));
						emitter.send(event("done", "cached"));
						emitter.complete();
						return;
					}
					analysisMetrics.countCacheMiss(provider.getValue());
				}

				Prompt prompt = promptBuilderService.buildDiffAnalysisPrompt(
						language, style, diff, request.maxSummaryLength(), request.requestId());
				emitter.send(event("started", request.requestId()));

				Bulkhead bulkhead = bulkheadRegistry.bulkhead(
						provider == AiProvider.OLLAMA ? "ai-ollama" : "ai-saas");
				Timer.Sample sample = analysisMetrics.startSample();
				long start = System.nanoTime();
				StringBuilder fullText;
				ChatResponse last;
				try {
					List<ChatResponse> chunks = bulkhead.executeSupplier(
							() -> aiChatService.streamAiModel(prompt, primaryChatClient, primaryChatOptions)
							                   .collectList()
							                   .block());
					if (chunks == null || chunks.isEmpty()) {
						throw new ModelOutputParseException(
								"AI model returned no output chunks.");
					}
					fullText = new StringBuilder();
					for (ChatResponse chunk : chunks) {
						String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
								? chunk.getResult().getOutput().getText()
								: null;
						if (text != null && !text.isEmpty()) {
							fullText.append(text);
							emitter.send(event("token", text));
						}
					}
					last = chunks.get(chunks.size() - 1);
					analysisMetrics.stopSample(sample, provider.getValue(), "success");
				} catch (BulkheadFullException e) {
					analysisMetrics.stopSample(sample, provider.getValue(), "bulkhead-full");
					emitter.send(event("error", "Provider overloaded, please retry shortly"));
					emitter.complete();
					return;
				} catch (RuntimeException e) {
					analysisMetrics.stopSample(sample, provider.getValue(), "error");
					throw e;
				}
				long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

				ChatResponse assembled =
						ChatResponse.builder()
						            .generations(java.util.List.of(new Generation(
								            new AssistantMessage(fullText.toString()))))
						            .metadata(last.getMetadata())
						            .build();
				AnalyzeDiffResponse response = diffResponseMapperService.mapToAnalyzeDiffResponse(
						assembled, latencyMs, diff, request.requestId(), provider.getValue());
				putCache(cacheKey, response);
				emitter.send(event("result", response));
				emitter.send(event("done", "live"));
				emitter.complete();
			} catch (Exception e) {
				try {
					log.warn(
							"Streaming analysis failed for requestId '{}': {}",
							request.requestId(), e.getMessage()
					);
					emitter.send(event("error", streamSafeMessage(e)));
				} catch (Exception sendFailure) {
					log.debug("Could not send SSE error event", sendFailure);
				}
				emitter.completeWithError(e);
			}
		});

		return emitter;
	}

	private SseEmitter.SseEventBuilder event(
			String name, Object data) {
		return SseEmitter.event().name(name).data(data);
	}

	/**
	 * Sanitizes stream failure text the same way as HTTP error bodies: 4xx
	 * app messages pass through, 5xx collapse to the reason phrase, and
	 * anything else becomes a generic notice. Details stay in the log.
	 */
	static String streamSafeMessage(Exception e) {
		if (e instanceof CustomApiException custom) {
			HttpStatus status = custom.getHttpStatus();
			if (status != null && status.is4xxClientError()
					&& custom.getMessage() != null && !custom.getMessage().isBlank()) {
				return custom.getMessage();
			}
			if (status != null) {
				return status.getReasonPhrase();
			}
		}
		return "Streaming failed";
	}

	/**
	 * Invokes an AI model to analyze a code diff and constructs a response containing the analysis results.
	 *
	 * @param request     the request containing metadata and context for the analysis, must not be {@code null}
	 * @param diff        the code diff to be analyzed, must not be {@code null} or empty
	 * @param prompt      the AI model prompt used for guiding the analysis, must not be {@code null} or blank
	 * @param chatClient  the fallback chat client to use for the AI call, must not be {@code null}
	 * @param chatOptions the options to configure the fallback chat client, must not be {@code null}
	 * @return the response containing the AI analysis results, never {@code null}
	 * @throws IllegalArgumentException if any required parameter is {@code null} or invalid
	 */
	private AnalyzeDiffResponse callAiAndBuildResponse(
			AnalyzeDiffRequest request,
			String diff,
			Prompt prompt,
			ChatClient chatClient,
			ChatOptions.Builder chatOptions,
			String providerName
	) {
		long start = System.nanoTime();
		ChatResponse aiResponse = aiChatService.callAiModel(
				prompt,
				chatClient,
				chatOptions
		);
		long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		return diffResponseMapperService.mapToAnalyzeDiffResponse(
				aiResponse,
				latencyMs,
				diff,
				request.requestId(),
				providerName
		);
	}

	/**
	 * Runs an AI call behind the provider bulkhead with per-provider latency telemetry.
	 *
	 * @param provider  the provider being called
	 * @param call      the AI call supplier
	 * @param requestId the request identifier for logging
	 * @return the analysis response
	 * @throws CustomApiException with 429 when the provider bulkhead is full
	 */
	private AnalyzeDiffResponse guardedCall(
			AiProvider provider, Supplier<AnalyzeDiffResponse> call, String requestId) {
		Bulkhead bulkhead = bulkheadRegistry.bulkhead(
				provider == AiProvider.OLLAMA ? "ai-ollama" : "ai-saas");
		io.micrometer.core.instrument.Timer.Sample sample = analysisMetrics.startSample();
		try {
			AnalyzeDiffResponse response = bulkhead.executeSupplier(call);
			analysisMetrics.stopSample(sample, provider.getValue(), "success");
			return response;
		} catch (BulkheadFullException e) {
			analysisMetrics.stopSample(sample, provider.getValue(), "bulkhead-full");
			log.warn("Provider '{}' bulkhead full for requestId '{}'", provider, requestId);
			throw new CustomApiException(
					"Provider overloaded, please retry shortly",
					HttpStatus.TOO_MANY_REQUESTS, e
			);
		} catch (RuntimeException e) {
			analysisMetrics.stopSample(sample, provider.getValue(), "error");
			throw e;
		}
	}

	private void putCache(String cacheKey, AnalyzeDiffResponse response) {
		if (analysisProperties.getCacheMaxSize() > 0 && response != null) {
			long estimate = estimateResponseChars(response);
			if (estimate > analysisProperties.getCacheMaxEntryChars()) {
				log.debug("Skipping cache store for oversized response (est. {} chars)", estimate);
				return;
			}
			analysisCache.put(cacheKey, response);
		}
	}

	/**
	 * Estimates response size in characters for cache admission. Sums all
	 * text fields and list entries; used only to bound worst-case heap, so
	 * a cheap over-approximation is fine. Skipped puts stay correct: the
	 * next identical request simply misses.
	 *
	 * @param response the response to measure, must not be {@code null}
	 * @return estimated character count, never negative
	 */
	static long estimateResponseChars(AnalyzeDiffResponse response) {
		long total = lengthOf(response.title())
				+ lengthOf(response.summary())
				+ lengthOf(response.details())
				+ lengthOf(response.analysisNotes())
				+ lengthOf(response.requestId())
				+ lengthOf(response.rawModelOutput());
		if (response.risks() != null) {
			for (RiskItem risk : response.risks()) {
				if (risk != null) {
					total += lengthOf(risk.level()) + lengthOf(risk.message());
				}
			}
		}
		total += lengthsOf(response.suggestedTests()) + lengthsOf(response.touchedFiles());
		return total;
	}

	private static long lengthOf(String value) {
		return value == null ? 0 : value.length();
	}

	private static long lengthsOf(List<String> values) {
		if (values == null) {
			return 0;
		}
		long total = 0;
		for (String value : values) {
			total += lengthOf(value);
		}
		return total;
	}

	private String cacheKey(
			String diff, String language, String style, Integer maxSummaryLength, AiProvider provider) {
		MultiAiConfigurationProperties ai = multiAiConfigurationProperties;
		String raw = provider.getValue() + "\n" + language + "\n" + style + "\n" + maxSummaryLength
				+ "\n" + ai.getTemperature() + "\n" + ai.getMaxTokens()
				+ "\n" + promptBuilderService.templateHash() + "\n" + diff.trim();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(raw.hashCode());
		}
	}

	/**
	 * Returns {@code givenValue} if it is not {@code null}, blank, or empty; otherwise, returns {@code defaultValue}.
	 *
	 * @param givenValue   the value to use if it is not blank or empty; may be {@code null}
	 * @param defaultValue the default value to return if {@code givenValue} is blank, empty, or {@code null}
	 * @return {@code givenValue} if non-blank; otherwise, {@code defaultValue}
	 */
	private String useDefaultIfBlank(String givenValue, String defaultValue) {
		return (givenValue == null || givenValue.isBlank()) ? defaultValue : givenValue;
	}
}
