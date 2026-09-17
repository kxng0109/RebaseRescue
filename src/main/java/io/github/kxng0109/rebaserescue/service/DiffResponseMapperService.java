package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.api.dto.AiCallMetadata;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.ModelAnalyzeDiffResult;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.PrCopilotAnalysisProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotLoggingProperties;
import io.github.kxng0109.rebaserescue.error.ModelOutputParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for analyzing and building structured responses based on code diffs and AI-generated outputs.
 * <p>
 * Provides functionality to map AI responses to domain-specific objects, sanitize model outputs, and extract metadata
 * or file information from diffs in unified diff format.
 */
@Service
@Slf4j
@RequiredArgsConstructor
class DiffResponseMapperService {

	private static final Pattern DIFF_GIT_LINE_PATTERN = Pattern.compile("^diff --git a/(.+?) b/(.+?)$");

	private final ObjectMapper objectMapper;
	private final PrCopilotLoggingProperties loggingProperties;
	private final PrCopilotAnalysisProperties analysisProperties;

	/**
	 * Maps the AI model's raw {@code ChatResponse} output and related metadata to an {@code AnalyzeDiffResponse}.
	 *
	 * @param response     the AI model's raw response, must not be {@code null}
	 * @param responseTime the time taken by the model to respond, in milliseconds, must be non-negative
	 * @param diff         the code diff content used for analysis, must not be {@code null} or empty
	 * @param requestId    the unique request identifier, must not be {@code null} or empty
	 * @param provider     the name of the AI provider, must not be {@code null} or empty
	 * @return a fully populated {@code AnalyzeDiffResponse}, never {@code null}
	 * @throws ModelOutputParseException if the AI model's output is invalid or missing required fields
	 * @throws RuntimeException          if an unexpected error occurs during mapping
	 */
	public AnalyzeDiffResponse mapToAnalyzeDiffResponse(
			ChatResponse response,
			long responseTime,
			String diff,
			String requestId,
			String provider
	) {
		String modelOutput = extractModelOutputText(response);
		if (modelOutput.length() > analysisProperties.getMaxModelOutputChars()) {
			throw new ModelOutputParseException(
					"Model output exceeded maximum allowed size of "
							+ analysisProperties.getMaxModelOutputChars() + " characters");
		}
		if (log.isDebugEnabled()) {
			log.debug("AI model raw output: {} chars", modelOutput.length());
		}
		String cleanedModelOutput = sanitizeModelOutput(modelOutput);
		if (log.isDebugEnabled()) {
			log.debug("AI model cleaned output: {} chars", cleanedModelOutput.length());
		}

		try {
			ModelAnalyzeDiffResult aiResult = objectMapper.readValue(cleanedModelOutput, ModelAnalyzeDiffResult.class);
			log.debug("AI model analysis result parsed");

			if (aiResult == null) {
				throw new ModelOutputParseException("Parsed model output is null. Expected non-null, valid JSON DTO.");
			}

			if (aiResult.title() == null || aiResult.summary() == null || aiResult.details() == null
					|| aiResult.risks() == null || aiResult.suggestedTests() == null) {
				throw new ModelOutputParseException(
						"Parsed model output is missing required fields. Output preview: "
								+ cleanedModelOutput.substring(0, Math.min(cleanedModelOutput.length(), 500)));
			}

			List<RiskItem> risks = normalizeRisks(
					cap(aiResult.risks(), analysisProperties.getMaxRisks(), "risks"));

			if (loggingProperties.isLogResponses() && log.isInfoEnabled()) {
				log.info(
						"AI analysis complete for requestId {} (title chars={}, risks={})", requestId,
						aiResult.title() == null ? 0 : aiResult.title().length(), risks.size()
				);
			}

			String model = response.getMetadata().getModel();
			Integer tokensUsed = (response.getMetadata().getUsage() != null
					&& response.getMetadata().getUsage().getTotalTokens() != null)
					? response.getMetadata().getUsage().getTotalTokens()
					: null;

			List<String> modelTouchedFiles =
					cap(aiResult.touchedFiles(), analysisProperties.getMaxTouchedFiles(), "touchedFiles");
			List<String> touchedFiles = (modelTouchedFiles == null || modelTouchedFiles.isEmpty())
					? extractTouchedFilesFromDiff(diff)
					: modelTouchedFiles;

			AiCallMetadata metadata = AiCallMetadata.builder()
			                                        .modelName(model)
			                                        .provider(provider)
			                                        .tokensUsed(tokensUsed)
			                                        .modelLatencyMs(responseTime)
			                                        .build();

			return AnalyzeDiffResponse.builder()
			                          .title(aiResult.title())
			                          .risks(risks)
			                          .riskScore(riskScore(risks))
			                          .summary(aiResult.summary())
			                          .suggestedTests(cap(
					                          aiResult.suggestedTests(),
					                          analysisProperties.getMaxSuggestedTests(), "suggestedTests"
			                          ))
			                          .details(aiResult.details())
			                          .analysisNotes(aiResult.analysisNotes())
			                          .touchedFiles(touchedFiles)
			                          .metadata(metadata)
			                          .rawModelOutput(
					                          analysisProperties.isIncludeRawModelOutput()
							                          ? modelOutput
							                          : null
			                          )
			                          .requestId(requestId)
			                          .build();

		} catch (JacksonException e) {
			log.warn("JSON parsing failed for model output: {}", e.getMessage());
			throw new ModelOutputParseException("Model returned invalid JSON output. " +
					                                    "Error details: " + e.getMessage());
		} catch (ModelOutputParseException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Unexpected error mapping AI output", e);
		}
	}

