package io.github.kxng0109.rebaserescue.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Service for constructing {@code Prompt} objects to analyze Git diffs.
 * <p>
 * This service reads system-level prompt templates and combines them with user-provided parameters to create structured
 * prompts that adhere to the required analysis details.
 */
@Service
@Slf4j
class PromptBuilderService {

	@Value("${prcopilot.prompts.system-prompt}")
	private Resource systemPromptResource;

	private String cachedSystemPromptTemplate;

	@PostConstruct
	void loadSystemPromptAtStartup() {
		cachedSystemPromptTemplate = readSystemPromptResource();
		log.debug("System prompt cached ({} chars)", cachedSystemPromptTemplate.length());
	}

	/**
	 * Short hash of the cached system-prompt template for cache-key versioning.
	 *
	 * @return 16-char hex hash, never {@code null}
	 */
	public String templateHash() {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
			                                           .digest(cachedSystemPromptTemplate.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (java.security.NoSuchAlgorithmException e) {
			return Integer.toHexString(cachedSystemPromptTemplate.hashCode());
		}
	}

	/**
	 * Builds a {@code Prompt} for analyzing a Git diff based on the provided parameters.
	 * <p>
	 * Combines system-level prompt templates with dynamically generated user messages to construct a structured
	 * {@code Prompt} for diff analysis.
	 *
	 * @param language         the programming language associated with the diff, must not be {@code null} or empty
	 * @param style            the style or tone to use in the analysis, must not be {@code null} or empty
	 * @param diff             the Git diff content to be analyzed, must not be {@code null} or empty
	 * @param maxSummaryLength the optional maximum length for the summary in the analysis, may be {@code null}
	 * @param requestId        the optional request identifier to trace this analysis, may be {@code null} or blank
	 * @return a {@code Prompt} object containing structured messages ready for diff analysis, never {@code null}
	 */
	public Prompt buildDiffAnalysisPrompt(
			String language,
			String style,
			String diff,
			Integer maxSummaryLength,
			String requestId
	) {
		SystemPromptTemplate promptTemplate = new SystemPromptTemplate(cachedSystemPromptTemplate);
		Message systemMessage = promptTemplate.createMessage(
				Map.of("language", language, "style", style)
		);

		StringBuilder userContent = new StringBuilder();
		userContent.append("Please analyze this Git diff with strict adherence to instructions.\n");
		userContent.append("language: ").append(language).append("\n");
		userContent.append("style: ").append(style).append("\n");
		if (maxSummaryLength != null) {
			userContent.append("maxSummaryLength: ").append(maxSummaryLength).append("\n");
		}
		if (requestId != null && !requestId.isBlank()) {
			userContent.append("requestId: ").append(requestId).append("\n");
		}
		userContent.append("Diff: ```").append(diff).append("\n```");

		UserMessage userMessage = new UserMessage(userContent.toString());

		return new Prompt(
				List.of(systemMessage, userMessage)
		);
	}


	/**
	 * Reads the system prompt content from the configured resource.
	 *
	 * @return the prompt template content, never {@code null}
	 * @throws RuntimeException if an I/O error occurs while reading the resource
	 */
	private String readSystemPromptResource() {
		try {
			return new String(systemPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("Could not load system prompt: {}", e.getMessage(), e);
			throw new RuntimeException("Could not load system prompt.", e);
		}
	}
}
