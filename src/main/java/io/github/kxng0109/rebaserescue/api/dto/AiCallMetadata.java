package io.github.kxng0109.rebaserescue.api.dto;

import lombok.Builder;

/**
 * Metadata regarding an AI model invocation.
 *
 * @param modelName      the name of the AI model used, may be {@code null}
 * @param provider       the provider of the AI model, may be {@code null}
 * @param modelLatencyMs the latency of the model call in milliseconds
 * @param tokensUsed     the number of tokens used in the AI call, may be {@code null}
 */
@Builder
public record AiCallMetadata(
		String modelName,
		String provider,
		long modelLatencyMs,
		Integer tokensUsed
) {
}