	/**
	 * Truncates a model-provided list to a hard ceiling. Guards against list-bomb payloads (e.g. tens of thousands of
	 * tiny objects inside the char budget).
	 *
	 * @param list  the model-provided list, may be {@code null}
	 * @param max   the maximum entries to keep, must be positive
	 * @param field the field name, for logging only
	 * @return the original list when within budget, a truncated snapshot when over, or {@code null} when the input was
	 * {@code null}
	 */
	private static <T> List<T> cap(List<T> list, int max, String field) {
		if (list == null || list.size() <= max) {
			return list;
		}
		log.warn("Model output field '{}' truncated from {} to {} entries", field, list.size(), max);
		return List.copyOf(list.subList(0, max));
	}

	/**
	 * Validates and normalizes model-provided risks onto the contract levels.
	 *
	 * @param risks raw risks; must not be {@code null} (null is a missing-field error)
	 * @return normalized risks, never {@code null}
	 * @throws ModelOutputParseException on blank messages or unrecognized levels
	 */
	private List<RiskItem> normalizeRisks(List<RiskItem> risks) {
		if (risks == null) {
			throw new ModelOutputParseException("Parsed model output is missing required field: risks.");
		}
		try {
			return risks.stream()
			            .map(r -> {
				            if (r == null || r.message() == null || r.message().isBlank()) {
					            throw new IllegalArgumentException("Risk message must not be blank");
				            }
				            return new RiskItem(RiskItem.normalizeLevel(r.level()), r.message().trim());
			            })
			            .toList();
		} catch (IllegalArgumentException e) {
			throw new ModelOutputParseException("Invalid risk entry: " + e.getMessage());
		}
	}

	/**
	 * Deterministic 0-100 score: error=25, warning=10, note=2, capped at 100.
	 */
	static int riskScore(List<RiskItem> risks) {
		int score = 0;
		for (RiskItem risk : risks) {
			score += switch (risk.level()) {
				case "error" -> 25;
				case "warning" -> 10;
				default -> 2;
			};
		}
		return Math.min(score, 100);
	}

	private String extractModelOutputText(ChatResponse response) {
		String aiRawResponse = null;

		try {
			aiRawResponse = response.getResult().getOutput().getText();
		} catch (Exception e) {
			log.error("Could not extract text from ChatResponse result/output.", e);
			throw new ModelOutputParseException("Could not extract text from AI model response.");
		}

		if (aiRawResponse == null || aiRawResponse.isBlank()) {
			throw new ModelOutputParseException("AI model returned empty output; cannot parse.");
		}

		return aiRawResponse;
	}

	/**
	 * Sanitizes the output of a model by removing extra formatting or wrapping elements.
	 *
	 * @param value the raw output string, may be {@code null} or blank
	 * @return the sanitized string, never {@code null}. Returns an empty string if the input was {@code null} or blank.
	 */
	private String sanitizeModelOutput(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		value = value.replaceFirst("(?s)^```(?:json)?\\s*\\n?", "");
		value = value.replaceFirst("(?s)\\n?```$", "");
		value = value.trim();

		if (!value.startsWith("{")) {
			int start = value.indexOf("{");
			int end = value.lastIndexOf("}");
			if (start != -1 && end != -1 && end > start) {
				value = value.substring(start, end + 1);
			}
		}

		return value;
	}

	/**
	 * Extracts the set of file paths touched by a diff.
	 *
	 * @param diff the diff content in unified diff format, may be {@code null} or blank
	 * @return an unmodifiable list of unique file paths, never {@code null}
	 */
	private List<String> extractTouchedFilesFromDiff(String diff) {
		if (diff == null || diff.trim().isEmpty() || diff.isBlank()) {
			return List.of();
		}

		LinkedHashSet<String> files = new LinkedHashSet<>();

		String[] lines = diff.split("\\R");
		for (String line : lines) {
			Matcher matcher = DIFF_GIT_LINE_PATTERN.matcher(line);
			if (matcher.matches()) {
				String newPath = matcher.group(2);
				files.add(newPath);
			}
		}

		return List.copyOf(files);
	}
}
